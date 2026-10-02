-- Domain events (REP-9, ADR-0009). A module records a fact in the transaction that makes it true
-- (DomainEventPublisher), so the event exists exactly when that transaction commits; the
-- dispatcher delivers it to the listeners afterwards. Delivery only ever writes bookkeeping back:
-- processed_at once every listener has run, or attempts, last_error and next_attempt_at when one
-- failed. An event that has failed attempts times in a row (the dispatcher's limit) is given up and
-- left unprocessed. To recover one: fix the cause and set its attempts back to 0, which resubmits it;
-- or, if it can never be delivered, rebuild its branch's report figures (report_rebuild_branch, V23
-- part 2, in a REPEATABLE READ transaction), which counts it from the sources and marks it applied.
--
-- seq is taken as an event is written, not as its transaction commits: a company's events reach its
-- listeners in seq order among the events already committed, so one committed after a later-numbered
-- event was delivered follows it. Events about the same order or shift commit one after the other,
-- so they keep their order.
--
-- A row belongs to the company whose transaction wrote it and follows the same policy as every
-- company-owned table, so the app writes and delivers it only inside that company. The
-- dispatcher finds the pending rows of every company as the schema owner (SystemTransactions),
-- then delivers each inside its company on the app connection.
CREATE TABLE domain_event (
    id              UUID          NOT NULL,
    seq             BIGINT        GENERATED ALWAYS AS IDENTITY,
    company_id      UUID          NOT NULL,
    event_type      VARCHAR(255)  NOT NULL,
    payload         JSONB         NOT NULL,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    processed_at    TIMESTAMPTZ,
    attempts        INT           NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ,
    last_error      VARCHAR(2000),
    CONSTRAINT pk_domain_event PRIMARY KEY (id),
    CONSTRAINT fk_domain_event_company FOREIGN KEY (company_id) REFERENCES company (id)
);

-- What the dispatcher reads: the pending rows in order, and a company's earlier pending rows.
CREATE INDEX idx_domain_event_pending ON domain_event (seq) WHERE processed_at IS NULL;
CREATE INDEX idx_domain_event_company_pending ON domain_event (company_id, seq) WHERE processed_at IS NULL;

ALTER TABLE domain_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE domain_event FORCE ROW LEVEL SECURITY;
CREATE POLICY domain_event_tenant_isolation ON domain_event
    USING (company_id = NULLIF(current_setting('app.company_id', true), '')::uuid);
