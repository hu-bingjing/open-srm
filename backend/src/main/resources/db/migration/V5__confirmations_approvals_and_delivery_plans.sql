ALTER TABLE organization_grant DROP CONSTRAINT organization_grant_permission_check;
ALTER TABLE organization_grant ADD CONSTRAINT organization_grant_permission_check CHECK(permission IN ('organization.read','supplier.read','supplier.manage','audit.read','order.read','sync.manage','confirmation.submit','delivery.approve'));
ALTER TABLE audit_event DROP CONSTRAINT audit_event_category_check;
ALTER TABLE audit_event ADD CONSTRAINT audit_event_category_check CHECK(category IN ('IDENTITY','ORGANIZATION','SUPPLIER','ORDER','SYNC','COLLABORATION'));
INSERT INTO role_permission(role_code,permission) VALUES ('SUPPLIER','confirmation.submit'),('BUYER','delivery.approve');
CREATE TABLE confirmation (
 id uuid PRIMARY KEY, deployment_id uuid NOT NULL, order_id uuid NOT NULL, revision_id uuid NOT NULL,
 schedule_id uuid NOT NULL, source_revision varchar(80) NOT NULL, quantity numeric(19,6) NOT NULL CHECK(quantity>0),
 promised_date date NOT NULL, status varchar(20) NOT NULL CHECK(status IN ('SUBMITTED','EFFECTIVE','REJECTED','SUPERSEDED')),
 requester_id uuid NOT NULL, lock_version bigint NOT NULL DEFAULT 0, created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(deployment_id,id), FOREIGN KEY(deployment_id,order_id) REFERENCES purchase_order(deployment_id,id),
 FOREIGN KEY(deployment_id,revision_id) REFERENCES po_revision(deployment_id,id),
 FOREIGN KEY(schedule_id) REFERENCES po_schedule(id), FOREIGN KEY(deployment_id,requester_id) REFERENCES app_user(deployment_id,id)
);
CREATE INDEX confirmation_budget ON confirmation(schedule_id,status);
CREATE TABLE delivery_change (
 id uuid PRIMARY KEY, deployment_id uuid NOT NULL, order_id uuid NOT NULL, confirmation_id uuid NOT NULL,
 source_revision varchar(80) NOT NULL, kind varchar(16) NOT NULL CHECK(kind IN ('INITIAL','RESCHEDULE')),
 original_date date NOT NULL, proposed_date date NOT NULL, reason varchar(1000) NOT NULL,
 requester_id uuid NOT NULL, base_confirmation_version bigint NOT NULL,
 status varchar(20) NOT NULL CHECK(status IN ('SUBMITTED','APPROVED','REJECTED','WITHDRAWN','SUPERSEDED')),
 lock_version bigint NOT NULL DEFAULT 0, created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(deployment_id,id), FOREIGN KEY(deployment_id,order_id) REFERENCES purchase_order(deployment_id,id),
 FOREIGN KEY(deployment_id,confirmation_id) REFERENCES confirmation(deployment_id,id),
 FOREIGN KEY(deployment_id,requester_id) REFERENCES app_user(deployment_id,id)
);
CREATE UNIQUE INDEX one_pending_change_per_confirmation ON delivery_change(confirmation_id) WHERE status='SUBMITTED';
CREATE TABLE approval_event (
 id uuid PRIMARY KEY, deployment_id uuid NOT NULL, change_id uuid NOT NULL UNIQUE, actor_id uuid NOT NULL,
 decision varchar(16) NOT NULL CHECK(decision IN ('APPROVED','REJECTED')), reason varchar(1000) NOT NULL,
 created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY(deployment_id,change_id) REFERENCES delivery_change(deployment_id,id),
 FOREIGN KEY(deployment_id,actor_id) REFERENCES app_user(deployment_id,id)
);
CREATE TABLE delivery_plan (
 id uuid PRIMARY KEY, deployment_id uuid NOT NULL, order_id uuid NOT NULL, confirmation_id uuid NOT NULL,
 confirmation_version bigint NOT NULL, schedule_id uuid NOT NULL, source_revision varchar(80) NOT NULL,
 quantity numeric(19,6) NOT NULL CHECK(quantity>0), due_date date NOT NULL,
 status varchar(20) NOT NULL CHECK(status IN ('PLANNED','SUPERSEDED')),
 lock_version bigint NOT NULL DEFAULT 0, created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(deployment_id,id), UNIQUE(confirmation_id,confirmation_version),
 FOREIGN KEY(deployment_id,order_id) REFERENCES purchase_order(deployment_id,id),
 FOREIGN KEY(deployment_id,confirmation_id) REFERENCES confirmation(deployment_id,id),
 FOREIGN KEY(schedule_id) REFERENCES po_schedule(id)
);
CREATE TABLE outbox_event (
 id uuid PRIMARY KEY, deployment_id uuid NOT NULL, order_id uuid NOT NULL, aggregate_id uuid NOT NULL,
 aggregate_version bigint NOT NULL, source_revision varchar(80) NOT NULL, event_type varchar(80) NOT NULL,
 status varchar(20) NOT NULL CHECK(status IN ('PENDING','SUPERSEDED')),
 created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(order_id,aggregate_id,aggregate_version,event_type), FOREIGN KEY(deployment_id,order_id) REFERENCES purchase_order(deployment_id,id)
);
