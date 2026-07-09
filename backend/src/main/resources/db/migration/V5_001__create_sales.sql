-- Phase 4 / sales module: orders (POS tickets), their lines + chosen modifiers,
-- and payments/refunds. Orders reference products/modifiers by id (snapshotted),
-- and deduct ingredient stock on completion via the inventory ledger.

CREATE TABLE sales_order (
    id              UUID          NOT NULL,
    version         BIGINT        NOT NULL,
    branch_id       UUID          NOT NULL,
    cashier_user_id UUID,
    order_type      VARCHAR(16)   NOT NULL,
    status          VARCHAR(16)   NOT NULL,
    currency        VARCHAR(3)    NOT NULL,
    tax_rate        NUMERIC(9,6)  NOT NULL DEFAULT 0,
    subtotal        NUMERIC(19,4) NOT NULL DEFAULT 0,
    order_discount  NUMERIC(19,4) NOT NULL DEFAULT 0,
    discount_total  NUMERIC(19,4) NOT NULL DEFAULT 0,
    tax_total       NUMERIC(19,4) NOT NULL DEFAULT 0,
    grand_total     NUMERIC(19,4) NOT NULL DEFAULT 0,
    cogs_total      NUMERIC(19,4),
    created_at      TIMESTAMPTZ   NOT NULL,
    created_by      VARCHAR(255),
    updated_at      TIMESTAMPTZ   NOT NULL,
    updated_by      VARCHAR(255),
    CONSTRAINT pk_sales_order PRIMARY KEY (id),
    CONSTRAINT fk_sales_order_branch FOREIGN KEY (branch_id) REFERENCES branch (id)
);

CREATE TABLE order_line (
    id                UUID          NOT NULL,
    version           BIGINT        NOT NULL,
    order_id          UUID,
    product_id        UUID          NOT NULL,
    product_name      VARCHAR(255)  NOT NULL,
    unit_price        NUMERIC(19,4),
    currency          VARCHAR(3),
    quantity          INTEGER       NOT NULL,
    line_discount     NUMERIC(19,4),
    discount_currency VARCHAR(3),
    line_total        NUMERIC(19,4),
    total_currency    VARCHAR(3),
    CONSTRAINT pk_order_line PRIMARY KEY (id),
    CONSTRAINT fk_order_line_order FOREIGN KEY (order_id) REFERENCES sales_order (id)
);

CREATE TABLE order_line_modifier (
    id            UUID          NOT NULL,
    version       BIGINT        NOT NULL,
    order_line_id UUID,
    modifier_id   UUID,
    modifier_name VARCHAR(255)  NOT NULL,
    price_delta   NUMERIC(19,4),
    currency      VARCHAR(3),
    CONSTRAINT pk_order_line_modifier PRIMARY KEY (id),
    CONSTRAINT fk_olm_order_line FOREIGN KEY (order_line_id) REFERENCES order_line (id)
);

CREATE TABLE payment (
    id         UUID          NOT NULL,
    version    BIGINT        NOT NULL,
    order_id   UUID,
    method     VARCHAR(16)   NOT NULL,
    type       VARCHAR(16)   NOT NULL,
    amount     NUMERIC(19,4),
    currency   VARCHAR(3),
    created_at TIMESTAMPTZ   NOT NULL,
    created_by VARCHAR(255),
    updated_at TIMESTAMPTZ   NOT NULL,
    updated_by VARCHAR(255),
    CONSTRAINT pk_payment PRIMARY KEY (id),
    CONSTRAINT fk_payment_order FOREIGN KEY (order_id) REFERENCES sales_order (id)
);

CREATE INDEX idx_sales_order_branch_id ON sales_order (branch_id);
CREATE INDEX idx_order_line_order_id ON order_line (order_id);
CREATE INDEX idx_olm_order_line_id ON order_line_modifier (order_line_id);
CREATE INDEX idx_payment_order_id ON payment (order_id);
