-- Phase 1 / identity module: users, roles, permissions, per-branch access, refresh tokens.

CREATE TABLE permission (
    id          UUID        NOT NULL,
    version     BIGINT      NOT NULL,
    name        VARCHAR(64) NOT NULL,
    description VARCHAR(255),
    CONSTRAINT pk_permission PRIMARY KEY (id),
    CONSTRAINT uk_permission_name UNIQUE (name)
);

CREATE TABLE role (
    id          UUID        NOT NULL,
    version     BIGINT      NOT NULL,
    company_id  UUID        NOT NULL,
    name        VARCHAR(64) NOT NULL,
    description VARCHAR(255),
    created_at  TIMESTAMPTZ NOT NULL,
    created_by  VARCHAR(255),
    updated_at  TIMESTAMPTZ NOT NULL,
    updated_by  VARCHAR(255),
    CONSTRAINT pk_role PRIMARY KEY (id),
    CONSTRAINT uk_role_company_name UNIQUE (company_id, name),
    CONSTRAINT fk_role_company FOREIGN KEY (company_id) REFERENCES company (id)
);

CREATE TABLE role_permission (
    role_id       UUID NOT NULL,
    permission_id UUID NOT NULL,
    CONSTRAINT pk_role_permission PRIMARY KEY (role_id, permission_id),
    CONSTRAINT fk_rp_role FOREIGN KEY (role_id) REFERENCES role (id),
    CONSTRAINT fk_rp_permission FOREIGN KEY (permission_id) REFERENCES permission (id)
);

CREATE TABLE app_user (
    id            UUID         NOT NULL,
    version       BIGINT       NOT NULL,
    company_id    UUID         NOT NULL,
    email         VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    full_name     VARCHAR(255),
    status        VARCHAR(16)  NOT NULL,
    deleted       BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMPTZ  NOT NULL,
    created_by    VARCHAR(255),
    updated_at    TIMESTAMPTZ  NOT NULL,
    updated_by    VARCHAR(255),
    CONSTRAINT pk_app_user PRIMARY KEY (id),
    CONSTRAINT uk_user_email UNIQUE (email),
    CONSTRAINT fk_user_company FOREIGN KEY (company_id) REFERENCES company (id)
);

CREATE TABLE user_branch_access (
    id         UUID        NOT NULL,
    version    BIGINT      NOT NULL,
    user_id    UUID        NOT NULL,
    branch_id  UUID        NOT NULL,
    role_id    UUID        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    created_by VARCHAR(255),
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by VARCHAR(255),
    CONSTRAINT pk_user_branch_access PRIMARY KEY (id),
    CONSTRAINT uk_uba_user_branch UNIQUE (user_id, branch_id),
    CONSTRAINT fk_uba_user FOREIGN KEY (user_id) REFERENCES app_user (id),
    CONSTRAINT fk_uba_branch FOREIGN KEY (branch_id) REFERENCES branch (id),
    CONSTRAINT fk_uba_role FOREIGN KEY (role_id) REFERENCES role (id)
);

CREATE TABLE refresh_token (
    id         UUID        NOT NULL,
    version    BIGINT      NOT NULL,
    user_id    UUID        NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked    BOOLEAN     NOT NULL DEFAULT FALSE,
    CONSTRAINT pk_refresh_token PRIMARY KEY (id),
    CONSTRAINT uk_refresh_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_refresh_user FOREIGN KEY (user_id) REFERENCES app_user (id)
);

CREATE INDEX idx_role_company_id ON role (company_id);
CREATE INDEX idx_user_company_id ON app_user (company_id);
CREATE INDEX idx_uba_user_id ON user_branch_access (user_id);
CREATE INDEX idx_refresh_user_id ON refresh_token (user_id);
