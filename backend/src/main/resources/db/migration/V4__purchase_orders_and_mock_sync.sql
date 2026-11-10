ALTER TABLE organization_grant DROP CONSTRAINT organization_grant_permission_check;
ALTER TABLE organization_grant ADD CONSTRAINT organization_grant_permission_check CHECK(permission IN ('organization.read','supplier.read','supplier.manage','audit.read','order.read','sync.manage'));
ALTER TABLE audit_event DROP CONSTRAINT audit_event_category_check;
ALTER TABLE audit_event ADD CONSTRAINT audit_event_category_check CHECK(category IN ('IDENTITY','ORGANIZATION','SUPPLIER','ORDER','SYNC'));
INSERT INTO role_permission(role_code,permission) VALUES ('ADMIN','order.read'),('BUYER','order.read'),('SUPPLIER','order.read'),('AUDITOR','order.read'),('ADMIN','sync.manage'),('ADMIN','sync.read');
CREATE TABLE erp_connection (
 id uuid PRIMARY KEY, deployment_id uuid NOT NULL REFERENCES app_guard(deployment_id), owner_id uuid NOT NULL,
 name varchar(160) NOT NULL, adapter_type varchar(32) NOT NULL CHECK(adapter_type='mock'),
 source_system varchar(40) NOT NULL DEFAULT 'MOCK', source_client varchar(40) NOT NULL DEFAULT 'DEMO',
 company_external_key varchar(80) NOT NULL DEFAULT 'COMPANY-A', purchasing_org_external_key varchar(80) NOT NULL DEFAULT 'PURCHASING-A', supplier_external_key varchar(80) NOT NULL DEFAULT 'SUPPLIER-A',
 company_id uuid NOT NULL, purchasing_org_id uuid NOT NULL, supplier_id uuid NOT NULL,
 active boolean NOT NULL DEFAULT true, lock_version bigint NOT NULL DEFAULT 0, created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(deployment_id,id), FOREIGN KEY(deployment_id,owner_id) REFERENCES app_user(deployment_id,id),
 FOREIGN KEY(deployment_id,company_id,purchasing_org_id) REFERENCES company_purch_org(deployment_id,company_id,purchasing_org_id),
 FOREIGN KEY(deployment_id,supplier_id,company_id) REFERENCES supplier_company(deployment_id,supplier_id,company_id)
);
CREATE TABLE erp_plant_mapping (
 connection_id uuid NOT NULL REFERENCES erp_connection(id), deployment_id uuid NOT NULL, company_id uuid NOT NULL,
 external_key varchar(40) NOT NULL, plant_id uuid NOT NULL, PRIMARY KEY(connection_id,external_key), UNIQUE(connection_id,plant_id),
 FOREIGN KEY(deployment_id,company_id,plant_id) REFERENCES plant(deployment_id,company_id,id)
);
CREATE TABLE material (
 id uuid PRIMARY KEY, deployment_id uuid NOT NULL, connection_id uuid NOT NULL, external_key varchar(80) NOT NULL,
 name varchar(160) NOT NULL, unit varchar(16) NOT NULL, UNIQUE(connection_id,external_key), UNIQUE(deployment_id,id),
 FOREIGN KEY(deployment_id,connection_id) REFERENCES erp_connection(deployment_id,id)
);
CREATE TABLE purchase_order (
 id uuid PRIMARY KEY, deployment_id uuid NOT NULL, connection_id uuid NOT NULL, external_id varchar(80) NOT NULL,
 company_id uuid NOT NULL, purchasing_org_id uuid NOT NULL, supplier_id uuid NOT NULL,
 current_revision_id uuid, source_revision varchar(80) NOT NULL, source_sequence bigint NOT NULL CHECK(source_sequence>0),
 lifecycle varchar(16) NOT NULL CHECK(lifecycle IN ('ACTIVE','CLOSED','CANCELLED')),
 consistency_hold boolean NOT NULL DEFAULT false, lock_version bigint NOT NULL DEFAULT 0,
 created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP, UNIQUE(connection_id,external_id), UNIQUE(deployment_id,id),
 FOREIGN KEY(deployment_id,connection_id) REFERENCES erp_connection(deployment_id,id),
 FOREIGN KEY(deployment_id,company_id,purchasing_org_id) REFERENCES company_purch_org(deployment_id,company_id,purchasing_org_id),
 FOREIGN KEY(deployment_id,supplier_id,company_id) REFERENCES supplier_company(deployment_id,supplier_id,company_id)
);
CREATE TABLE po_revision (
 id uuid PRIMARY KEY, deployment_id uuid NOT NULL, order_id uuid NOT NULL, source_revision varchar(80) NOT NULL,
 source_sequence bigint NOT NULL CHECK(source_sequence>0), content_hash varchar(64) NOT NULL,
 lifecycle varchar(16) NOT NULL CHECK(lifecycle IN ('ACTIVE','CLOSED','CANCELLED')),
 source_updated_at timestamptz NOT NULL, received_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(order_id,source_revision), UNIQUE(order_id,source_sequence), UNIQUE(order_id,id), UNIQUE(deployment_id,id),
 FOREIGN KEY(deployment_id,order_id) REFERENCES purchase_order(deployment_id,id)
);
ALTER TABLE purchase_order ADD CONSTRAINT current_order_revision_fk FOREIGN KEY(id,current_revision_id) REFERENCES po_revision(order_id,id);
CREATE TABLE po_item (
 id uuid PRIMARY KEY, deployment_id uuid NOT NULL, revision_id uuid NOT NULL, company_id uuid NOT NULL,
 external_item varchar(40) NOT NULL, plant_id uuid NOT NULL, material_id uuid NOT NULL, unit varchar(16) NOT NULL,
 UNIQUE(revision_id,external_item), UNIQUE(deployment_id,id),
 FOREIGN KEY(deployment_id,revision_id) REFERENCES po_revision(deployment_id,id),
 FOREIGN KEY(deployment_id,company_id,plant_id) REFERENCES plant(deployment_id,company_id,id),
 FOREIGN KEY(deployment_id,material_id) REFERENCES material(deployment_id,id)
);
CREATE TABLE po_schedule (
 id uuid PRIMARY KEY, deployment_id uuid NOT NULL, item_id uuid NOT NULL, external_schedule varchar(40) NOT NULL,
 ordered_quantity numeric(19,6) NOT NULL CHECK(ordered_quantity>0), requested_date date NOT NULL,
 UNIQUE(item_id,external_schedule), FOREIGN KEY(deployment_id,item_id) REFERENCES po_item(deployment_id,id)
);
CREATE INDEX order_scope_lookup ON purchase_order(company_id,supplier_id,purchasing_org_id,lifecycle,id);
CREATE INDEX item_scope_lookup ON po_item(revision_id,plant_id);
CREATE TABLE sync_job (
 id uuid PRIMARY KEY, deployment_id uuid NOT NULL, connection_id uuid NOT NULL, actor_id uuid NOT NULL,
 actor_session_version bigint NOT NULL, request_id uuid NOT NULL, scenario varchar(40) NOT NULL,
 snapshot_id varchar(100) NOT NULL, next_cursor varchar(200),
 status varchar(20) NOT NULL CHECK(status IN ('PENDING','RUNNING','SUCCEEDED','FAILED','QUARANTINED')),
 processed integer NOT NULL DEFAULT 0, applied integer NOT NULL DEFAULT 0, skipped integer NOT NULL DEFAULT 0,
 quarantined integer NOT NULL DEFAULT 0, attempts integer NOT NULL DEFAULT 0 CHECK(attempts BETWEEN 0 AND 5),
 error_code varchar(80), lock_version bigint NOT NULL DEFAULT 0,
 created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY(deployment_id,connection_id) REFERENCES erp_connection(deployment_id,id),
 FOREIGN KEY(deployment_id,actor_id) REFERENCES app_user(deployment_id,id)
);
CREATE UNIQUE INDEX one_active_sync_per_connection ON sync_job(connection_id) WHERE status IN ('PENDING','RUNNING');
CREATE TABLE sync_cursor (
 connection_id uuid PRIMARY KEY REFERENCES erp_connection(id), snapshot_id varchar(100) NOT NULL,
 next_cursor varchar(200), updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE inbox_event (
 connection_id uuid NOT NULL REFERENCES erp_connection(id), external_key varchar(80) NOT NULL, source_revision varchar(80) NOT NULL,
 content_hash varchar(64) NOT NULL, created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(connection_id,external_key,source_revision)
);
CREATE TABLE sync_issue (
 id uuid PRIMARY KEY, connection_id uuid NOT NULL REFERENCES erp_connection(id), job_id uuid NOT NULL REFERENCES sync_job(id),
 external_key varchar(80) NOT NULL, source_revision varchar(80) NOT NULL, content_hash varchar(64) NOT NULL, error_code varchar(80) NOT NULL,
 created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP, UNIQUE(job_id,external_key,source_revision,content_hash,error_code)
);
