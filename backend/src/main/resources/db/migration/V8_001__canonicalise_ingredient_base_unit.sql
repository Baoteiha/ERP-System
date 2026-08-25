-- Canonicalise ingredient.base_unit and close it to a known vocabulary (INV-104).
--
-- base_unit was free text, so "kg", "Kg", and "kilogram" could all describe the same
-- shelf while meaning nothing to the code that divides by it. Every downstream number
-- -- quantity on hand, moving-average cost, recipe deduction -- is denominated in this
-- unit, so it has to resolve to exactly one known value.
--
-- Step 1 rewrites the spellings the application now accepts (mirrors Unit.ALIASES).
-- Step 2 makes anything outside the vocabulary impossible from here on.

-- Flyway runs this migration in one transaction. V7 put ingredient under FORCE ROW
-- LEVEL SECURITY, which subjects even the table owner to the tenant policy — and no
-- app.company_id is set during a migration, so under a non-superuser migration role the
-- UPDATE below would silently match ZERO rows, after which the CHECK constraint (which
-- ignores RLS) fails on the very rows the UPDATE was meant to fix. Superusers bypass
-- RLS entirely, which is why dev never sees this. row_security = off restores the
-- owner's right to see its own table for the rest of this transaction only.
SET LOCAL row_security = off;

WITH alias (spelling, canonical) AS (VALUES
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
    ('unit', 'pc'), ('units', 'pc'), ('ea', 'pc'), ('each', 'pc')
)
UPDATE ingredient i
SET base_unit = a.canonical
FROM alias a
WHERE lower(trim(i.base_unit)) = a.spelling
  AND i.base_unit <> a.canonical;

-- If this constraint fails, a row holds a unit nobody can convert (say 'cup' or 'sack').
-- That is the migration doing its job: decide what the ingredient is really counted in,
-- fix the row, and re-run. Packaging like 'sack' is never a base unit -- it belongs to
-- the item being purchased, as a quantity of a real unit.
ALTER TABLE ingredient
    ADD CONSTRAINT ck_ingredient_base_unit
    CHECK (base_unit IN ('mg', 'g', 'kg', 'ml', 'cl', 'l', 'pc'));
