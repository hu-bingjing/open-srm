CREATE TABLE company (
    id uuid PRIMARY KEY, deployment_id uuid NOT NULL REFERENCES app_guard(deployment_id),
    code varchar(40) NOT NULL, name varchar(160) NOT NULL, timezone varchar(80) NOT NULL,
    active boolean NOT NULL DEFAULT true, lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP, UNIQUE(deployment_id,id)
);
CREATE UNIQUE INDEX company_code_unique ON company(deployment_id,lower(code));
CREATE TABLE plant (
    id uuid PRIMARY KEY, deployment_id uuid NOT NULL, company_id uuid NOT NULL,
    code varchar(40) NOT NULL, name varchar(160) NOT NULL, active boolean NOT NULL DEFAULT true,
    lock_version bigint NOT NULL DEFAULT 0, created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(deployment_id,id), UNIQUE(deployment_id,company_id,id),
    FOREIGN KEY(deployment_id,company_id) REFERENCES company(deployment_id,id)
);
CREATE UNIQUE INDEX plant_code_unique ON plant(deployment_id,company_id,lower(code));
CREATE TABLE purchasing_org (
    id uuid PRIMARY KEY, deployment_id uuid NOT NULL REFERENCES app_guard(deployment_id),
    code varchar(40) NOT NULL, name varchar(160) NOT NULL, active boolean NOT NULL DEFAULT true,
    lock_version bigint NOT NULL DEFAULT 0, created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(deployment_id,id)
);
CREATE UNIQUE INDEX purchasing_org_code_unique ON purchasing_org(deployment_id,lower(code));
CREATE TABLE company_purch_org (
    deployment_id uuid NOT NULL, company_id uuid NOT NULL, purchasing_org_id uuid NOT NULL,
    active boolean NOT NULL DEFAULT true, PRIMARY KEY(deployment_id,company_id,purchasing_org_id),
    FOREIGN KEY(deployment_id,company_id) REFERENCES company(deployment_id,id),
    FOREIGN KEY(deployment_id,purchasing_org_id) REFERENCES purchasing_org(deployment_id,id)
);
CREATE TABLE supplier (
    id uuid PRIMARY KEY, deployment_id uuid NOT NULL REFERENCES app_guard(deployment_id),
    code varchar(40) NOT NULL, name varchar(160) NOT NULL,
    status varchar(16) NOT NULL CHECK(status IN ('ACTIVE','FROZEN')),
    lock_version bigint NOT NULL DEFAULT 0, created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(deployment_id,id)
);
CREATE UNIQUE INDEX supplier_code_unique ON supplier(deployment_id,lower(code));
CREATE TABLE supplier_company (
    deployment_id uuid NOT NULL, supplier_id uuid NOT NULL, company_id uuid NOT NULL,
    active boolean NOT NULL DEFAULT true, PRIMARY KEY(deployment_id,supplier_id,company_id),
    FOREIGN KEY(deployment_id,supplier_id) REFERENCES supplier(deployment_id,id),
    FOREIGN KEY(deployment_id,company_id) REFERENCES company(deployment_id,id)
);
CREATE TABLE organization_grant (
    id uuid PRIMARY KEY, deployment_id uuid NOT NULL, user_id uuid NOT NULL,
    permission varchar(80) NOT NULL CHECK(permission IN ('organization.read','supplier.read','supplier.manage','audit.read')),
    company_id uuid NOT NULL, plant_id uuid, purchasing_org_id uuid, active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY(deployment_id,user_id) REFERENCES app_user(deployment_id,id),
    FOREIGN KEY(deployment_id,company_id) REFERENCES company(deployment_id,id),
    FOREIGN KEY(deployment_id,company_id,plant_id) REFERENCES plant(deployment_id,company_id,id),
    FOREIGN KEY(deployment_id,company_id,purchasing_org_id) REFERENCES company_purch_org(deployment_id,company_id,purchasing_org_id)
);
CREATE UNIQUE INDEX organization_grant_active_unique ON organization_grant(user_id,permission,company_id,plant_id,purchasing_org_id) NULLS NOT DISTINCT WHERE active;
CREATE INDEX organization_grant_lookup ON organization_grant(user_id,company_id,permission) WHERE active;
CREATE TABLE user_supplier (
    user_id uuid PRIMARY KEY, deployment_id uuid NOT NULL, supplier_id uuid NOT NULL,
    FOREIGN KEY(deployment_id,user_id) REFERENCES app_user(deployment_id,id),
    FOREIGN KEY(deployment_id,supplier_id) REFERENCES supplier(deployment_id,id)
);
ALTER TABLE audit_event ADD COLUMN category varchar(16) NOT NULL DEFAULT 'IDENTITY' CHECK(category IN ('IDENTITY','ORGANIZATION','SUPPLIER'));
ALTER TABLE audit_event ADD COLUMN supplier_id uuid;
ALTER TABLE audit_event ADD CONSTRAINT audit_event_realm_unique UNIQUE(deployment_id,id);
ALTER TABLE audit_event ADD CONSTRAINT audit_supplier_realm_fk FOREIGN KEY(deployment_id,supplier_id) REFERENCES supplier(deployment_id,id);
CREATE TABLE audit_event_company (
    deployment_id uuid NOT NULL, event_id uuid NOT NULL, company_id uuid NOT NULL,
    PRIMARY KEY(event_id,company_id),
    FOREIGN KEY(deployment_id,event_id) REFERENCES audit_event(deployment_id,id),
    FOREIGN KEY(deployment_id,company_id) REFERENCES company(deployment_id,id)
);
CREATE INDEX audit_event_company_scope ON audit_event_company(company_id,event_id);
INSERT INTO app_role(code) VALUES ('BUYER'),('SUPPLIER');
INSERT INTO role_permission(role_code,permission) VALUES
    ('ADMIN','organization.manage'),('ADMIN','scope.manage'),('ADMIN','organization.read'),
    ('ADMIN','supplier.read'),('ADMIN','supplier.manage'),('AUDITOR','organization.read'),
    ('BUYER','organization.read'),('BUYER','supplier.read'),('BUYER','supplier.manage'),
    ('SUPPLIER','organization.read'),('SUPPLIER','supplier.read');
-- No implicit scopes, bindings, organization fixtures or business data are granted by migration.
