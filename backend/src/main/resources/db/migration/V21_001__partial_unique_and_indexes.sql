-- Schema hygiene: soft-deleted names and codes become reusable, the sales and ledger
-- reads get the indexes they filter by, and the sales child tables stop accepting
-- rows that make no sense.
--
-- A full UNIQUE constraint also counts soft-deleted rows, so deleting "LAT1" made the
-- SKU unusable forever. Each is replaced by a unique index over live rows only. The
-- old constraints allowed at most one row per key, so no existing data can collide
-- with the new, weaker indexes.
--
-- The sales tables also gain NOT NULLs and CHECKs. Order lines and line modifiers whose
-- parent is NULL are derived, unreachable children and are DELETED here so the NOT NULLs
-- can apply. Payments are money records and are never deleted: if any payment has no
-- order, or any row would break a new constraint, the migration stops with a message
-- naming the rows so a person can decide.

ALTER TABLE company DROP CONSTRAINT uk_company_code;
CREATE UNIQUE INDEX uk_company_code ON company (code) WHERE deleted = FALSE;

ALTER TABLE branch DROP CONSTRAINT uk_branch_company_code;
CREATE UNIQUE INDEX uk_branch_company_code ON branch (company_id, code) WHERE deleted = FALSE;

ALTER TABLE category DROP CONSTRAINT uk_category_company_name;
CREATE UNIQUE INDEX uk_category_company_name ON category (company_id, name) WHERE deleted = FALSE;

ALTER TABLE modifier_group DROP CONSTRAINT uk_modifier_group_company_name;
CREATE UNIQUE INDEX uk_modifier_group_company_name ON modifier_group (company_id, name) WHERE deleted = FALSE;

ALTER TABLE product DROP CONSTRAINT uk_product_company_sku;
CREATE UNIQUE INDEX uk_product_company_sku ON product (company_id, sku) WHERE deleted = FALSE;

ALTER TABLE ingredient DROP CONSTRAINT uk_ingredient_company_name;
CREATE UNIQUE INDEX uk_ingredient_company_name ON ingredient (company_id, name) WHERE deleted = FALSE;

ALTER TABLE supplier DROP CONSTRAINT uk_supplier_company_name;
CREATE UNIQUE INDEX uk_supplier_company_name ON supplier (company_id, name) WHERE deleted = FALSE;

-- Order lists by branch and status, newest first; the ledger by branch (and ingredient or type) over time.
CREATE INDEX idx_sales_order_branch_status_created ON sales_order (branch_id, status, created_at);
CREATE INDEX idx_stock_movement_branch_created ON stock_movement (branch_id, created_at DESC);
CREATE INDEX idx_stock_movement_branch_ingredient_created ON stock_movement (branch_id, ingredient_id, created_at DESC);
CREATE INDEX idx_stock_movement_branch_type_created ON stock_movement (branch_id, type, created_at);

-- Derived children detached from their parent can never be reached again; clear them so the NOT NULLs apply.
DELETE FROM order_line_modifier
 WHERE order_line_id IS NULL
    OR order_line_id IN (SELECT id FROM order_line WHERE order_id IS NULL);
DELETE FROM order_line WHERE order_id IS NULL;

-- Payments are money: stop the deploy rather than guess.
DO $$
DECLARE
    n   BIGINT;
    ids TEXT;
BEGIN
    SELECT count(*), string_agg(id::text, ', ') INTO n, ids
      FROM (SELECT id FROM payment WHERE order_id IS NULL ORDER BY id LIMIT 20) p;
    IF n > 0 THEN
        RAISE EXCEPTION 'V21: % payment row(s) have a NULL order_id (first 20 ids: %); resolve them by hand before migrating',
            (SELECT count(*) FROM payment WHERE order_id IS NULL), ids;
    END IF;

    SELECT count(*), string_agg(id::text, ', ') INTO n, ids
      FROM (SELECT id FROM payment WHERE amount IS NULL OR amount <= 0 ORDER BY id LIMIT 20) p;
    IF n > 0 THEN
        RAISE EXCEPTION 'V21: % payment row(s) have a NULL or non-positive amount (first 20 ids: %); fix them before migrating',
            (SELECT count(*) FROM payment WHERE amount IS NULL OR amount <= 0), ids;
    END IF;

    SELECT count(*), string_agg(id::text, ', ') INTO n, ids
      FROM (SELECT id FROM order_line WHERE quantity <= 0 ORDER BY id LIMIT 20) p;
    IF n > 0 THEN
        RAISE EXCEPTION 'V21: % order_line row(s) have quantity <= 0 (first 20 ids: %); fix them before migrating',
            (SELECT count(*) FROM order_line WHERE quantity <= 0), ids;
    END IF;

    SELECT count(*), string_agg(id::text, ', ') INTO n, ids
      FROM (SELECT id FROM order_line WHERE line_total IS NULL ORDER BY id LIMIT 20) p;
    IF n > 0 THEN
        RAISE EXCEPTION 'V21: % order_line row(s) have a NULL line_total (first 20 ids: %); fix them before migrating',
            (SELECT count(*) FROM order_line WHERE line_total IS NULL), ids;
    END IF;
END $$;

ALTER TABLE order_line ALTER COLUMN order_id SET NOT NULL;
ALTER TABLE order_line ALTER COLUMN line_total SET NOT NULL;
ALTER TABLE order_line_modifier ALTER COLUMN order_line_id SET NOT NULL;
ALTER TABLE payment ALTER COLUMN order_id SET NOT NULL;
ALTER TABLE payment ALTER COLUMN amount SET NOT NULL;

-- Refunds are stored as positive amounts with type REFUND, so every payment row is above zero.
ALTER TABLE order_line ADD CONSTRAINT ck_order_line_quantity_positive CHECK (quantity > 0);
ALTER TABLE payment ADD CONSTRAINT ck_payment_amount_positive CHECK (amount > 0);
