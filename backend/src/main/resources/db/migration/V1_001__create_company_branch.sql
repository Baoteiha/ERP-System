-- Phase 0 / organization module: company and branch master data.

CREATE TABLE company (
    id         UUID         NOT NULL,
    version    BIGINT       NOT NULL,
    name       VARCHAR(255) NOT NULL,
    code       VARCHAR(32)  NOT NULL,
    deleted    BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ  NOT NULL,
    created_by VARCHAR(255),
    updated_at TIMESTAMPTZ  NOT NULL,
    updated_by VARCHAR(255),
    CONSTRAINT pk_company PRIMARY KEY (id),
    CONSTRAINT uk_company_code UNIQUE (code)
);

CREATE TABLE branch (
    id         UUID         NOT NULL,
    version    BIGINT       NOT NULL,
    company_id UUID         NOT NULL,
    name       VARCHAR(255) NOT NULL,
    code       VARCHAR(32)  NOT NULL,
    address    VARCHAR(500),
    phone      VARCHAR(32),
    active     BOOLEAN      NOT NULL DEFAULT TRUE,
    deleted    BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ  NOT NULL,
    created_by VARCHAR(255),
    updated_at TIMESTAMPTZ  NOT NULL,
    updated_by VARCHAR(255),
    CONSTRAINT pk_branch PRIMARY KEY (id),
    CONSTRAINT uk_branch_company_code UNIQUE (company_id, code),
    CONSTRAINT fk_branch_company FOREIGN KEY (company_id) REFERENCES company (id)
);

CREATE INDEX idx_branch_company_id ON branch (company_id);
