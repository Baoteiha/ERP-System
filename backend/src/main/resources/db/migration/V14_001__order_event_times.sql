-- Books a sale in the period it completed and a refund/void in the period it was reversed.
--
-- Backfill rules for rows that predate these columns:
--   * COMPLETED            : completed_at = updated_at (the last write was the completion).
--   * VOID / REFUNDED      : reversed_at  = updated_at (the last write was the reversal).
--   * VOID / REFUNDED that had completed first, i.e. SALE_DEPLETION movements exist for the order
--     (stock_movement.ref_type = 'ORDER' and ref_id = order id): completed_at = created_at, the best
--     available stand-in because the row no longer records when it completed.
--   * PAID (never completed, so it never deducted stock) and VOID/REFUNDED without depletion keep a
--     NULL completed_at; reports count those at created_at.
ALTER TABLE sales_order ADD COLUMN completed_at TIMESTAMPTZ;
ALTER TABLE sales_order ADD COLUMN reversed_at TIMESTAMPTZ;

UPDATE sales_order SET completed_at = updated_at WHERE status = 'COMPLETED';

UPDATE sales_order SET reversed_at = updated_at WHERE status IN ('VOID', 'REFUNDED');

UPDATE sales_order o SET completed_at = o.created_at
WHERE o.status IN ('VOID', 'REFUNDED')
  AND EXISTS (SELECT 1 FROM stock_movement m
              WHERE m.ref_type = 'ORDER' AND m.ref_id = o.id AND m.type = 'SALE_DEPLETION');

CREATE INDEX idx_sales_order_branch_completed_at ON sales_order (branch_id, completed_at);
CREATE INDEX idx_sales_order_branch_reversed_at ON sales_order (branch_id, reversed_at);
