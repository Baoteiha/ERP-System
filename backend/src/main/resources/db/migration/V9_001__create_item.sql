-- Item: what a supplier sells, and which ingredient it becomes once received (INV-309).
--
-- Suppliers quote kilograms and litres; stock is counted in grams and millilitres.
-- Until now purchase orders pointed straight at an ingredient, so there was nowhere to
-- record that gap and the buyer had to do the arithmetic in their head. This table is
-- that missing bridge: one row per (supplier, ingredient, purchase unit).
--
-- `unit` is constrained to the same vocabulary as ingredient.base_unit (V8_001), and the
-- application additionally requires the two to share a dimension -- you may buy in kg
-- what you count in g, never in ml. Packaging ("25 kg sack") is intentionally absent:
-- it is a label over a quantity of a real unit, and can be added later without moving
-- anything here.

CREATE TABLE item (
    id            UUID         NOT NULL,
    version       BIGINT       NOT NULL,
    company_id    UUID         NOT NULL,
    sku           VARCHAR(64),
    name          VARCHAR(255) NOT NULL,
    supplier_id   UUID         NOT NULL,
    ingredient_id UUID         NOT NULL,
    unit          VARCHAR(16)  NOT NULL,
    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    deleted       BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMPTZ  NOT NULL,
    created_by    VARCHAR(255),
    updated_at    TIMESTAMPTZ  NOT NULL,
    updated_by    VARCHAR(255),
    CONSTRAINT pk_item PRIMARY KEY (id),
    CONSTRAINT fk_item_company FOREIGN KEY (company_id) REFERENCES company (id),
    CONSTRAINT fk_item_supplier FOREIGN KEY (supplier_id) REFERENCES supplier (id),
    CONSTRAINT fk_item_ingredient FOREIGN KEY (ingredient_id) REFERENCES ingredient (id),
    CONSTRAINT ck_item_unit CHECK (unit IN ('mg', 'g', 'kg', 'ml', 'cl', 'l', 'pc'))
);

-- Partial rather than table constraints, for two reasons: sku is optional, and NULLs
-- would otherwise each count as distinct; and soft-deleted rows must not permanently
-- reserve a supplier's SKU or block re-adding an item someone removed by mistake.
CREATE UNIQUE INDEX uk_item_supplier_sku ON item (supplier_id, sku)
    WHERE sku IS NOT NULL AND deleted = FALSE;

-- One row per way of buying a thing: the same supplier may sell beans by the kg and by
-- the g, but not twice by the kg.
CREATE UNIQUE INDEX uk_item_supplier_ingredient_unit ON item (supplier_id, ingredient_id, unit)
    WHERE deleted = FALSE;

CREATE INDEX idx_item_company_id ON item (company_id);
CREATE INDEX idx_item_supplier_id ON item (supplier_id);
CREATE INDEX idx_item_ingredient_id ON item (ingredient_id);

-- Layer 2 tenant isolation, matching `ingredient` (V7_001/V7_002). TenantRlsAspect
-- already sets app.company_id for every inventory.application call, so ItemService is
-- covered with no extra wiring. NULLIF maps the reverted-setting empty string to NULL so
-- an absent tenant matches no rows instead of raising on the ::uuid cast.
ALTER TABLE item ENABLE ROW LEVEL SECURITY;
ALTER TABLE item FORCE ROW LEVEL SECURITY;

CREATE POLICY item_tenant_isolation ON item
    USING      (company_id = NULLIF(current_setting('app.company_id', true), '')::uuid)
    WITH CHECK (company_id = NULLIF(current_setting('app.company_id', true), '')::uuid);
