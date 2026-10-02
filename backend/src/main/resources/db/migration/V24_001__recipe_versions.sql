-- Recipes become versioned consumption (CAT-13, ADR-0010). Saving a recipe adds a version and
-- never rewrites one; an order line records the version in force when it was taken, and its sale
-- consumes that version, however the recipe changed before payment.
--
--   * recipe_version: one row per save, numbered from 1 per recipe. recipe.current_version_no is
--     the version in force. The app role may add versions and lines but never change or delete
--     them: orders still open point at them.
--   * recipe_line belongs to a version instead of the recipe. Each existing recipe's lines, as they
--     stand, become its version 1, in force since the recipe was last saved (its updated_at). A
--     line that belongs to no recipe is deleted: no sale can reach it, and the policies already
--     hide it from the app.
--   * order_line.recipe_version_id: the version a line consumes. Existing lines of OPEN and PAID
--     orders, which still deduct stock when they complete, get their product's version 1, the only
--     version there is. Lines of finished orders stay NULL: a completed sale's consumption is in
--     the ledger (and its reversal reads it there), and a cancelled or reversed-before-completion
--     order never deducts. From here on NULL means the product had no recipe when the line was
--     taken, so the line consumes nothing. The column names a catalog row by id, without a
--     foreign key, as order lines name their product.
--   * Recipe lines and modifier deltas store a unit's canonical symbol, as ingredient.base_unit
--     does since V8. The spellings the app accepts are rewritten (Unit.ALIASES); any other unit
--     stops the migration, listing the rows: decide what each line really measures, fix it, and
--     re-run. Modifier deltas are not versioned (ADR-0010): they keep their table, without the
--     optimistic-lock column nothing reads any more.

CREATE TEMPORARY TABLE unit_alias (spelling, canonical) ON COMMIT DROP AS VALUES
    ('mg', 'mg'), ('milligram', 'mg'), ('milligrams', 'mg'),
    ('g', 'g'), ('gr', 'g'), ('gram', 'g'), ('grams', 'g'), ('gam', 'g'),
    ('kg', 'kg'), ('kgs', 'kg'), ('kilo', 'kg'), ('kilos', 'kg'),
    ('kilogram', 'kg'), ('kilograms', 'kg'),
    ('ml', 'ml'), ('mls', 'ml'), ('milliliter', 'ml'), ('millilitre', 'ml'),
    ('milliliters', 'ml'), ('millilitres', 'ml'),
    ('cl', 'cl'), ('centiliter', 'cl'), ('centilitre', 'cl'),
    ('l', 'l'), ('lit', 'l'), ('ltr', 'l'), ('liter', 'l'), ('litre', 'l'),
    ('liters', 'l'), ('litres', 'l'),
    ('pc', 'pc'), ('pcs', 'pc'), ('piece', 'pc'), ('pieces', 'pc'),
    ('unit', 'pc'), ('units', 'pc'), ('ea', 'pc'), ('each', 'pc');

DELETE FROM recipe_line WHERE recipe_id IS NULL;

DO $$
DECLARE
    tbl TEXT;
    bad BIGINT;
    ids TEXT;
    units TEXT;
BEGIN
    FOREACH tbl IN ARRAY ARRAY['recipe_line', 'modifier_recipe_line'] LOOP
        EXECUTE format('SELECT count(*), string_agg(DISTINCT t.unit, '', '') FROM %I t'
                || ' WHERE NOT EXISTS (SELECT 1 FROM unit_alias a WHERE a.spelling = lower(trim(t.unit)))', tbl)
            INTO bad, units;
        IF bad > 0 THEN
            EXECUTE format('SELECT string_agg(id::text, '', '') FROM (SELECT t.id FROM %I t'
                    || ' WHERE NOT EXISTS (SELECT 1 FROM unit_alias a WHERE a.spelling = lower(trim(t.unit)))'
                    || ' ORDER BY t.id LIMIT 20) p', tbl)
                INTO ids;
            RAISE EXCEPTION 'V24: % % row(s) have a unit outside mg, g, kg, ml, cl, l, pc (units: %; first 20 ids: %); fix them before migrating',
                bad, tbl, units, ids;
        END IF;
    END LOOP;
END $$;

UPDATE recipe_line t SET unit = a.canonical
FROM unit_alias a
WHERE lower(trim(t.unit)) = a.spelling AND t.unit <> a.canonical;

UPDATE modifier_recipe_line t SET unit = a.canonical
FROM unit_alias a
WHERE lower(trim(t.unit)) = a.spelling AND t.unit <> a.canonical;

ALTER TABLE recipe_line
    ADD CONSTRAINT ck_recipe_line_unit CHECK (unit IN ('mg', 'g', 'kg', 'ml', 'cl', 'l', 'pc'));
ALTER TABLE modifier_recipe_line
    ADD CONSTRAINT ck_modifier_recipe_line_unit CHECK (unit IN ('mg', 'g', 'kg', 'ml', 'cl', 'l', 'pc'));

-- versions

CREATE TABLE recipe_version (
    id         UUID        NOT NULL,
    recipe_id  UUID        NOT NULL,
    version_no INT         NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_recipe_version PRIMARY KEY (id),
    CONSTRAINT uk_recipe_version_no UNIQUE (recipe_id, version_no),
    CONSTRAINT fk_recipe_version_recipe FOREIGN KEY (recipe_id) REFERENCES recipe (id),
    CONSTRAINT ck_recipe_version_no_positive CHECK (version_no > 0)
);

INSERT INTO recipe_version (id, recipe_id, version_no, created_at)
SELECT gen_random_uuid(), r.id, 1, r.updated_at FROM recipe r;

ALTER TABLE recipe ADD COLUMN current_version_no INT;
UPDATE recipe SET current_version_no = 1;
ALTER TABLE recipe ALTER COLUMN current_version_no SET NOT NULL;

-- recipe lines move under their version; the old policy names the column that goes
DROP POLICY recipe_line_tenant_isolation ON recipe_line;
ALTER TABLE recipe_line ADD COLUMN recipe_version_id UUID;
UPDATE recipe_line l SET recipe_version_id = v.id
FROM recipe_version v
WHERE v.recipe_id = l.recipe_id AND v.version_no = 1;
ALTER TABLE recipe_line ALTER COLUMN recipe_version_id SET NOT NULL;
ALTER TABLE recipe_line
    ADD CONSTRAINT fk_recipe_line_version FOREIGN KEY (recipe_version_id) REFERENCES recipe_version (id);
DROP INDEX idx_recipe_line_recipe_id;
CREATE INDEX idx_recipe_line_recipe_version_id ON recipe_line (recipe_version_id);
ALTER TABLE recipe_line DROP COLUMN recipe_id;

-- A line is a value of its owner now: the row keeps a key of its own, which the app never writes.
ALTER TABLE recipe_line DROP COLUMN version;
ALTER TABLE recipe_line ALTER COLUMN id SET DEFAULT gen_random_uuid();
ALTER TABLE modifier_recipe_line DROP COLUMN version;
ALTER TABLE modifier_recipe_line ALTER COLUMN id SET DEFAULT gen_random_uuid();

-- the version each order line consumes

ALTER TABLE order_line ADD COLUMN recipe_version_id UUID;
UPDATE order_line l SET recipe_version_id = v.id
FROM sales_order o, recipe r, recipe_version v
WHERE o.id = l.order_id
  AND o.status IN ('OPEN', 'PAID')
  AND r.product_id = l.product_id
  AND v.recipe_id = r.id AND v.version_no = 1;

-- tenancy: a version through its recipe, a line through its version (V22's rule for children)

ALTER TABLE recipe_version ENABLE ROW LEVEL SECURITY;
ALTER TABLE recipe_version FORCE ROW LEVEL SECURITY;
CREATE POLICY recipe_version_tenant_isolation ON recipe_version
    USING (EXISTS (SELECT 1 FROM recipe p WHERE p.id = recipe_version.recipe_id));

CREATE POLICY recipe_line_tenant_isolation ON recipe_line
    USING (EXISTS (SELECT 1 FROM recipe_version p WHERE p.id = recipe_line.recipe_version_id));

-- recipe_version took V22's default grants; a version and its lines are written once
REVOKE UPDATE, DELETE ON recipe_version FROM erp_app;
REVOKE UPDATE, DELETE ON recipe_line FROM erp_app;
