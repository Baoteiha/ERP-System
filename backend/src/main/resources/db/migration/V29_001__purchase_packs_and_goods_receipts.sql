-- Purchase packs, goods receipts and retail purchases (spec 2026-10-07).
--   * item.pack_label / pack_size: an optional pack, a label over a quantity of the item's own unit
--     (ADR-0001). Both or neither.
--   * purchase_order_line.pack_label / pack_size: the item's pack copied when the order was placed. A
--     line with a pack counts ordered_qty, received_qty and unit_cost in packs.
--   * purchase_order.kind: ORDER, or RETAIL for a purchase saved already received.
--   * goods_receipt / goods_receipt_line: one receipt per delivery or replacement received.

ALTER TABLE item ADD COLUMN pack_label VARCHAR(32);
ALTER TABLE item ADD COLUMN pack_size NUMERIC(19,4);
ALTER TABLE item ADD CONSTRAINT ck_item_pack CHECK (
    (pack_label IS NULL AND pack_size IS NULL) OR (pack_label IS NOT NULL AND pack_size > 0));

-- One live row per way of buying a thing: a box of 100 and loose pieces from one supplier are two ways.
DROP INDEX uk_item_supplier_ingredient_unit;
CREATE UNIQUE INDEX uk_item_supplier_ingredient_unit_pack ON item
    (supplier_id, ingredient_id, unit, COALESCE(lower(pack_label), ''), COALESCE(pack_size, 0))
    WHERE deleted = FALSE;

ALTER TABLE purchase_order_line ADD COLUMN pack_label VARCHAR(32);
ALTER TABLE purchase_order_line ADD COLUMN pack_size NUMERIC(19,4);
ALTER TABLE purchase_order_line ADD CONSTRAINT ck_pol_pack CHECK (
    (pack_label IS NULL AND pack_size IS NULL) OR (pack_label IS NOT NULL AND pack_size > 0));

ALTER TABLE purchase_order ADD COLUMN kind VARCHAR(16) NOT NULL DEFAULT 'ORDER';
ALTER TABLE purchase_order ADD CONSTRAINT ck_purchase_order_kind CHECK (kind IN ('ORDER', 'RETAIL'));

CREATE TABLE goods_receipt (
    id                UUID         NOT NULL,
    version           BIGINT       NOT NULL,
    branch_id         UUID         NOT NULL,
    purchase_order_id UUID         NOT NULL,
    kind              VARCHAR(16)  NOT NULL,
    note              VARCHAR(255),
    created_at        TIMESTAMPTZ  NOT NULL,
    created_by        VARCHAR(255),
    updated_at        TIMESTAMPTZ  NOT NULL,
    updated_by        VARCHAR(255),
    CONSTRAINT pk_goods_receipt PRIMARY KEY (id),
    CONSTRAINT fk_goods_receipt_branch FOREIGN KEY (branch_id) REFERENCES branch (id),
    CONSTRAINT fk_goods_receipt_order FOREIGN KEY (purchase_order_id) REFERENCES purchase_order (id),
    CONSTRAINT ck_goods_receipt_kind CHECK (kind IN ('DELIVERY', 'COMPENSATION'))
);
CREATE INDEX idx_goods_receipt_order ON goods_receipt (purchase_order_id);

CREATE TABLE goods_receipt_line (
    id                     UUID          NOT NULL,
    version                BIGINT        NOT NULL,
    goods_receipt_id       UUID          NOT NULL,
    purchase_order_line_id UUID          NOT NULL,
    delivered_qty          NUMERIC(19,4) NOT NULL,
    damaged_qty            NUMERIC(19,4) NOT NULL DEFAULT 0,
    loss_qty               NUMERIC(19,4) NOT NULL DEFAULT 0,
    over_delivered_qty     NUMERIC(19,4) NOT NULL DEFAULT 0,
    over_delivery_kind     VARCHAR(16),
    unit_cost              NUMERIC(19,4) NOT NULL,
    currency               VARCHAR(3)    NOT NULL,
    cost_per_unit          NUMERIC(19,4) NOT NULL,
    compensated_qty        NUMERIC(19,4) NOT NULL DEFAULT 0,
    replacement_qty        NUMERIC(19,4) NOT NULL DEFAULT 0,
    compensates_line_id    UUID,
    note                   VARCHAR(255),
    CONSTRAINT pk_goods_receipt_line PRIMARY KEY (id),
    CONSTRAINT fk_grl_receipt FOREIGN KEY (goods_receipt_id) REFERENCES goods_receipt (id),
    CONSTRAINT fk_grl_po_line FOREIGN KEY (purchase_order_line_id) REFERENCES purchase_order_line (id),
    CONSTRAINT fk_grl_compensates FOREIGN KEY (compensates_line_id) REFERENCES goods_receipt_line (id),
    CONSTRAINT ck_grl_over_delivery CHECK ((over_delivered_qty = 0 AND over_delivery_kind IS NULL)
        OR (over_delivered_qty > 0 AND over_delivery_kind IN ('CHARGED', 'FREE'))),
    CONSTRAINT ck_grl_quantities CHECK (delivered_qty >= 0 AND damaged_qty >= 0 AND replacement_qty >= 0
        AND loss_qty >= 0 AND loss_qty <= damaged_qty AND compensated_qty >= 0 AND compensated_qty <= loss_qty)
);
CREATE INDEX idx_grl_receipt ON goods_receipt_line (goods_receipt_id);

ALTER TABLE goods_receipt ENABLE ROW LEVEL SECURITY;
ALTER TABLE goods_receipt FORCE ROW LEVEL SECURITY;
CREATE POLICY goods_receipt_tenant_isolation ON goods_receipt
    USING (branch_id IN (SELECT b.id FROM branch b));

ALTER TABLE goods_receipt_line ENABLE ROW LEVEL SECURITY;
ALTER TABLE goods_receipt_line FORCE ROW LEVEL SECURITY;
CREATE POLICY goods_receipt_line_tenant_isolation ON goods_receipt_line
    USING (EXISTS (SELECT 1 FROM goods_receipt p WHERE p.id = goods_receipt_line.goods_receipt_id));
