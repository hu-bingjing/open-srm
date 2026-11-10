"""Business acceptance examples; production DB/HTTP verification remains required."""
from copy import deepcopy
from dataclasses import replace
from decimal import Decimal
import unittest

from specs.domain import Actor, Grant, RuleError, ScheduleSpec, Scope, authorize, quantity


def actor(name, kind, permissions, supplier=None):
    return Actor(name, kind, tuple(Grant(p, "Company A", "Plant A", "Purchasing Org A") for p in permissions), supplier)


class DomainTests(unittest.TestCase):
    def setUp(self):
        self.order = ScheduleSpec()
        self.supplier = actor("supplier-user-a", "supplier", ("confirmation.submit", "asn.submit"), "Supplier A")
        self.approver = actor("buyer-a", "buyer", ("change.approve",))
        self.erp = actor("mock-erp", "integration", ("order.import", "receipt.import"))

    def confirm(self, qty="100", promised="2027-01-10", key="confirm-0001"):
        return self.order.confirm(self.supplier, key, qty, promised, self.order.revision)

    def ship(self, qty="30", key="shipment-0001"):
        return self.order.ship(self.supplier, key, qty, self.order.revision)["id"]

    def test_partial_confirmation_accumulates(self):
        self.confirm("60")
        self.assertEqual(self.order.confirmed, Decimal(60))
        self.confirm("40", key="confirm-0002")
        self.assertEqual(self.order.confirmed, Decimal(100))

    def test_overconfirmation_rejected_atomically(self):
        self.confirm("60")
        before = deepcopy(self.order.__dict__)
        with self.assertRaisesRegex(RuleError, "QUANTITY_EXCEEDED"):
            self.confirm("41", key="confirm-0002")
        self.assertEqual(self.order.__dict__, before)

    def test_pending_confirmation_reserves_confirmation_budget(self):
        self.confirm("60", "2027-02-01")
        with self.assertRaisesRegex(RuleError, "QUANTITY_EXCEEDED"):
            self.confirm("50", key="confirm-0002")

    def test_late_confirmation_requires_approval(self):
        result = self.confirm("60", "2027-02-01")
        self.assertEqual(result["status"], "SUBMITTED")
        self.assertEqual(self.order.confirmed, 0)
        self.assertEqual(self.order.available, 0)
        self.order.decide(self.approver, "approval-0001", result["id"], True, 1)
        self.assertEqual(self.order.confirmed, 60)

    def test_unapproved_late_quantity_cannot_ship(self):
        self.confirm("60", "2027-02-01")
        with self.assertRaisesRegex(RuleError, "QUANTITY_EXCEEDED"):
            self.ship()

    def test_rejection_releases_confirmation_budget(self):
        result = self.confirm("60", "2027-02-01")
        self.order.decide(self.approver, "approval-0001", result["id"], False, 1)
        self.confirm("100", key="confirm-0002")
        self.assertEqual(self.order.confirmed, 100)

    def test_self_approval_denied_even_with_permission(self):
        result = self.confirm("60", "2027-02-01")
        same_person = actor(self.supplier.id, "buyer", ("change.approve",))
        with self.assertRaisesRegex(RuleError, "SELF_APPROVAL_FORBIDDEN"):
            self.order.decide(same_person, "approval-0001", result["id"], True, 1)

    def test_second_decision_denied(self):
        result = self.confirm("60", "2027-02-01")
        self.order.decide(self.approver, "approval-0001", result["id"], True, 1)
        with self.assertRaisesRegex(RuleError, "ALREADY_DECIDED"):
            self.order.decide(self.approver, "approval-0002", result["id"], False, 1)

    def test_stale_approval_denied(self):
        result = self.confirm("60", "2027-02-01")
        self.order.revise(self.erp, "revision-0001", 2, "100")
        with self.assertRaisesRegex(RuleError, "SOURCE_REVISION_CONFLICT"):
            self.order.decide(self.approver, "approval-0001", result["id"], True, 1)
        self.assertEqual(self.order.confirmations[result["id"]]["change_status"], "SUPERSEDED")

    def test_asn_is_not_receipt(self):
        self.confirm("60")
        self.ship()
        self.assertEqual(self.order.received, 0)
        self.assertEqual(self.order.in_transit, 30)
        self.assertEqual(self.order.available, 30)

    def test_partial_receipt_and_second_shipment(self):
        self.confirm("60")
        aid = self.ship()
        self.order.receive(self.erp, "receipt-0001", "GR-A", "20", aid)
        self.assertEqual((self.order.received, self.order.in_transit, self.order.available), (20, 10, 30))
        self.ship("30", "shipment-0002")
        self.assertEqual(self.order.available, 0)

    def test_overship_rejected_without_side_effect(self):
        self.confirm("60")
        self.ship("50")
        before = deepcopy(self.order.__dict__)
        with self.assertRaisesRegex(RuleError, "QUANTITY_EXCEEDED"):
            self.ship("11", "shipment-0002")
        self.assertEqual(self.order.__dict__, before)

    def test_receipt_requires_service_identity(self):
        forged = actor("buyer-b", "buyer", ("receipt.import",))
        with self.assertRaisesRegex(RuleError, "ERP_AUTHORITY_REQUIRED"):
            self.order.receive(forged, "receipt-0001", "GR-A", "10")

    def test_unlinked_receipt_reduces_available(self):
        self.confirm("60")
        self.ship("30")
        self.order.receive(self.erp, "receipt-0001", "GR-A", "10")
        self.assertEqual((self.order.received, self.order.in_transit, self.order.available), (10, 30, 20))

    def test_duplicate_erp_event_new_request_key_not_double_counted(self):
        self.order.receive(self.erp, "receipt-0001", "GR-A", "10")
        self.order.receive(self.erp, "receipt-0002", "GR-A", "10")
        self.assertEqual(self.order.received, 10)

    def test_source_event_conflicting_content_rejected(self):
        self.order.receive(self.erp, "receipt-0001", "GR-A", "10")
        with self.assertRaisesRegex(RuleError, "SOURCE_EVENT_CONFLICT"):
            self.order.receive(self.erp, "receipt-0002", "GR-A", "11")
        self.assertEqual(self.order.received, 10)

    def test_overreceipt_is_authoritative_and_freezes_order(self):
        self.order.receive(self.erp, "receipt-0001", "GR-A", "101")
        self.assertEqual(self.order.received, 101)
        self.assertTrue(self.order.hold)

    def test_receipt_after_cancellation_preserved(self):
        self.order.revise(self.erp, "revision-0001", 2, "100", True)
        self.order.receive(self.erp, "receipt-0001", "GR-A", "10")
        self.assertEqual(self.order.received, 10)
        self.assertEqual(self.order.lifecycle, "CANCELLED")
        self.assertTrue(self.order.hold)

    def test_partial_and_full_receipt_reversal(self):
        self.confirm()
        aid = self.ship()
        self.order.receive(self.erp, "receipt-0001", "GR-A", "20", aid)
        self.order.reverse(self.erp, "reverse-0001", "REV-A", "GR-A", "5")
        self.assertEqual((self.order.received, self.order.in_transit), (15, 15))
        self.assertEqual(self.order.receipts["GR-A"]["status"], "PARTIALLY_REVERSED")
        self.order.reverse(self.erp, "reverse-0002", "REV-B", "GR-A", "15")
        self.assertEqual((self.order.received, self.order.in_transit), (0, 30))
        self.assertEqual(self.order.receipts["GR-A"]["status"], "REVERSED")

    def test_overreversal_rejected(self):
        self.order.receive(self.erp, "receipt-0001", "GR-A", "10")
        with self.assertRaisesRegex(RuleError, "INVALID_REVERSAL"):
            self.order.reverse(self.erp, "reverse-0001", "REV-A", "GR-A", "11")
        self.assertEqual(self.order.received, 10)

    def test_duplicate_reversal_not_double_counted(self):
        self.order.receive(self.erp, "receipt-0001", "GR-A", "10")
        self.order.reverse(self.erp, "reverse-0001", "REV-A", "GR-A", "5")
        self.order.reverse(self.erp, "reverse-0002", "REV-A", "GR-A", "5")
        self.assertEqual(self.order.received, 5)

    def test_out_of_order_reversal_requires_quarantine(self):
        with self.assertRaisesRegex(RuleError, "INVALID_REVERSAL"):
            self.order.reverse(self.erp, "reverse-0001", "REV-A", "GR-A", "5")
        self.assertFalse(self.order.reversals)

    def test_cancel_unreceived_asn_releases_reservation(self):
        self.confirm("60")
        aid = self.ship()
        self.order.cancel_asn(self.supplier, "cancel-0001", aid)
        self.assertEqual((self.order.available, self.order.in_transit), (60, 0))

    def test_cancel_received_asn_denied(self):
        self.confirm()
        aid = self.ship()
        self.order.receive(self.erp, "receipt-0001", "GR-A", "1", aid)
        with self.assertRaisesRegex(RuleError, "ASN_CANNOT_CANCEL"):
            self.order.cancel_asn(self.supplier, "cancel-0001", aid)

    def test_revision_supersedes_confirmations(self):
        result = self.confirm()
        self.order.revise(self.erp, "revision-0001", 2, "90")
        self.assertEqual(self.order.confirmed, 0)
        self.assertEqual(self.order.confirmations[result["id"]]["status"], "SUPERSEDED")

    def test_shrink_below_in_transit_freezes_without_deleting(self):
        self.confirm()
        self.ship()
        self.order.revise(self.erp, "revision-0001", 2, "15")
        self.assertTrue(self.order.hold)
        self.assertEqual(self.order.in_transit, 30)
        with self.assertRaisesRegex(RuleError, "CONSISTENCY_HOLD"):
            self.confirm("1", key="confirm-0002")

    def test_cancellation_blocks_collaboration(self):
        self.order.revise(self.erp, "revision-0001", 2, "100", True)
        with self.assertRaisesRegex(RuleError, "INACTIVE"):
            self.confirm()

    def test_stale_supplier_revision_denied(self):
        self.order.revise(self.erp, "revision-0001", 2, "100")
        with self.assertRaisesRegex(RuleError, "SOURCE_REVISION_CONFLICT"):
            self.order.confirm(self.supplier, "confirm-0001", "10", "2027-01-10", 1)

    def test_frozen_supplier_cannot_confirm(self):
        self.order.supplier_active = False
        with self.assertRaisesRegex(RuleError, "SUPPLIER_FROZEN"):
            self.confirm()

    def test_idempotent_confirmation_replay_has_one_audit(self):
        first = self.confirm("60")
        self.assertEqual(first, self.confirm("60"))
        self.assertEqual(len(self.order.confirmations), 1)
        self.assertEqual(len(self.order.audit), 1)

    def test_idempotency_payload_conflict(self):
        self.confirm("60")
        with self.assertRaisesRegex(RuleError, "IDEMPOTENCY_CONFLICT"):
            self.confirm("61")

    def test_replay_does_not_bypass_revoked_grants(self):
        self.confirm("60")
        self.supplier = replace(self.supplier, grants=())
        with self.assertRaisesRegex(RuleError, "NOT_FOUND_OR_DENIED"):
            self.confirm("60")

    def test_replay_does_not_bypass_disabled_user(self):
        self.confirm("60")
        self.supplier = replace(self.supplier, active=False)
        with self.assertRaisesRegex(RuleError, "UNAUTHENTICATED"):
            self.confirm("60")

    def test_idempotency_key_is_actor_scoped(self):
        self.confirm("40")
        self.supplier = replace(self.supplier, id="supplier-user-a-2")
        self.confirm("40")
        self.assertEqual(self.order.confirmed, 80)

    def test_invalid_quantity_formats(self):
        for value in ("0", "-1", "NaN", "Infinity", "1e2", "01", "1.0000001", "10000000000000", 1.1, "0.000000"):
            with self.subTest(value=value), self.assertRaises(RuleError):
                quantity(value)

    def test_decimal_quantity_exact(self):
        self.order = ScheduleSpec("0.3")
        self.confirm("0.1")
        self.confirm("0.2", key="confirm-0002")
        self.assertEqual(self.order.confirmed, Decimal("0.3"))


class AuthorizationTests(unittest.TestCase):
    def test_cross_supplier_denied(self):
        person = actor("supplier-user-b", "supplier", ("confirmation.submit",), "Supplier B")
        with self.assertRaisesRegex(RuleError, "NOT_FOUND_OR_DENIED"):
            authorize(person, "confirmation.submit", Scope("Company A", "Plant A", "Purchasing Org A"), "Supplier A")

    def test_cross_company_denied(self):
        person = actor("buyer", "buyer", ("order.read",))
        with self.assertRaises(RuleError):
            authorize(person, "order.read", Scope("Company B", "Plant B", "Purchasing Org A"), "Supplier A")

    def test_grants_cannot_be_spliced(self):
        person = Actor("buyer", "buyer", (Grant("order.read", "Company A", "Plant A", "Purchasing Org A"),
                                          Grant("order.read", "Company B", "Plant B", "Purchasing Org B")))
        with self.assertRaises(RuleError):
            authorize(person, "order.read", Scope("Company A", "Plant B", "Purchasing Org B"), "Supplier A")

    def test_company_wildcard_covers_its_own_plants_only(self):
        person = Actor("buyer", "buyer", (Grant("order.read", "Company A"),))
        authorize(person, "order.read", Scope("Company A", "Plant A", "Purchasing Org A"), "Supplier A")
        with self.assertRaises(RuleError):
            authorize(person, "order.read", Scope("Company B", "Plant B", "Purchasing Org B"), "Supplier A")

    def test_admin_kind_without_business_grants_denied(self):
        person = Actor("admin", "admin", ())
        with self.assertRaises(RuleError):
            authorize(person, "order.read", Scope("Company A", "Plant A", "Purchasing Org A"), "Supplier A")


if __name__ == "__main__":
    unittest.main()
