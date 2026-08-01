-- Phase 5 / staff module: employees (company master data) and shifts (branch-scoped
-- schedule + time clock). Shifts snapshot the hourly rate at clock-in and capture
-- labor cost at clock-out so history never drifts when rates change.

CREATE TABLE employee (
    id                   UUID          NOT NULL,
    version              BIGINT        NOT NULL,
    company_id           UUID          NOT NULL,
    full_name            VARCHAR(255)  NOT NULL,
    position             VARCHAR(100),
    phone                VARCHAR(32),
    email                VARCHAR(255),
    hourly_rate          NUMERIC(19,4) NOT NULL DEFAULT 0,
    hourly_rate_currency VARCHAR(3)    NOT NULL DEFAULT 'VND',
    active               BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at           TIMESTAMPTZ   NOT NULL,
    created_by           VARCHAR(255),
    updated_at           TIMESTAMPTZ   NOT NULL,
    updated_by           VARCHAR(255),
    CONSTRAINT pk_employee PRIMARY KEY (id),
    CONSTRAINT fk_employee_company FOREIGN KEY (company_id) REFERENCES company (id)
);

CREATE INDEX idx_employee_company ON employee (company_id);

CREATE TABLE shift (
    id                   UUID          NOT NULL,
    version              BIGINT        NOT NULL,
    branch_id            UUID          NOT NULL,
    employee_id          UUID          NOT NULL,
    status               VARCHAR(16)   NOT NULL,
    scheduled_start      TIMESTAMPTZ   NOT NULL,
    scheduled_end        TIMESTAMPTZ   NOT NULL,
    clock_in_at          TIMESTAMPTZ,
    clock_out_at         TIMESTAMPTZ,
    hourly_rate          NUMERIC(19,4),
    hourly_rate_currency VARCHAR(3),
    labor_cost           NUMERIC(19,4),
    labor_cost_currency  VARCHAR(3),
    note                 VARCHAR(500),
    created_at           TIMESTAMPTZ   NOT NULL,
    created_by           VARCHAR(255),
    updated_at           TIMESTAMPTZ   NOT NULL,
    updated_by           VARCHAR(255),
    CONSTRAINT pk_shift PRIMARY KEY (id),
    CONSTRAINT fk_shift_branch FOREIGN KEY (branch_id) REFERENCES branch (id),
    CONSTRAINT fk_shift_employee FOREIGN KEY (employee_id) REFERENCES employee (id)
);

CREATE INDEX idx_shift_branch_start ON shift (branch_id, scheduled_start);
CREATE INDEX idx_shift_branch_clock_out ON shift (branch_id, clock_out_at);
