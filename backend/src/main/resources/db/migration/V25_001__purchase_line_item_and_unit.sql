-- A purchase line carries the unit it is bought in and, optionally, the item it was taken from
-- (INV-18, INV-5). Until now a line was always in the ingredient's base unit, so existing lines
-- take that unit: quantities and unit cost keep their meaning, and they receive exactly as before.
--
--   * unit: the canonical symbol the line's ordered/received quantity and unit_cost are counted in.
--   * item_id: the supplier item the line was created from, or NULL for a line made from an
--     ingredient and a unit. Foreign keys are checked past row-level security, so the service, not
--     this constraint, keeps a line from naming another company's item.

ALTER TABLE purchase_order_line ADD COLUMN item_id UUID;
ALTER TABLE purchase_order_line ADD COLUMN unit VARCHAR(16);

UPDATE purchase_order_line l
SET unit = i.base_unit
FROM ingredient i
WHERE i.id = l.ingredient_id;

ALTER TABLE purchase_order_line ALTER COLUMN unit SET NOT NULL;
ALTER TABLE purchase_order_line
    ADD CONSTRAINT ck_purchase_order_line_unit CHECK (unit IN ('mg', 'g', 'kg', 'ml', 'cl', 'l', 'pc'));
ALTER TABLE purchase_order_line
    ADD CONSTRAINT fk_pol_item FOREIGN KEY (item_id) REFERENCES item (id);
CREATE INDEX idx_pol_item_id ON purchase_order_line (item_id);
