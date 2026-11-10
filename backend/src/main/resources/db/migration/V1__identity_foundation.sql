CREATE TABLE app_guard (
    id integer PRIMARY KEY CHECK (id = 1),
    deployment_id uuid NOT NULL UNIQUE,
    initialized boolean NOT NULL DEFAULT false
);
INSERT INTO app_guard(id, deployment_id) VALUES (1, gen_random_uuid());

CREATE TABLE app_user (
    id uuid PRIMARY KEY,
    deployment_id uuid NOT NULL REFERENCES app_guard(deployment_id),
    login varchar(80) NOT NULL UNIQUE,
    display_name varchar(120) NOT NULL,
    password_hash varchar(100) NOT NULL,
    status varchar(16) NOT NULL CHECK (status IN ('ACTIVE', 'DISABLED')),
    lock_version bigint NOT NULL DEFAULT 0,
    session_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(deployment_id, id)
);
CREATE TABLE app_role (code varchar(32) PRIMARY KEY);
CREATE TABLE role_permission (
    role_code varchar(32) NOT NULL REFERENCES app_role(code),
    permission varchar(80) NOT NULL,
    PRIMARY KEY(role_code, permission)
);
CREATE TABLE user_role (
    user_id uuid NOT NULL REFERENCES app_user(id),
    role_code varchar(32) NOT NULL REFERENCES app_role(code),
    PRIMARY KEY(user_id, role_code)
);
INSERT INTO app_role(code) VALUES ('ADMIN'), ('AUDITOR'), ('USER');
INSERT INTO role_permission(role_code, permission) VALUES
    ('ADMIN', 'user.manage'), ('AUDITOR', 'audit.read'), ('USER', 'account.read');

CREATE TABLE audit_event (
    id uuid PRIMARY KEY,
    deployment_id uuid NOT NULL REFERENCES app_guard(deployment_id),
    actor_id uuid NOT NULL REFERENCES app_user(id),
    operation varchar(80) NOT NULL,
    object_id uuid NOT NULL,
    request_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX audit_event_time_idx ON audit_event(created_at DESC, id);
CREATE TABLE idempotency_record (
    actor_id uuid NOT NULL REFERENCES app_user(id),
    operation varchar(120) NOT NULL,
    request_key varchar(128) NOT NULL,
    request_hash varchar(64) NOT NULL,
    response_body text NOT NULL,
    response_etag varchar(40) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(actor_id, operation, request_key)
);
CREATE TABLE login_attempt (
    login_hash varchar(64) PRIMARY KEY,
    failures integer NOT NULL,
    blocked_until timestamptz NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
-- No company or supplier fixtures and no default administrator password.
