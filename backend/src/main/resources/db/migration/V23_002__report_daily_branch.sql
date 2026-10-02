-- Reporting's read model (REP-9, ADR-0009): one row of figures per branch and report day, kept by
-- ReportProjection from OrderCompleted, OrderReversed and ShiftCompleted, so a report reads a few
-- rows instead of asking sales, inventory and staff about every period. A report day is a calendar
-- date in app.reporting.zone.
--
-- The figures follow the rules reports have used since V14:
--   * a sale counts on the day it completed; a legacy PAID order that never completed, on the day
--     it was created (completing it later moves it to its completion day);
--   * a void or refund takes the sale's money back on the day of the reversal; order_count stays
--     the number of sales made;
--   * net sales are gross less discounts, tax excluded; the grand total (net + tax) is not stored;
--   * COGS is the cost of the stock the sales took (-quantity x unit cost of each SALE_DEPLETION and
--     SALE_REVERSAL movement, with the ledger's own sign, so stock a sale returns is a negative cost),
--     on the day of the movement, which the sale or reversal writes in its own transaction;
--   * labour is a COMPLETED shift's cost, on the day it clocked out. report_shift_labor holds each
--     shift's current share, so an adjusted shift moves its labour instead of adding to it.
-- report_applied_event lists the events the projection has applied, so one delivered twice
-- changes nothing.
--
-- report_rebuild_branch recomputes one branch's rows from those sources and marks every event
-- already written about the branch as applied, so one still on its way is not counted twice. It
-- rounds a day's ledger cost once, half to even, at the Money scale; every other figure is a sum
-- of stored amounts. Its COGS can differ from the events' only immaterially: an event carries its
-- sale's cost rounded line by line (at most 0.00005 a line from one rounding of the day), and a
-- movement is stamped within milliseconds of its sale's completion or reversal, which moves it to
-- another day only within those milliseconds of midnight. This migration runs it for every branch
-- (the backfill), in the zone of the reportingZone placeholder, which the app takes from
-- app.reporting.zone; a Flyway run outside the app must supply it. After changing
-- app.reporting.zone, run it again for every branch, as the schema owner, in a REPEATABLE READ
-- transaction, so the sources it reads and the events it marks are one snapshot.
CREATE TABLE report_daily_branch (
    company_id     UUID          NOT NULL,
    branch_id      UUID          NOT NULL,
    day            DATE          NOT NULL,
    order_count    BIGINT        NOT NULL DEFAULT 0,
    gross_sales    NUMERIC(19,4) NOT NULL DEFAULT 0,
    discount_total NUMERIC(19,4) NOT NULL DEFAULT 0,
    net_sales      NUMERIC(19,4) NOT NULL DEFAULT 0,
    tax_total      NUMERIC(19,4) NOT NULL DEFAULT 0,
    cogs           NUMERIC(19,4) NOT NULL DEFAULT 0,
    labor_cost     NUMERIC(19,4) NOT NULL DEFAULT 0,
    CONSTRAINT pk_report_daily_branch PRIMARY KEY (branch_id, day),
    CONSTRAINT fk_report_daily_branch_company FOREIGN KEY (company_id) REFERENCES company (id),
    CONSTRAINT fk_report_daily_branch_branch FOREIGN KEY (branch_id) REFERENCES branch (id)
);

CREATE TABLE report_shift_labor (
    shift_id   UUID          NOT NULL,
    company_id UUID          NOT NULL,
    branch_id  UUID          NOT NULL,
    day        DATE          NOT NULL,
    labor_cost NUMERIC(19,4) NOT NULL,
    CONSTRAINT pk_report_shift_labor PRIMARY KEY (shift_id),
    CONSTRAINT fk_report_shift_labor_company FOREIGN KEY (company_id) REFERENCES company (id),
    CONSTRAINT fk_report_shift_labor_branch FOREIGN KEY (branch_id) REFERENCES branch (id)
);

CREATE INDEX idx_report_shift_labor_branch ON report_shift_labor (branch_id);

CREATE TABLE report_applied_event (
    event_id   UUID        NOT NULL,
    company_id UUID        NOT NULL,
    applied_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_report_applied_event PRIMARY KEY (event_id),
    CONSTRAINT fk_report_applied_event_company FOREIGN KEY (company_id) REFERENCES company (id)
);

ALTER TABLE report_daily_branch ENABLE ROW LEVEL SECURITY;
ALTER TABLE report_daily_branch FORCE ROW LEVEL SECURITY;
CREATE POLICY report_daily_branch_tenant_isolation ON report_daily_branch
    USING (company_id = NULLIF(current_setting('app.company_id', true), '')::uuid);

ALTER TABLE report_shift_labor ENABLE ROW LEVEL SECURITY;
ALTER TABLE report_shift_labor FORCE ROW LEVEL SECURITY;
CREATE POLICY report_shift_labor_tenant_isolation ON report_shift_labor
    USING (company_id = NULLIF(current_setting('app.company_id', true), '')::uuid);

ALTER TABLE report_applied_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE report_applied_event FORCE ROW LEVEL SECURITY;
CREATE POLICY report_applied_event_tenant_isolation ON report_applied_event
    USING (company_id = NULLIF(current_setting('app.company_id', true), '')::uuid);

CREATE FUNCTION report_rebuild_branch(p_branch_id UUID, p_zone TEXT) RETURNS VOID
LANGUAGE plpgsql AS $$
DECLARE
    v_company_id UUID;
BEGIN
    SELECT company_id INTO v_company_id FROM branch WHERE id = p_branch_id;
    IF v_company_id IS NULL THEN
        RAISE EXCEPTION 'No branch %', p_branch_id;
    END IF;

    DELETE FROM report_daily_branch WHERE branch_id = p_branch_id;
    DELETE FROM report_shift_labor WHERE branch_id = p_branch_id;

    INSERT INTO report_shift_labor (shift_id, company_id, branch_id, day, labor_cost)
    SELECT s.id, v_company_id, s.branch_id, (s.clock_out_at AT TIME ZONE p_zone)::date, COALESCE(s.labor_cost, 0)
    FROM shift s
    WHERE s.branch_id = p_branch_id AND s.status = 'COMPLETED' AND s.clock_out_at IS NOT NULL;

    INSERT INTO report_daily_branch (company_id, branch_id, day, order_count, gross_sales, discount_total,
                                     net_sales, tax_total, cogs, labor_cost)
    SELECT v_company_id, p_branch_id, d.day, d.orders, d.gross, d.discount, d.net, d.tax,
           CASE WHEN abs(d.cogs * 10000 - trunc(d.cogs * 10000)) = 0.5 AND mod(trunc(d.cogs * 10000), 2) = 0
                THEN trunc(d.cogs, 4)
                ELSE round(d.cogs, 4)
           END,
           d.labor
    FROM (
        SELECT f.day, SUM(f.orders) AS orders, SUM(f.gross) AS gross, SUM(f.discount) AS discount,
               SUM(f.net) AS net, SUM(f.tax) AS tax, SUM(f.cogs) AS cogs, SUM(f.labor) AS labor
        FROM (
            -- each sale, on the day it completed (a legacy PAID order's on the day it was created)
            SELECT (COALESCE(o.completed_at, o.created_at) AT TIME ZONE p_zone)::date AS day, 1 AS orders,
                   o.subtotal AS gross, o.discount_total AS discount, o.subtotal - o.discount_total AS net,
                   o.tax_total AS tax, 0 AS cogs, 0 AS labor
            FROM sales_order o
            WHERE o.branch_id = p_branch_id AND o.status IN ('PAID', 'COMPLETED', 'VOID', 'REFUNDED')
            UNION ALL
            -- its void or refund, on the day of the reversal
            SELECT (o.reversed_at AT TIME ZONE p_zone)::date, 0, -o.subtotal, -o.discount_total,
                   o.discount_total - o.subtotal, -o.tax_total, 0, 0
            FROM sales_order o
            WHERE o.branch_id = p_branch_id AND o.status IN ('VOID', 'REFUNDED') AND o.reversed_at IS NOT NULL
            UNION ALL
            -- the cost with the ledger's own sign: a sale's movement takes stock out (negative quantity,
            -- positive cost) unless a modifier made the net consumption negative; a reversal undoes it
            SELECT (m.created_at AT TIME ZONE p_zone)::date, 0, 0, 0, 0, 0, -m.quantity * COALESCE(m.unit_cost, 0), 0
            FROM stock_movement m
            WHERE m.branch_id = p_branch_id AND m.type IN ('SALE_DEPLETION', 'SALE_REVERSAL')
            UNION ALL
            SELECT l.day, 0, 0, 0, 0, 0, 0, l.labor_cost
            FROM report_shift_labor l
            WHERE l.branch_id = p_branch_id
        ) f
        GROUP BY f.day
    ) d;

    INSERT INTO report_applied_event (event_id, company_id)
    SELECT e.id, e.company_id
    FROM domain_event e
    WHERE e.company_id = v_company_id AND e.payload->>'branchId' = p_branch_id::text
    ON CONFLICT (event_id) DO NOTHING;
END
$$;

-- The backfill's and an operator's tool, run as the schema owner; the app never calls it.
REVOKE EXECUTE ON FUNCTION report_rebuild_branch(UUID, TEXT) FROM PUBLIC;

-- An unknown zone fails here even on an empty database, where no row would have tried it.
DO $$
BEGIN
    PERFORM now() AT TIME ZONE '${reportingZone}';
    PERFORM report_rebuild_branch(b.id, '${reportingZone}') FROM branch b;
END $$;
