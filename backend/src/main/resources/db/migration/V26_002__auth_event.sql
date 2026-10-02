-- The authentication audit trail (AUTH-G14): sign-ins, failed sign-ins, refreshes, a replayed refresh
-- token, sign-outs, and every change to who may do what: a grant, a revoke, a password change or
-- reset, a status change.
--
-- A row belongs to its user's company and follows the standard company policy, so a company reads only
-- its own trail (GET /auth-events). Sign-ins, refreshes and sign-outs run before any company is in
-- force, so they are written as the schema owner (SystemTransactions); a failed sign-in for an email no
-- account has belongs to no company and no user, and only the owner sees it. The app may add rows and
-- read them, never change or delete one. occurred_at is the clock at the write, not the transaction's
-- start, so the events of one transaction keep their order.
CREATE TABLE auth_event (
    id          UUID         NOT NULL,
    company_id  UUID,
    user_id     UUID,
    type        VARCHAR(40)  NOT NULL,
    ip          VARCHAR(64),
    occurred_at TIMESTAMPTZ  NOT NULL DEFAULT clock_timestamp(),
    detail      JSONB        NOT NULL DEFAULT '{}',
    CONSTRAINT pk_auth_event PRIMARY KEY (id),
    CONSTRAINT fk_auth_event_company FOREIGN KEY (company_id) REFERENCES company (id),
    CONSTRAINT fk_auth_event_user FOREIGN KEY (user_id) REFERENCES app_user (id)
);

-- What GET /auth-events reads: a company's trail newest first, and one user's.
CREATE INDEX idx_auth_event_company_time ON auth_event (company_id, occurred_at DESC);
CREATE INDEX idx_auth_event_user_time ON auth_event (user_id, occurred_at DESC);

ALTER TABLE auth_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE auth_event FORCE ROW LEVEL SECURITY;
CREATE POLICY auth_event_tenant_isolation ON auth_event
    USING (company_id = NULLIF(current_setting('app.company_id', true), '')::uuid);

REVOKE UPDATE, DELETE ON auth_event FROM erp_app;
