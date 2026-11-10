"""Generate the Phase 00 contract; no network or application runtime."""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def ref(name):
    return {"$ref": f"#/components/schemas/{name}"}


def obj(properties, required=()):
    return {"type": "object", "additionalProperties": False,
            "properties": properties, "required": list(required)}


def build():
    string = {"type": "string", "minLength": 1, "maxLength": 200}
    uuid = {"type": "string", "format": "uuid"}
    date = {"type": "string", "format": "date"}
    timestamp = {"type": "string", "format": "date-time"}
    quantity = {"type": "string", "pattern": r"^(0|[1-9][0-9]{0,12})(\.[0-9]{1,6})?$"}
    positive = {"type": "string", "pattern": r"^(?=.*[1-9])(0|[1-9][0-9]{0,12})(\.[0-9]{1,6})?$"}
    schemas = {"Quantity": quantity, "PositiveQuantity": positive}
    schemas["Problem"] = obj({"code": string, "status": {"type": "integer", "minimum": 400, "maximum": 599},
                              "title": string, "requestId": uuid,
                              "fieldErrors": {"type": "array", "items": obj({"field": string, "code": string}, ("field", "code"))}},
                             ("code", "status", "title", "requestId"))
    schemas["CsrfToken"] = obj({"csrfToken": string}, ("csrfToken",))
    schemas["LoginRequest"] = obj({"login": string, "password": {"type": "string", "format": "password", "minLength": 1, "maxLength": 256}}, ("login", "password"))
    login_field = {"type": "string", "pattern": "^[a-z0-9][a-z0-9._-]{2,79}$"}
    display_name = {"type": "string", "minLength": 1, "maxLength": 120}
    roles_field = {"type": "array", "minItems": 1, "maxItems": 5, "items": {"enum": ["ADMIN", "AUDITOR", "USER", "BUYER", "SUPPLIER"]}, "description": "SUPPLIER must be the sole role"}
    password_field = {"type": "string", "minLength": 12, "maxLength": 72, "writeOnly": True, "description": "UTF-8 encoding must also be at most 72 bytes"}
    schemas["User"] = obj({"id": uuid, "login": login_field, "displayName": display_name, "status": {"enum": ["ACTIVE", "DISABLED"]},
                           "roles": roles_field, "version": {"type": "integer", "minimum": 0}}, ("id", "login", "displayName", "status", "roles", "version"))
    schemas["UserCreate"] = obj({"login": login_field, "displayName": display_name, "password": password_field, "roles": roles_field}, ("login", "displayName", "password", "roles"))
    schemas["UserUpdate"] = obj({"displayName": display_name, "status": {"enum": ["ACTIVE", "DISABLED"]}}, ("displayName", "status"))
    schemas["BootstrapStatus"] = obj({"setupRequired": {"type": "boolean"}}, ("setupRequired",))
    schemas["BootstrapRequest"] = obj({"bootstrapToken": {"type": "string", "minLength": 1, "maxLength": 256}, "login": login_field, "displayName": display_name, "password": password_field}, ("bootstrapToken", "login", "displayName", "password"))
    schemas["ProfileRequest"] = obj({"displayName": display_name}, ("displayName",))
    schemas["CompanyCreate"] = obj({"code": string, "name": string, "timezone": string}, ("code", "name", "timezone"))
    schemas["Company"] = obj({"id": uuid, **schemas["CompanyCreate"]["properties"]}, ("id", "code", "name", "timezone"))
    schemas["PlantCreate"] = obj({"code": string, "name": string, "companyId": uuid}, ("code", "name", "companyId"))
    schemas["Plant"] = obj({"id": uuid, **schemas["PlantCreate"]["properties"]}, ("id", "code", "name", "companyId"))
    schemas["PurchasingOrgCreate"] = obj({"code": string, "name": string, "companyIds": {"type": "array", "minItems": 1, "uniqueItems": True, "items": uuid}}, ("code", "name", "companyIds"))
    schemas["PurchasingOrg"] = obj({"id": uuid, **schemas["PurchasingOrgCreate"]["properties"]}, ("id", "code", "name", "companyIds"))
    schemas["SupplierCreate"] = obj({"code": string, "name": string}, ("code", "name"))
    schemas["SupplierUpdate"] = obj({"name": string, "status": {"enum": ["ACTIVE", "FROZEN"]}}, ("name", "status"))
    schemas["Supplier"] = obj({"id": uuid, "code": string, **schemas["SupplierUpdate"]["properties"]}, ("id", "code", "name", "status"))
    schemas["SupplierCompanyRequest"] = obj({"companyId": uuid, "active": {"type": "boolean"}}, ("companyId", "active"))
    schemas["GrantRequest"] = obj({"permission": string, "companyId": uuid, "plantId": uuid, "purchasingOrgId": uuid}, ("permission", "companyId"))
    schemas["Grant"] = obj({"id": uuid, **schemas["GrantRequest"]["properties"]}, ("id", "permission", "companyId"))
    schemas["RolesRequest"] = obj({"roles": roles_field}, ("roles",))
    schemas["SupplierBindingRequest"] = obj({"supplierId": uuid}, ("supplierId",))
    schemas["Schedule"] = obj({"id": uuid, "externalSchedule": string, "orderedQuantity": ref("Quantity"), "requestedDate": date,
                               "confirmedQuantity": ref("Quantity"), "netReceivedQuantity": ref("Quantity"), "inTransitQuantity": ref("Quantity"), "unit": string},
                              ("id", "externalSchedule", "orderedQuantity", "requestedDate", "confirmedQuantity", "netReceivedQuantity", "inTransitQuantity", "unit"))
    schemas["OrderItem"] = obj({"id": uuid, "externalItem": string, "plantId": uuid, "materialId": uuid,
                                "schedules": {"type": "array", "items": ref("Schedule")}}, ("id", "externalItem", "plantId", "materialId", "schedules"))
    schemas["Order"] = obj({"id": uuid, "externalId": string, "companyId": uuid, "purchasingOrgId": uuid, "supplierId": uuid,
                            "sourceRevision": string, "lifecycle": {"enum": ["ACTIVE", "CLOSED", "CANCELLED"]}, "consistencyHold": {"type": "boolean"},
                            "items": {"type": "array", "items": ref("OrderItem")}}, ("id", "externalId", "companyId", "purchasingOrgId", "supplierId", "sourceRevision", "lifecycle", "consistencyHold", "items"))
    schemas["OrderRevision"] = obj({"orderId": uuid, "sourceRevision": string, "receivedAt": timestamp, "contentHash": string}, ("orderId", "sourceRevision", "receivedAt", "contentHash"))
    schemas["ConfirmationRequest"] = obj({"scheduleId": uuid, "sourceRevision": string, "quantity": ref("PositiveQuantity"), "promisedDate": date}, ("scheduleId", "sourceRevision", "quantity", "promisedDate"))
    schemas["Confirmation"] = obj({"id": uuid, **schemas["ConfirmationRequest"]["properties"], "status": {"enum": ["DRAFT", "SUBMITTED", "EFFECTIVE", "REJECTED", "CANCELLED", "SUPERSEDED"]}}, ("id", "scheduleId", "sourceRevision", "quantity", "promisedDate", "status"))
    schemas["ChangeRequest"] = obj({"confirmationId": uuid, "sourceRevision": string, "proposedDate": date, "reason": string}, ("confirmationId", "sourceRevision", "proposedDate", "reason"))
    schemas["DeliveryChange"] = obj({"id": uuid, **schemas["ChangeRequest"]["properties"], "requesterId": uuid,
                                     "status": {"enum": ["DRAFT", "SUBMITTED", "APPROVED", "REJECTED", "WITHDRAWN", "SUPERSEDED"]}}, ("id", "confirmationId", "sourceRevision", "proposedDate", "reason", "requesterId", "status"))
    schemas["DecisionRequest"] = obj({"decision": {"enum": ["APPROVED", "REJECTED"]}, "reason": string}, ("decision", "reason"))
    schemas["DeliveryPlan"] = obj({"id": uuid, "confirmationId": uuid, "scheduleId": uuid, "quantity": ref("PositiveQuantity"), "dueDate": date,
                                   "status": {"enum": ["PLANNED", "PARTIALLY_SHIPPED", "SHIPPED", "CANCELLED", "SUPERSEDED"]}}, ("id", "confirmationId", "scheduleId", "quantity", "dueDate", "status"))
    schemas["AsnLineRequest"] = obj({"deliveryPlanId": uuid, "sourceRevision": string, "quantity": ref("PositiveQuantity")}, ("deliveryPlanId", "sourceRevision", "quantity"))
    schemas["AsnRequest"] = obj({"shipDate": date, "items": {"type": "array", "minItems": 1, "maxItems": 100, "items": ref("AsnLineRequest")}}, ("shipDate", "items"))
    schemas["Asn"] = obj({"id": uuid, "companyId": uuid, "supplierId": uuid, **schemas["AsnRequest"]["properties"],
                          "status": {"enum": ["DRAFT", "SUBMITTED", "ACKNOWLEDGED", "CANCELLED", "FAILED"]},
                          "publishStatus": {"enum": ["NOT_REQUESTED", "PENDING", "ACCEPTED", "REJECTED", "UNKNOWN"]}}, ("id", "companyId", "supplierId", "shipDate", "items", "status", "publishStatus"))
    schemas["GoodsReceipt"] = obj({"id": uuid, "scheduleId": uuid, "externalDocument": string, "fiscalYear": string, "externalItem": string,
                                   "quantity": ref("PositiveQuantity"), "eventType": {"enum": ["RECEIPT", "REVERSAL"]}, "reversalOf": uuid, "postingDate": date},
                                  ("id", "scheduleId", "externalDocument", "fiscalYear", "externalItem", "quantity", "eventType", "postingDate"))
    schemas["Notification"] = obj({"id": uuid, "title": string, "createdAt": timestamp, "read": {"type": "boolean"}}, ("id", "title", "createdAt", "read"))
    schemas["AuditEvent"] = obj({"id": uuid, "actorId": uuid, "operation": string, "objectId": uuid, "requestId": uuid, "createdAt": timestamp}, ("id", "actorId", "operation", "objectId", "requestId", "createdAt"))
    schemas["SyncJob"] = obj({"id": uuid, "connectionId": uuid, "entityType": string,
                              "status": {"enum": ["PENDING", "RUNNING", "SUCCEEDED", "FAILED", "QUARANTINED"]},
                              "processed": {"type": "integer", "minimum": 0}, "errorCode": string}, ("id", "connectionId", "entityType", "status", "processed"))
    schemas["SyncRequest"] = obj({"connectionId": uuid, "entityType": {"enum": ["ORGANIZATION", "SUPPLIER", "MATERIAL", "PURCHASE_ORDER", "GOODS_RECEIPT"]}}, ("connectionId", "entityType"))
    schemas["Dashboard"] = obj({"pendingConfirmations": {"type": "integer", "minimum": 0}, "pendingApprovals": {"type": "integer", "minimum": 0}, "openAsns": {"type": "integer", "minimum": 0}}, ("pendingConfirmations", "pendingApprovals", "openAsns"))
    # Phase 02 persisted DTOs supersede the original planning placeholders.
    code = {"type": "string", "pattern": "^[A-Za-z0-9][A-Za-z0-9._-]{0,39}$"}
    name_field = {"type": "string", "minLength": 1, "maxLength": 160}
    version = {"type": "integer", "minimum": 0}
    nullable_uuid = {"type": ["string", "null"], "format": "uuid"}
    company_ids = {"type": "array", "items": uuid, "minItems": 1, "maxItems": 100, "uniqueItems": True}
    for model in ("Company", "Plant", "PurchasingOrg"):
        fields = schemas[model + "Create"]["properties"]
        fields.update(code=code, name=name_field, active={"type": "boolean"})
        if model == "Company": fields["timezone"] = {"type": "string", "minLength": 1, "maxLength": 80}
        if model == "PurchasingOrg": fields["companyIds"] = company_ids
        schemas[model + "Create"] = obj(fields, fields.keys())
        output = {**fields, "id": uuid, "version": version}
        if model == "PurchasingOrg": output["companyIds"] = {"type": "array", "items": uuid}
        schemas[model] = obj(output, output.keys())
    schemas["SupplierCreate"] = obj({"code": code, "name": name_field, "companyIds": company_ids}, ("code", "name", "companyIds"))
    schemas["SupplierUpdate"]["properties"]["name"] = name_field
    link = obj({"companyId": uuid, "companyCode": code, "companyName": name_field, "active": {"type": "boolean"}}, ("companyId", "companyCode", "companyName", "active"))
    fields = {"id": uuid, "code": code, **schemas["SupplierUpdate"]["properties"], "version": version, "companies": {"type": "array", "items": link}, "canEdit": {"type": "boolean"}}
    schemas["Supplier"] = obj(fields, fields.keys())
    schemas["GrantRequest"]["properties"].update(permission={"enum": ["organization.read", "supplier.read", "supplier.manage", "audit.read", "order.read", "sync.manage"]}, plantId=nullable_uuid, purchasingOrgId=nullable_uuid)
    schemas["Grant"] = obj({"id": uuid, **schemas["GrantRequest"]["properties"]}, ("id", "permission", "companyId", "plantId", "purchasingOrgId"))
    schemas["GrantList"] = {"type": "array", "items": ref("Grant")}
    schemas["SupplierBindingRequest"] = obj({"supplierId": nullable_uuid}, ("supplierId",))
    schemas["Access"] = obj({"permissions": {"type": "array", "items": string}, "grants": ref("GrantList"), "supplierId": nullable_uuid}, ("permissions", "grants", "supplierId"))
    order_schedule = obj({"id": uuid, "externalSchedule": code, "orderedQuantity": ref("PositiveQuantity"), "requestedDate": date}, ("id", "externalSchedule", "orderedQuantity", "requestedDate"))
    fields = {"id": uuid, "externalItem": code, "plantId": uuid, "plantCode": code, "materialId": uuid, "materialCode": string, "materialName": name_field, "unit": string, "schedules": {"type": "array", "items": order_schedule}}
    schemas["OrderItem"] = obj(fields, fields.keys())
    fields = {"id": uuid, "externalId": string, "companyId": uuid, "companyCode": code, "purchasingOrgId": uuid, "purchasingOrgCode": code, "supplierId": uuid, "supplierCode": code, "sourceRevision": string, "sourceSequence": {"type": "integer", "minimum": 1}, "lifecycle": {"enum": ["ACTIVE", "CLOSED", "CANCELLED"]}, "consistencyHold": {"type": "boolean"}, "version": version, "items": {"type": "array", "items": ref("OrderItem")}}
    schemas["Order"] = obj(fields, fields.keys())
    fields = {"id": uuid, "orderId": uuid, "sourceRevision": string, "sourceSequence": {"type": "integer", "minimum": 1}, "lifecycle": {"enum": ["ACTIVE", "CLOSED", "CANCELLED"]}, "receivedAt": timestamp, "items": {"type": "array", "items": ref("OrderItem")}}
    schemas["OrderRevision"] = obj(fields, fields.keys())
    fields = {"name": name_field, "companyId": uuid, "purchasingOrgId": uuid, "supplierId": uuid, "plantIds": {"type": "array", "minItems": 1, "maxItems": 20, "uniqueItems": True, "items": uuid}}
    schemas["ConnectionCreate"] = obj(fields, fields.keys())
    fields = {**fields, "id": uuid, "adapterType": {"enum": ["mock"]}, "active": {"type": "boolean"}, "version": version}
    schemas["Connection"] = obj(fields, fields.keys())
    scenarios = {"enum": ["INITIAL", "UPDATE", "DUPLICATE", "OUT_OF_ORDER", "CONFLICT", "CANCEL", "CLOSE", "REOPEN", "REMOVE_LINE", "INVALID_MAPPING", "FAIL_ONCE", "PERMANENT_FAIL"]}
    schemas["SyncRequest"] = obj({"connectionId": uuid, "entityType": {"enum": ["PURCHASE_ORDER", "GOODS_RECEIPT"]}, "scenario": scenarios}, ("connectionId", "entityType", "scenario"))
    fields = {"id": uuid, "connectionId": uuid, "entityType": {"enum": ["PURCHASE_ORDER", "GOODS_RECEIPT"]}, "scenario": scenarios, "status": {"enum": ["PENDING", "RUNNING", "SUCCEEDED", "FAILED", "QUARANTINED"]}, **{name: {"type": "integer", "minimum": 0} for name in ("processed", "applied", "skipped", "quarantined", "attempts")}, "errorCode": {"type": ["string", "null"]}, "nextCursor": {"type": ["string", "null"]}, "snapshotId": string, "version": version, "createdAt": timestamp, "updatedAt": timestamp}
    schemas["SyncJob"] = obj(fields, fields.keys())
    fields = {"id": uuid, "externalKey": string, "sourceRevision": string, "errorCode": string, "createdAt": timestamp}
    schemas["SyncIssue"] = obj(fields, fields.keys())
    # Phase 04: supplier collaboration. Quantities stay decimal strings.
    order_schedule["properties"].update({key: ref("Quantity") for key in ("effectiveQuantity", "pendingQuantity", "unconfirmedQuantity")})
    order_schedule["required"].extend(("effectiveQuantity", "pendingQuantity", "unconfirmedQuantity"))
    schemas["GrantRequest"]["properties"]["permission"]["enum"].extend(("confirmation.submit", "delivery.approve"))
    scenarios["enum"].append("CONFIRMATION")
    schemas["ConfirmationRequest"]["properties"]["reason"] = {"type": "string", "maxLength": 1000}
    line = {"id": uuid, "orderId": uuid, "scheduleId": uuid, "externalOrder": string, "externalItem": code, "externalSchedule": code, "plantCode": code, "sourceRevision": string, "status": string, "version": version}
    fields = {**line, "quantity": ref("PositiveQuantity"), "promisedDate": date, "requesterId": uuid}
    fields["status"] = {"enum": ["SUBMITTED", "EFFECTIVE", "REJECTED", "SUPERSEDED"]}
    schemas["Confirmation"] = obj(fields, fields.keys())
    fields = {**line, "confirmationId": uuid, "kind": {"enum": ["INITIAL", "RESCHEDULE"]}, "originalDate": date, "proposedDate": date, "reason": {"type": "string", "maxLength": 1000}, "requesterId": uuid}
    fields["status"] = {"enum": ["SUBMITTED", "APPROVED", "REJECTED", "WITHDRAWN", "SUPERSEDED"]}
    schemas["DeliveryChange"] = obj(fields, fields.keys())
    schemas["ChangeRequest"]["properties"]["reason"] = {"type": "string", "minLength": 1, "maxLength": 1000}
    schemas["DecisionRequest"]["properties"]["reason"] = {"type": "string", "minLength": 1, "maxLength": 1000}
    fields = {**line, "confirmationId": uuid, "quantity": ref("PositiveQuantity"), "dueDate": date}
    fields["status"] = {"enum": ["PLANNED", "SUPERSEDED"]}
    schemas["DeliveryPlan"] = obj(fields, fields.keys())
    fields = {"id": uuid, "actorId": uuid, "decision": {"enum": ["APPROVED", "REJECTED"]}, "reason": {"type": "string", "maxLength": 1000}, "createdAt": timestamp}
    schemas["ApprovalEvent"] = obj(fields, fields.keys())
    schemas["ApprovalEvents"] = {"type": "array", "items": ref("ApprovalEvent")}
    paths = {}
    operation_ids = set()

    def add(path, method, name, result=None, request=None, *, listing=False, anonymous=False, match=False, business=True, status="200", summary=""):
        assert name not in operation_ids
        operation_ids.add(name)
        parameters = []
        for segment in path.split("/"):
            if segment.startswith("{"):
                parameters.append({"name": segment[1:-1], "in": "path", "required": True, "schema": uuid})
        if listing:
            parameters += [{"name": "page", "in": "query", "schema": {"type": "integer", "minimum": 0, "default": 0}},
                           {"name": "size", "in": "query", "schema": {"type": "integer", "minimum": 1, "maximum": 100, "default": 20}}]
            for field in ("companyId", "plantId", "supplierId") if result in ("Order", "Asn", "GoodsReceipt", "DeliveryPlan") else ():
                parameters.append({"name": field, "in": "query", "schema": uuid})
            page_name = result + "Page"
            schemas[page_name] = obj({"items": {"type": "array", "items": ref(result)}, "total": {"type": "integer", "minimum": 0},
                                      "page": {"type": "integer", "minimum": 0}, "size": {"type": "integer", "minimum": 1, "maximum": 100}}, ("items", "total", "page", "size"))
            result = page_name
        if method != "get":
            parameters.append({"name": "X-CSRF-Token", "in": "header", "required": True, "schema": string})
            if business:
                parameters.append({"name": "Idempotency-Key", "in": "header", "required": True, "schema": {"type": "string", "minLength": 8, "maxLength": 128}})
        if match:
            parameters.append({"name": "If-Match", "in": "header", "required": True, "schema": string})
        response = {"description": "Success; authorized scope only"}
        if result:
            response["content"] = {"application/json": {"schema": ref(result)}}
            response["headers"] = {"ETag": {"description": "Local aggregate lock version where applicable", "schema": string}}
        responses = {status: response}
        for error in ("400", "401", "403", "404", "409", "412", "422", "429", "503"):
            responses[error] = {"description": "Problem; no sensitive content", "content": {"application/problem+json": {"schema": ref("Problem")}}}
        implemented = {"getCsrf", "login", "logout", "currentUser", "getBootstrapStatus", "bootstrap", "updateProfile", "listUser", "createUser", "getUser", "updateUser", "assignRoles", "listAuditEvents"}
        operation = {"operationId": name, "summary": summary or name, "tags": [path.split("/")[1]],
                     "x-implementation-phase": "01" if name in implemented else "02" if path.split('/')[1] in ("companies", "plants", "purchasing-organizations", "suppliers", "users") or name in ("currentAccess", "getSupplierBinding") else "04" if path.split('/')[1] in ("confirmations", "delivery-changes", "delivery-plans") or name in ("submitConfirmation", "requestDeliveryChange") else "03" if path.split('/')[1] in ("purchase-orders", "erp-connections", "sync-jobs") else "planned",
                     "parameters": parameters, "responses": responses, "security": [] if anonymous else [{"Session": []}]}
        if request:
            operation["requestBody"] = {"required": True, "content": {"application/json": {"schema": ref(request)}}}
        paths.setdefault(path, {})[method] = operation

    add("/auth/csrf", "get", "getCsrf", "CsrfToken", anonymous=True)
    add("/auth/login", "post", "login", "User", "LoginRequest", anonymous=True, business=False)
    add("/auth/logout", "post", "logout", business=False, status="204")
    add("/auth/me", "get", "currentUser", "User")
    add("/auth/access", "get", "currentAccess", "Access")
    add("/erp-connections", "get", "listConnections", "Connection", listing=True)
    add("/erp-connections", "post", "createConnection", "Connection", "ConnectionCreate", status="201")
    add("/erp-connections/{id}", "get", "getConnection", "Connection")
    add("/auth/bootstrap/status", "get", "getBootstrapStatus", "BootstrapStatus", anonymous=True)
    add("/auth/bootstrap", "post", "bootstrap", "User", "BootstrapRequest", anonymous=True, business=False, status="201")
    add("/auth/profile", "patch", "updateProfile", "User", "ProfileRequest", match=True)
    for resource, model in (("companies", "Company"), ("plants", "Plant"), ("purchasing-organizations", "PurchasingOrg"), ("suppliers", "Supplier"), ("users", "User")):
        add(f"/{resource}", "get", "list" + model, model, listing=True)
        add(f"/{resource}", "post", "create" + model, model, model + "Create", status="201")
        add(f"/{resource}/{{id}}", "get", "get" + model, model)
        update = model + "Update" if model in ("Supplier", "User") else model + "Create"
        add(f"/{resource}/{{id}}", "patch", "update" + model, model, update, match=True)
    add("/suppliers/{id}/companies", "post", "linkSupplierCompany", "Supplier", "SupplierCompanyRequest", match=True)
    add("/users/{id}/grants", "get", "listGrants", "GrantList")
    add("/users/{id}/grants", "post", "addGrant", "User", "GrantRequest", match=True, status="201")
    add("/users/{id}/grants/{grantId}", "delete", "revokeGrant", "User", match=True)
    add("/users/{id}/roles", "put", "assignRoles", "User", "RolesRequest", match=True)
    add("/users/{id}/supplier-binding", "put", "bindSupplier", "User", "SupplierBindingRequest", match=True)
    add("/users/{id}/supplier-binding", "get", "getSupplierBinding", "SupplierBindingRequest")
    for resource, model in (("purchase-orders", "Order"), ("confirmations", "Confirmation"), ("delivery-changes", "DeliveryChange"), ("delivery-plans", "DeliveryPlan"), ("asns", "Asn"), ("goods-receipts", "GoodsReceipt"), ("sync-jobs", "SyncJob")):
        add(f"/{resource}", "get", "list" + model, model, listing=True)
        add(f"/{resource}/{{id}}", "get", "get" + model, model)
    add("/purchase-orders/{id}/revisions", "get", "listOrderRevisions", "OrderRevision", listing=True)
    add("/purchase-orders/{id}/confirmations", "post", "submitConfirmation", "Confirmation", "ConfirmationRequest", match=True, status="201")
    add("/purchase-orders/{id}/delivery-changes", "post", "requestDeliveryChange", "DeliveryChange", "ChangeRequest", match=True, status="201")
    add("/delivery-changes/{id}/decision", "post", "decideDeliveryChange", "DeliveryChange", "DecisionRequest", match=True)
    add("/delivery-changes/{id}/withdraw", "post", "withdrawDeliveryChange", "DeliveryChange", match=True)
    add("/delivery-changes/{id}/approval-events", "get", "listApprovalEvents", "ApprovalEvents")
    add("/asns", "post", "createAsnDraft", "Asn", "AsnRequest", status="201")
    add("/asns/{id}", "patch", "updateAsnDraft", "Asn", "AsnRequest", match=True)
    for action in ("submit", "cancel"):
        add(f"/asns/{{id}}/{action}", "post", action + "Asn", "Asn", match=True)
    add("/sync-jobs", "post", "startSync", "SyncJob", "SyncRequest", status="202")
    add("/sync-jobs/{id}/retry", "post", "retrySync", "SyncJob", match=True, status="202")
    add("/sync-jobs/{id}/issues", "get", "listSyncIssues", "SyncIssue", listing=True)
    add("/notifications", "get", "listNotifications", "Notification", listing=True)
    add("/notifications/{id}/read", "post", "markNotificationRead", "Notification", match=True)
    add("/audit-events", "get", "listAuditEvents", "AuditEvent", listing=True)
    add("/dashboard", "get", "getDashboard", "Dashboard")
    for resource, filters in (("companies", ("plantId", "purchasingOrgId")), ("plants", ("companyId", "purchasingOrgId")), ("purchasing-organizations", ("companyId", "plantId")), ("suppliers", ("companyId",))):
        parameters = paths['/' + resource]['get']['parameters']
        parameters.append({"name": "query", "in": "query", "schema": {"type": "string", "maxLength": 160}})
        parameters.extend({"name": field, "in": "query", "schema": uuid} for field in filters)
    paths['/purchase-orders']['get']['parameters'].extend([{"name": "query", "in": "query", "schema": {"type": "string", "maxLength": 160}}, {"name": "purchasingOrgId", "in": "query", "schema": uuid}])
    for resource in ("confirmations", "delivery-changes", "delivery-plans"):
        parameters = paths['/' + resource]['get']['parameters']
        parameters[:] = [p for p in parameters if p['name'] in ('page', 'size')]
        parameters.extend({"name": field, "in": "query", "schema": uuid} for field in ("orderId", "companyId", "plantId"))
        parameters.append({"name": "status", "in": "query", "schema": {"type": "string", "maxLength": 20}})
    # Phase 05 contracts replace the original planning placeholders.
    schemas["AsnLineRequest"] = obj({"planId": uuid, "sourceRevision": string, "orderVersion": version, "quantity": ref("PositiveQuantity")}, ("planId", "sourceRevision", "orderVersion", "quantity"))
    schemas["AsnRequest"] = obj({"reference": {"type":"string","pattern":"^[A-Za-z0-9_-]+$","maxLength":80},"lines":{"type":"array","minItems":1,"maxItems":50,"items":ref("AsnLineRequest")}}, ("reference","lines"))
    schemas["AsnLine"] = obj({**schemas["AsnLineRequest"]["properties"],"id":uuid,"lineNumber":{"type":"integer"},"orderId":uuid,"scheduleId":uuid})
    schemas["Asn"] = obj({"id":uuid,"reference":string,"status":{"enum":["DRAFT","SUBMITTED","CANCELLED","SUPERSEDED"]},"version":version,"lines":{"type":"array","items":ref("AsnLine")}},("id","reference","status","version","lines"))
    schemas["GoodsReceipt"] = obj({"id":uuid,"external_key":string,"order_id":uuid,"external_item":string,"external_schedule":string,"event_type":{"enum":["POSTED","REVERSAL"]},"original_id":{"type":["string","null"],"format":"uuid"},"quantity":ref("PositiveQuantity"),"posting_date":date})
    schemas["ShippingBalance"] = obj({"scheduleId":uuid,**{k:ref("Quantity") for k in ("submittedQuantity","receivedQuantity","inTransitQuantity","availableQuantity")}})
    schemas["SyncRequest"]["properties"]["entityType"]={"enum":["PURCHASE_ORDER","GOODS_RECEIPT"]}
    schemas["SyncJob"]["properties"]["entityType"]={"enum":["PURCHASE_ORDER","GOODS_RECEIPT"]}
    for model in ("SyncRequest","SyncJob"):
        schemas[model]["properties"]["scenario"]["enum"] = list(dict.fromkeys(schemas[model]["properties"]["scenario"]["enum"]+["GR_INITIAL","GR_FAIL_ONCE","GR_PARTIAL_REVERSE","GR_FULL_REVERSE","GR_CONFLICT","GR_OVER","GR_UNMATCHED"]))
    paths["/asns/{id}"]["put"]=paths["/asns/{id}"].pop("patch")
    schemas["GoodsReceipt"]["properties"]["receipt_status"]={"enum":["POSTED","PARTIALLY_REVERSED","REVERSED","REVERSAL"]}
    schemas["ReceiptDetail"] = obj({**{k:v for k,v in schemas["GoodsReceipt"]["properties"].items() if k in ("id","external_key","event_type","original_id","quantity","posting_date")},"allocations":{"type":"array","items":obj({"reference":string,"line_number":{"type":"integer"},"quantity":ref("PositiveQuantity")})}})
    paths.pop("/goods-receipts/{id}",None)
    add("/goods-receipts/{id}","get","getReceiptDetail","ReceiptDetail")
    paths["/goods-receipts/{id}"]["get"]["x-implementation-phase"]="05"
    for path in ("/asns","/asns/{id}","/asns/{id}/submit","/asns/{id}/cancel","/goods-receipts"):
        for op in paths[path].values():
            op["x-implementation-phase"]="05"
            op["parameters"]=[p for p in op.get("parameters",[]) if p["in"]!="query" or p["name"] in ("page","size")]
    add("/purchase-orders/{id}/shipping-balances","get","shippingBalances","ShippingBalance")
    paths["/purchase-orders/{id}/shipping-balances"]["get"]["x-implementation-phase"]="05"
    paths["/purchase-orders/{id}/shipping-balances"]["get"]["responses"]["200"]["content"]["application/json"]["schema"]={"type":"array","items":ref("ShippingBalance")}
    # Phase 06: per-connection read-only adapters and metadata jobs.
    for model in ("SyncRequest", "SyncJob"):
        schemas[model]["properties"]["entityType"]={"enum":["PURCHASE_ORDER","GOODS_RECEIPT","METADATA"]}
        schemas[model]["properties"]["scenario"]["enum"] = list(dict.fromkeys(schemas[model]["properties"]["scenario"]["enum"] + ["SAP_READ","SAP_RECEIPTS","SAP_METADATA"]))
    schemas["ConnectionCreate"]["properties"].update({"adapterType":{"enum":["mock","sap-odata"]}, **{k:{"type":"string","maxLength":80} for k in ("sourceSystem","sourceClient","companyExternalKey","purchasingOrgExternalKey","supplierExternalKey")}, "plantMappings":{"type":"object","maxProperties":20,"additionalProperties":uuid}})
    schemas["Connection"]["properties"]["adapterType"]={"enum":["mock","sap-odata"]}
    schemas["AdapterCapability"] = obj({"adapterType":string,"configured":{"type":"boolean"},"writeEnabled":{"const":False}})
    add("/erp-adapters","get","listErpAdapters","AdapterCapability")
    paths["/erp-adapters"]["get"]["responses"]["200"]["content"]["application/json"]["schema"]={"type":"array","items":ref("AdapterCapability")}
    paths["/erp-adapters"]["get"]["x-implementation-phase"]="06"
    # Phase 07 workspace and in-app notification contracts.
    schemas["Notification"] = obj({"id":uuid,"eventType":string,"externalOrder":{"type":["string","null"]},"orderId":{"type":["string","null"],"format":"uuid"},"jobId":{"type":["string","null"],"format":"uuid"},"createdAt":timestamp,"readAt":{"type":["string","null"],"format":"date-time"},"version":version})
    schemas["NotificationDelivery"] = obj({"id":uuid,"eventType":string,"orderId":{"type":["string","null"],"format":"uuid"},"jobId":{"type":["string","null"],"format":"uuid"},"status":{"enum":["PENDING","DELIVERED","SUPERSEDED","FAILED"]},"attempts":{"type":"integer","minimum":0},"errorCode":{"type":["string","null"]},"version":version})
    schemas["WorkspaceTask"] = obj({"id":uuid,"orderId":uuid,"externalOrder":string,"kind":{"enum":["CONFIRM","APPROVE","OVERDUE"]},"dueDate":date})
    schemas["Dashboard"] = obj({**{key:{"type":"integer","minimum":0} for key in ("orders","heldOrders","unconfirmedSchedules","pendingApprovals","overduePlans","failedSyncJobs","unreadNotifications")},"tasks":{"type":"array","items":ref("WorkspaceTask")}})
    schemas["Statistic"] = obj({"companyId":uuid,"companyCode":string,"plantId":uuid,"plantCode":string,"unit":string,**{key:ref("Quantity") for key in ("orderedQuantity","effectiveQuantity","pendingQuantity","submittedQuantity","netReceiptQuantity","inTransitQuantity")}})
    schemas["UnreadCount"] = obj({"count":{"type":"integer","minimum":0}},("count",))
    add("/notifications/{id}","get","getNotification","Notification")
    add("/notifications/unread-count","get","unreadNotifications","UnreadCount")
    add("/notification-deliveries","get","listNotificationDeliveries","NotificationDelivery",listing=True)
    add("/notification-deliveries/{id}/retry","post","retryNotificationDelivery","NotificationDelivery",match=True,status="202")
    add("/statistics","get","organizationStatistics","Statistic")
    paths["/statistics"]["get"]["responses"]["200"]["content"]["application/json"]["schema"]={"type":"array","items":ref("Statistic")}
    paths["/statistics"]["get"]["parameters"]=[{"name":key,"in":"query","schema":uuid} for key in ("companyId","plantId","purchasingOrgId","supplierId")]
    paths["/notifications"]["get"]["parameters"]=[p for p in paths["/notifications"]["get"]["parameters"] if p["name"] in ("page","size")]+[{"name":"unread","in":"query","schema":{"type":"boolean","default":False}}]
    paths["/notification-deliveries"]["get"]["parameters"]=[p for p in paths["/notification-deliveries"]["get"]["parameters"] if p["name"] in ("page","size")]
    paths["/dashboard"]["get"]["parameters"]=[]
    for path in ("/dashboard","/statistics","/notifications","/notifications/{id}","/notifications/unread-count","/notifications/{id}/read","/notification-deliveries","/notification-deliveries/{id}/retry"):
        for operation in paths[path].values():operation["x-implementation-phase"]="07"
    return {"openapi": "3.1.0", "info": {"title": "OpenSRM API", "version": "0.8.0", "description": "Phase 01-07 implemented: collaboration, read-only integration, workspace, in-app notifications and scoped statistics. License not selected."},
            "servers": [{"url": "/api/v1"}], "security": [{"Session": []}], "paths": paths,
            "components": {"securitySchemes": {"Session": {"type": "apiKey", "in": "cookie", "name": "OPEN_SRM_SESSION"}}, "schemas": schemas}}


def serialized():
    return json.dumps(build(), ensure_ascii=False, indent=2) + "\n"


if __name__ == "__main__":
    path = ROOT / "docs/api/openapi.json"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(serialized(), encoding="utf-8")
    print("Generated docs/api/openapi.json")
