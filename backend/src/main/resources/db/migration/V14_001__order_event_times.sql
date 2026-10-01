-- Books a sale in the period it completed and a refund/void in the period it was reversed.
--
-- Backfill rules for rows that predate these columns:
--   * COMPLETED            : completed_at = updated_at (the last write was the completion).
--   * VOID / REFUNDED      : reversed_at  = updated_at (the last write was the reversal).
--   * VOID / REFUNDED that had completed first, i.e. cogs_total IS NOT NULL (set only when an order
--     completes, even for a recipe-less product): completed_at = created_at, the best available
--     stand-in because the row no longer records when it completed.
--   * PAID (never completed, so it never deducted stock) and VOID/REFUNDED with no cogs_total keep a
--     NULL completed_at; reports count those at created_at.
--   * A legacy PAID order completed after this migration moves from its created_at period to its
--     completion period.
ALTER TABLE sales_order ADD COLUMN completed_at TIMESTAMPTZ;
ALTER TABLE sales_order ADD COLUMN reversed_at TIMESTAMPTZ;

UPDATE sales_order SET completed_at = updated_at WHERE status = 'COMPLETED';

UPDATE sales_order SET reversed_at = updated_at WHERE status IN ('VOID', 'REFUNDED');

UPDATE sales_order SET completed_at = created_at
WHERE status IN ('VOID', 'REFUNDED') AND cogs_total IS NOT NULL;

CREATE INDEX idx_sales_order_branch_completed_at ON sales_order (branch_id, completed_at);
CREATE INDEX idx_sales_order_branch_reversed_at ON sales_order (branch_id, reversed_at);
