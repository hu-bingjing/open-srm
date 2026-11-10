"""Executable single-schedule design examples, NOT a service or production backend.

No persistence, concurrency, HTTP, multiple-plan allocations or actual ERP adapter.
Use acceptance cases when implementing transactional Java modules.
"""
from copy import deepcopy
from dataclasses import dataclass
from datetime import date
from decimal import Decimal
import hashlib
import json
import re


class RuleError(ValueError):
    pass


def quantity(value):
    if not isinstance(value, str) or not re.fullmatch(r"(0|[1-9][0-9]{0,12})(\.[0-9]{1,6})?", value):
        raise RuleError("INVALID_QUANTITY")
    result = Decimal(value)
    if result <= 0:
        raise RuleError("INVALID_QUANTITY")
    return result


@dataclass(frozen=True)
class Scope:
    company: str
    plant: str
    purchasing_org: str


@dataclass(frozen=True)
class Grant:
    permission: str
    company: str
    plant: str | None = None
    purchasing_org: str | None = None

    def covers(self, permission, scope):
        return (self.permission == permission and self.company == scope.company
                and self.plant in (None, scope.plant)
                and self.purchasing_org in (None, scope.purchasing_org))


@dataclass(frozen=True)
class Actor:
    id: str
    kind: str
    grants: tuple[Grant, ...]
    supplier: str | None = None
    active: bool = True


def authorize(actor, permission, scope, supplier):
    if not actor.active:
        raise RuleError("UNAUTHENTICATED")
    if not any(g.covers(permission, scope) for g in actor.grants):
        raise RuleError("NOT_FOUND_OR_DENIED")
    if actor.kind == "supplier" and actor.supplier != supplier:
        raise RuleError("NOT_FOUND_OR_DENIED")
    if permission in ("confirmation.submit", "asn.submit") and actor.kind != "supplier":
        raise RuleError("SUPPLIER_REQUIRED")
    if permission in ("receipt.import", "order.import") and actor.kind != "integration":
        raise RuleError("ERP_AUTHORITY_REQUIRED")


class ScheduleSpec:
    """One order/one schedule, trusted synthetic ERP identifiers and scope."""

    def __init__(self, ordered="100", revision=1):
        self.scope = Scope("Company A", "Plant A", "Purchasing Org A")
        self.supplier = "Supplier A"
        self.ordered = quantity(ordered)
        self.revision = revision  # Synthetic comparable sequence; production port uses opaque revision mapping.
        self.requested_date = date(2027, 1, 10)
        self.lifecycle = "ACTIVE"
        self.hold = False
        self.supplier_active = True
        self.confirmations = {}
        self.asns = {}
        self.receipts = {}
        self.reversals = {}
        self.audit = []
        self.idempotency = {}

    @property
    def confirmed(self):
        return sum((v["quantity"] for v in self.confirmations.values()
                    if v["status"] == "EFFECTIVE" and v["revision"] == self.revision), Decimal(0))

    @property
    def received(self):
        return sum((v["quantity"] - v["reversed"] for v in self.receipts.values()), Decimal(0))

    @property
    def in_transit(self):
        return sum((v["quantity"] - v["received"] for v in self.asns.values()
                    if v["status"] == "SUBMITTED"), Decimal(0))

    @property
    def available(self):
        return max(Decimal(0), min(self.ordered, self.confirmed) - self.received - self.in_transit)

    def _current(self, revision):
        if revision != self.revision:
            raise RuleError("SOURCE_REVISION_CONFLICT")
        if self.lifecycle != "ACTIVE" or self.hold:
            raise RuleError("CONSISTENCY_HOLD_OR_INACTIVE")
        if not self.supplier_active:
            raise RuleError("SUPPLIER_FROZEN")

    def _run(self, actor, permission, key, payload, command):
        # Reauthorize before replay, including revoked supplier/user access.
        authorize(actor, permission, self.scope, self.supplier)
        if not isinstance(key, str) or not 8 <= len(key) <= 128:
            raise RuleError("INVALID_IDEMPOTENCY_KEY")
        digest = hashlib.sha256(json.dumps(payload, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
        identity = (actor.id, permission, key)
        previous = self.idempotency.get(identity)
        if previous:
            if previous[0] != digest:
                raise RuleError("IDEMPOTENCY_CONFLICT")
            return deepcopy(previous[1])
        before = deepcopy(self.__dict__)
        try:
            result = command()
            self.audit.append({"actor": actor.id, "operation": permission, "revision": self.revision})
            self.idempotency[identity] = (digest, deepcopy(result))
            return result
        except Exception:
            self.__dict__.clear()
            self.__dict__.update(before)
            raise

    def confirm(self, actor, key, qty, promised_date, revision):
        def command():
            self._current(revision)
            amount = quantity(qty)
            promised = date.fromisoformat(promised_date)
            committed = sum((v["quantity"] for v in self.confirmations.values()
                             if v["status"] in ("SUBMITTED", "EFFECTIVE") and v["revision"] == revision), Decimal(0))
            if committed + amount > self.ordered:
                raise RuleError("QUANTITY_EXCEEDED")
            cid = f"CONF-{len(self.confirmations) + 1}"
            status = "SUBMITTED" if promised > self.requested_date else "EFFECTIVE"
            self.confirmations[cid] = {"quantity": amount, "promised_date": promised, "revision": revision,
                                       "requester": actor.id, "status": status,
                                       "change_status": "SUBMITTED" if status == "SUBMITTED" else None}
            return {"id": cid, "status": status}
        return self._run(actor, "confirmation.submit", key, [qty, promised_date, revision], command)

    def decide(self, actor, key, confirmation_id, approve, revision):
        def command():
            self._current(revision)
            confirmation = self.confirmations.get(confirmation_id)
            if not confirmation or confirmation["revision"] != revision:
                raise RuleError("SOURCE_REVISION_CONFLICT")
            if confirmation["requester"] == actor.id:
                raise RuleError("SELF_APPROVAL_FORBIDDEN")
            if confirmation["change_status"] != "SUBMITTED":
                raise RuleError("ALREADY_DECIDED")
            confirmation["status"] = "EFFECTIVE" if approve else "REJECTED"
            confirmation["change_status"] = "APPROVED" if approve else "REJECTED"
            return {"id": confirmation_id, "status": confirmation["change_status"]}
        return self._run(actor, "change.approve", key, [confirmation_id, approve, revision], command)

    def ship(self, actor, key, qty, revision):
        def command():
            self._current(revision)
            amount = quantity(qty)
            if amount > self.available:
                raise RuleError("QUANTITY_EXCEEDED")
            aid = f"ASN-{len(self.asns) + 1}"
            self.asns[aid] = {"quantity": amount, "received": Decimal(0), "revision": revision, "status": "SUBMITTED"}
            return {"id": aid, "status": "SUBMITTED"}
        return self._run(actor, "asn.submit", key, [qty, revision], command)

    def cancel_asn(self, actor, key, asn_id):
        def command():
            asn = self.asns.get(asn_id)
            if not asn or asn["status"] != "SUBMITTED" or asn["received"] > 0:
                raise RuleError("ASN_CANNOT_CANCEL")
            # Specification assumes mock publisher has not accepted it; production must query ERP.
            asn["status"] = "CANCELLED"
            return {"id": asn_id, "status": "CANCELLED"}
        return self._run(actor, "asn.submit", key, ["cancel", asn_id], command)

    def receive(self, actor, key, event_id, qty, asn_id=None):
        def command():
            amount = quantity(qty)
            existing = self.receipts.get(event_id)
            if existing:
                if (existing["quantity"], existing["asn_id"]) != (amount, asn_id):
                    raise RuleError("SOURCE_EVENT_CONFLICT")
                return {"id": event_id, "status": "DUPLICATE"}
            allocated = Decimal(0)
            if asn_id is not None:
                asn = self.asns.get(asn_id)
                if not asn or asn["status"] != "SUBMITTED":
                    raise RuleError("UNMATCHED_ASN")
                allocated = min(amount, asn["quantity"] - asn["received"])
                asn["received"] += allocated
            self.receipts[event_id] = {"quantity": amount, "reversed": Decimal(0), "asn_id": asn_id,
                                        "allocated": allocated, "status": "POSTED"}
            if self.lifecycle != "ACTIVE" or self.received + self.in_transit > self.ordered:
                self.hold = True
            return {"id": event_id, "status": "POSTED"}
        return self._run(actor, "receipt.import", key, [event_id, qty, asn_id], command)

    def reverse(self, actor, key, event_id, original_id, qty):
        def command():
            amount = quantity(qty)
            existing = self.reversals.get(event_id)
            if existing:
                if existing != (original_id, amount):
                    raise RuleError("SOURCE_EVENT_CONFLICT")
                return {"id": event_id, "status": "DUPLICATE"}
            receipt = self.receipts.get(original_id)
            if not receipt or receipt["reversed"] + amount > receipt["quantity"]:
                raise RuleError("INVALID_REVERSAL")
            # Unallocated quantity is reversed first, then original ASN allocation.
            unallocated = receipt["quantity"] - receipt["reversed"] - receipt["allocated"]
            release = max(Decimal(0), amount - unallocated)
            if receipt["asn_id"]:
                self.asns[receipt["asn_id"]]["received"] -= release
            receipt["allocated"] -= release
            receipt["reversed"] += amount
            receipt["status"] = "REVERSED" if receipt["reversed"] == receipt["quantity"] else "PARTIALLY_REVERSED"
            self.reversals[event_id] = (original_id, amount)
            return {"id": event_id, "status": receipt["status"]}
        return self._run(actor, "receipt.import", key, ["reversal", event_id, original_id, qty], command)

    def revise(self, actor, key, new_revision, qty, cancelled=False):
        def command():
            if not isinstance(new_revision, int) or new_revision <= self.revision:
                raise RuleError("SOURCE_REVISION_CONFLICT")
            new_qty = quantity(qty)
            self.revision = new_revision
            self.ordered = new_qty
            for value in self.confirmations.values():
                if value["status"] in ("SUBMITTED", "EFFECTIVE"):
                    value["status"] = "SUPERSEDED"
                    if value["change_status"] == "SUBMITTED":
                        value["change_status"] = "SUPERSEDED"
            # Conservatively require explicit reconciliation for any in-transit old version.
            self.hold = self.in_transit > 0 or self.received + self.in_transit > new_qty
            if cancelled:
                self.lifecycle = "CANCELLED"
                self.hold = self.hold or self.received > 0
            return {"revision": new_revision, "hold": self.hold, "lifecycle": self.lifecycle}
        return self._run(actor, "order.import", key, [new_revision, qty, cancelled], command)
