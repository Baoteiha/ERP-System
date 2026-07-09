-- Phase 3 / inventory module: ingredients, suppliers, per-branch stock, the
-- append-only stock movement ledger, and purchase orders with receiving.

CREATE TABLE ingredient (
    id         UUID         NOT NULL,
    version    BIGINT       NOT NULL,
    company_id UUID         NOT NULL,
    name       VARCHAR(255) NOT NULL,
    base_unit  VARCHAR(16)  NOT NULL,
    category   VARCHAR(64),
    active     BOOLEAN      NOT NULL DEFAULT TRUE,
    deleted    BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ  NOT NULL,
    created_by VARCHAR(255),
    updated_at TIMESTAMPTZ  NOT NULL,
    updated_by VARCHAR(255),
    CONSTRAINT pk_ingredient PRIMARY KEY (id),
    CONSTRAINT uk_ingredient_company_name UNIQUE (company_id, name),
    CONSTRAINT fk_ingredient_company FOREIGN KEY (company_id) REFERENCES company (id)
);

CREATE TABLE supplier (
    id            UUID         NOT NULL,
    version       BIGINT       NOT NULL,
    company_id    UUID         NOT NULL,
    name          VARCHAR(255) NOT NULL,
    contact_phone VARCHAR(32),
    contact_email VARCHAR(255),
    address       VARCHAR(500),
    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    deleted       BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMPTZ  NOT NULL,
    created_by    VARCHAR(255),
    updated_at    TIMESTAMPTZ  NOT NULL,
    updated_by    VARCHAR(255),
    CONSTRAINT pk_supplier PRIMARY KEY (id),
    CONSTRAINT uk_supplier_company_name UNIQUE (company_id, name),
    CONSTRAINT fk_supplier_company FOREIGN KEY (company_id) REFERENCES company (id)
);

CREATE TABLE stock_item (
    id               UUID          NOT NULL,
    version          BIGINT        NOT NULL,
    branch_id        UUID          NOT NULL,
    ingredient_id    UUID          NOT NULL,
    quantity_on_hand NUMERIC(19,4) NOT NULL DEFAULT 0,
    reorder_level    NUMERIC(19,4) NOT NULL DEFAULT 0,
    avg_unit_cost    NUMERIC(19,4),
    currency         VARCHAR(3),
    created_at       TIMESTAMPTZ   NOT NULL,
    created_by       VARCHAR(255),
    updated_at       TIMESTAMPTZ   NOT NULL,
    updated_by       VARCHAR(255),
    CONSTRAINT pk_stock_item PRIMARY KEY (id),
    CONSTRAINT uk_stock_item_ingredient_branch UNIQUE (ingredient_id, branch_id),
    CONSTRAINT fk_stock_item_branch FOREIGN KEY (branch_id) REFERENCES branch (id),
    CONSTRAINT fk_stock_item_ingredient FOREIGN KEY (ingredient_id) REFERENCES ingredient (id)
);

CREATE TABLE stock_movement (
    id            UUID          NOT NULL,
    version       BIGINT        NOT NULL,
    branch_id     UUID          NOT NULL,
    ingredient_id UUID          NOT NULL,
    type          VARCHAR(24)   NOT NULL,
    quantity      NUMERIC(19,4) NOT NULL,
    unit_cost     NUMERIC(19,4),
    currency      VARCHAR(3),
    ref_type      VARCHAR(32),
    ref_id        UUID,
    note          VARCHAR(255),
    created_at    TIMESTAMPTZ   NOT NULL,
    created_by    VARCHAR(255),
    updated_at    TIMESTAMPTZ   NOT NULL,
    updated_by    VARCHAR(255),
    CONSTRAINT pk_stock_movement PRIMARY KEY (id),
    CONSTRAINT fk_stock_movement_branch FOREIGN KEY (branch_id) REFERENCES branch (id),
    CONSTRAINT fk_stock_movement_ingredient FOREIGN KEY (ingredient_id) REFERENCES ingredient (id)
);

CREATE TABLE purchase_order (
    id          UUID        NOT NULL,
    version     BIGINT      NOT NULL,
    branch_id   UUID        NOT NULL,
    supplier_id UUID        NOT NULL,
    status      VARCHAR(24) NOT NULL,
    note        VARCHAR(255),
    created_at  TIMESTAMPTZ NOT NULL,
    created_by  VARCHAR(255),
    updated_at  TIMESTAMPTZ NOT NULL,
    updated_by  VARCHAR(255),
    CONSTRAINT pk_purchase_order PRIMARY KEY (id),
    CONSTRAINT fk_purchase_order_branch FOREIGN KEY (branch_id) REFERENCES branch (id),
    CONSTRAINT fk_purchase_order_supplier FOREIGN KEY (supplier_id) REFERENCES supplier (id)
);

CREATE TABLE purchase_order_line (
    id                UUID          NOT NULL,
    version           BIGINT        NOT NULL,
    purchase_order_id UUID,
    ingredient_id     UUID          NOT NULL,
    ordered_qty       NUMERIC(19,4) NOT NULL,
    received_qty      NUMERIC(19,4) NOT NULL DEFAULT 0,
    unit_cost         NUMERIC(19,4),
    currency          VARCHAR(3),
    CONSTRAINT pk_purchase_order_line PRIMARY KEY (id),
    CONSTRAINT fk_pol_order FOREIGN KEY (purchase_order_id) REFERENCES purchase_order (id),
    CONSTRAINT fk_pol_ingredient FOREIGN KEY (ingredient_id) REFERENCES ingredient (id)
);

CREATE INDEX idx_ingredient_company_id ON ingredient (company_id);
CREATE INDEX idx_supplier_company_id ON supplier (company_id);
CREATE INDEX idx_stock_item_branch_id ON stock_item (branch_id);
CREATE INDEX idx_stock_movement_branch_id ON stock_movement (branch_id);
CREATE INDEX idx_stock_movement_ingredient_id ON stock_movement (ingredient_id);
CREATE INDEX idx_stock_movement_ref ON stock_movement (ref_type, ref_id);
CREATE INDEX idx_purchase_order_branch_id ON purchase_order (branch_id);
CREATE INDEX idx_pol_order_id ON purchase_order_line (purchase_order_id);
