ALTER TABLE erp_connection DROP CONSTRAINT erp_connection_adapter_type_check;
ALTER TABLE erp_connection ADD CONSTRAINT erp_connection_adapter_type_check CHECK(adapter_type IN ('mock','sap-odata'));
ALTER TABLE sync_job DROP CONSTRAINT sync_job_entity_type_check;
ALTER TABLE sync_job ADD CONSTRAINT sync_job_entity_type_check CHECK(entity_type IN ('PURCHASE_ORDER','GOODS_RECEIPT','METADATA'));
ALTER TABLE sync_job ALTER COLUMN next_cursor TYPE varchar(4000);
ALTER TABLE sync_cursor ALTER COLUMN next_cursor TYPE varchar(4000);
-- No private endpoints, secrets, organizations or implicit access grants in migrations.

ALTER TABLE sync_job ADD COLUMN next_attempt_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP;
