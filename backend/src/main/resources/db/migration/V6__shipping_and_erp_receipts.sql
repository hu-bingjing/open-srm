ALTER TABLE organization_grant DROP CONSTRAINT organization_grant_permission_check;
ALTER TABLE organization_grant ADD CONSTRAINT organization_grant_permission_check CHECK(permission IN ('organization.read','supplier.read','supplier.manage','audit.read','order.read','sync.manage','confirmation.submit','delivery.approve','asn.manage'));
INSERT INTO role_permission(role_code,permission) VALUES ('SUPPLIER','asn.manage');
ALTER TABLE sync_job ADD COLUMN entity_type varchar(30) NOT NULL DEFAULT 'PURCHASE_ORDER' CHECK(entity_type IN ('PURCHASE_ORDER','GOODS_RECEIPT'));
CREATE TABLE asn (
 id uuid PRIMARY KEY, deployment_id uuid NOT NULL, company_id uuid NOT NULL, supplier_id uuid NOT NULL,
 reference varchar(80) NOT NULL, status varchar(20) NOT NULL CHECK(status IN ('DRAFT','SUBMITTED','CANCELLED','SUPERSEDED')),
 requester_id uuid NOT NULL, lock_version bigint NOT NULL DEFAULT 0, created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(deployment_id,id), UNIQUE(company_id,supplier_id,reference),
 FOREIGN KEY(deployment_id,company_id) REFERENCES company(deployment_id,id),
 FOREIGN KEY(deployment_id,supplier_id) REFERENCES supplier(deployment_id,id),
 FOREIGN KEY(deployment_id,requester_id) REFERENCES app_user(deployment_id,id)
);
CREATE TABLE asn_line (
 id uuid PRIMARY KEY, deployment_id uuid NOT NULL, asn_id uuid NOT NULL, line_number integer NOT NULL CHECK(line_number>0),
 plan_id uuid NOT NULL, order_id uuid NOT NULL, schedule_id uuid NOT NULL REFERENCES po_schedule(id),
 source_revision varchar(80) NOT NULL, order_version bigint NOT NULL, quantity numeric(19,6) NOT NULL CHECK(quantity>0),
 UNIQUE(asn_id,plan_id), UNIQUE(asn_id,line_number), UNIQUE(deployment_id,id),
 FOREIGN KEY(deployment_id,asn_id) REFERENCES asn(deployment_id,id),
 FOREIGN KEY(deployment_id,plan_id) REFERENCES delivery_plan(deployment_id,id),
 FOREIGN KEY(deployment_id,order_id) REFERENCES purchase_order(deployment_id,id)
);
CREATE INDEX asn_line_budget ON asn_line(order_id,schedule_id,plan_id);
CREATE TABLE receipt_inbox (
 id uuid PRIMARY KEY, connection_id uuid NOT NULL REFERENCES erp_connection(id), external_key varchar(160) NOT NULL,
 content_hash varchar(64) NOT NULL, payload text NOT NULL, created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(connection_id,external_key,content_hash)
);
CREATE TABLE receipt_event (
 id uuid PRIMARY KEY REFERENCES receipt_inbox(id), deployment_id uuid NOT NULL, connection_id uuid NOT NULL REFERENCES erp_connection(id),
 external_key varchar(160) NOT NULL, order_id uuid NOT NULL, schedule_id uuid NOT NULL REFERENCES po_schedule(id),
 event_type varchar(16) NOT NULL CHECK(event_type IN ('POSTED','REVERSAL')), original_id uuid REFERENCES receipt_event(id),
 quantity numeric(19,6) NOT NULL CHECK(quantity>0), posting_date date NOT NULL,
 UNIQUE(connection_id,external_key), UNIQUE(deployment_id,id),
 FOREIGN KEY(deployment_id,order_id) REFERENCES purchase_order(deployment_id,id),
 CHECK((event_type='POSTED' AND original_id IS NULL) OR (event_type='REVERSAL' AND original_id IS NOT NULL))
);
CREATE INDEX receipt_budget ON receipt_event(order_id,schedule_id);
CREATE TABLE receipt_allocation (
 event_id uuid NOT NULL REFERENCES receipt_event(id), asn_line_id uuid NOT NULL REFERENCES asn_line(id),
 quantity numeric(19,6) NOT NULL CHECK(quantity>0), PRIMARY KEY(event_id,asn_line_id)
);

ALTER TABLE delivery_plan DROP CONSTRAINT delivery_plan_status_check;
ALTER TABLE delivery_plan ADD CONSTRAINT delivery_plan_status_check CHECK(status IN ('PLANNED','PARTIALLY_SHIPPED','SHIPPED','SUPERSEDED'));
