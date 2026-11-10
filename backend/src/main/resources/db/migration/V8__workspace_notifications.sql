ALTER TABLE audit_event DROP CONSTRAINT audit_event_category_check;
ALTER TABLE audit_event ADD CONSTRAINT audit_event_category_check CHECK(category IN ('IDENTITY','ORGANIZATION','SUPPLIER','ORDER','SYNC','COLLABORATION','NOTIFICATION'));
CREATE TABLE notification_delivery (
 id uuid PRIMARY KEY, deployment_id uuid NOT NULL REFERENCES app_guard(deployment_id),
 origin_kind varchar(20) NOT NULL CHECK(origin_kind IN ('COLLABORATION','SYNC','ORDER')),
 origin_id uuid NOT NULL, origin_version bigint NOT NULL, event_type varchar(80) NOT NULL,
 order_id uuid REFERENCES purchase_order(id), plant_id uuid REFERENCES plant(id), job_id uuid REFERENCES sync_job(id),
 source_revision varchar(80), status varchar(20) NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','DELIVERED','SUPERSEDED','FAILED')),
 attempts integer NOT NULL DEFAULT 0, next_attempt_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 error_code varchar(80), lock_version bigint NOT NULL DEFAULT 0,
 created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP, delivered_at timestamptz,
 UNIQUE(origin_kind,origin_id,origin_version), UNIQUE(deployment_id,id),
 CHECK((origin_kind='SYNC' AND job_id IS NOT NULL AND order_id IS NULL) OR (origin_kind<>'SYNC' AND order_id IS NOT NULL AND job_id IS NULL))
);
CREATE INDEX notification_delivery_pending_idx ON notification_delivery(status,next_attempt_at,created_at);
CREATE TABLE notification (
 id uuid PRIMARY KEY, deployment_id uuid NOT NULL, delivery_id uuid NOT NULL,
 recipient_id uuid NOT NULL, channel varchar(16) NOT NULL DEFAULT 'IN_APP' CHECK(channel='IN_APP'),
 read_at timestamptz, lock_version bigint NOT NULL DEFAULT 0, created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY(deployment_id,delivery_id) REFERENCES notification_delivery(deployment_id,id),
 FOREIGN KEY(deployment_id,recipient_id) REFERENCES app_user(deployment_id,id),
 UNIQUE(delivery_id,recipient_id,channel)
);
CREATE INDEX notification_recipient_idx ON notification(recipient_id,created_at DESC,id);
