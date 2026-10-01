-- Schema hygiene: soft-deleted names and codes become reusable, the sales and ledger
-- reads get the indexes they filter by, and the sales child tables stop accepting
-- rows that make no sense.
--
-- A full UNIQUE constraint also counts soft-deleted rows, so deleting "LAT1" made the
-- SKU unusable forever. Each is replaced by a unique index over live rows only. The
-- old constraints allowed at most one row per key, so no existing data can collide
-- with the new, weaker indexes.

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
