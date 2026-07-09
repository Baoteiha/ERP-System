-- Phase 2 / catalog module: categories, products, per-branch availability,
-- modifier groups/modifiers, and recipes. Ingredient references are plain UUIDs
-- (no FK) until the inventory module introduces the ingredient table.

CREATE TABLE category (
    id            UUID         NOT NULL,
    version       BIGINT       NOT NULL,
    company_id    UUID         NOT NULL,
    name          VARCHAR(255) NOT NULL,
    display_order INTEGER      NOT NULL DEFAULT 0,
    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    deleted       BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMPTZ  NOT NULL,
    created_by    VARCHAR(255),
    updated_at    TIMESTAMPTZ  NOT NULL,
    updated_by    VARCHAR(255),
    CONSTRAINT pk_category PRIMARY KEY (id),
    CONSTRAINT uk_category_company_name UNIQUE (company_id, name),
    CONSTRAINT fk_category_company FOREIGN KEY (company_id) REFERENCES company (id)
);

CREATE TABLE modifier_group (
    id         UUID         NOT NULL,
    version    BIGINT       NOT NULL,
    company_id UUID         NOT NULL,
    name       VARCHAR(255) NOT NULL,
    min_select INTEGER      NOT NULL DEFAULT 0,
    max_select INTEGER      NOT NULL DEFAULT 1,
    deleted    BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ  NOT NULL,
    created_by VARCHAR(255),
    updated_at TIMESTAMPTZ  NOT NULL,
    updated_by VARCHAR(255),
    CONSTRAINT pk_modifier_group PRIMARY KEY (id),
    CONSTRAINT uk_modifier_group_company_name UNIQUE (company_id, name),
    CONSTRAINT fk_modifier_group_company FOREIGN KEY (company_id) REFERENCES company (id)
);

CREATE TABLE modifier (
    id                UUID          NOT NULL,
    version           BIGINT        NOT NULL,
    modifier_group_id UUID,
    name              VARCHAR(255)  NOT NULL,
    price_delta       NUMERIC(19,4),
    currency          VARCHAR(3),
    display_order     INTEGER       NOT NULL DEFAULT 0,
    active            BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at        TIMESTAMPTZ   NOT NULL,
    created_by        VARCHAR(255),
    updated_at        TIMESTAMPTZ   NOT NULL,
    updated_by        VARCHAR(255),
    CONSTRAINT pk_modifier PRIMARY KEY (id),
    CONSTRAINT fk_modifier_group FOREIGN KEY (modifier_group_id) REFERENCES modifier_group (id)
);

CREATE TABLE modifier_recipe_line (
    id             UUID          NOT NULL,
    version        BIGINT        NOT NULL,
    modifier_id    UUID,
    ingredient_id  UUID          NOT NULL,
    quantity_delta NUMERIC(19,4) NOT NULL,
    unit           VARCHAR(16)   NOT NULL,
    CONSTRAINT pk_modifier_recipe_line PRIMARY KEY (id),
    CONSTRAINT fk_mrl_modifier FOREIGN KEY (modifier_id) REFERENCES modifier (id)
);

CREATE TABLE product (
    id          UUID          NOT NULL,
    version     BIGINT        NOT NULL,
    company_id  UUID          NOT NULL,
    category_id UUID          NOT NULL,
    name        VARCHAR(255)  NOT NULL,
    sku         VARCHAR(64)   NOT NULL,
    description VARCHAR(1000),
    base_price  NUMERIC(19,4),
    currency    VARCHAR(3),
    active      BOOLEAN       NOT NULL DEFAULT TRUE,
    deleted     BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ   NOT NULL,
    created_by  VARCHAR(255),
    updated_at  TIMESTAMPTZ   NOT NULL,
    updated_by  VARCHAR(255),
    CONSTRAINT pk_product PRIMARY KEY (id),
    CONSTRAINT uk_product_company_sku UNIQUE (company_id, sku),
    CONSTRAINT fk_product_company FOREIGN KEY (company_id) REFERENCES company (id),
    CONSTRAINT fk_product_category FOREIGN KEY (category_id) REFERENCES category (id)
);

CREATE TABLE product_modifier_group (
    product_id        UUID NOT NULL,
    modifier_group_id UUID NOT NULL,
    CONSTRAINT pk_product_modifier_group PRIMARY KEY (product_id, modifier_group_id),
    CONSTRAINT fk_pmg_product FOREIGN KEY (product_id) REFERENCES product (id),
    CONSTRAINT fk_pmg_group FOREIGN KEY (modifier_group_id) REFERENCES modifier_group (id)
);

CREATE TABLE product_branch_availability (
    id             UUID          NOT NULL,
    version        BIGINT        NOT NULL,
    branch_id      UUID          NOT NULL,
    product_id     UUID          NOT NULL,
    available      BOOLEAN       NOT NULL DEFAULT TRUE,
    price_override NUMERIC(19,4),
    price_currency VARCHAR(3),
    created_at     TIMESTAMPTZ   NOT NULL,
    created_by     VARCHAR(255),
    updated_at     TIMESTAMPTZ   NOT NULL,
    updated_by     VARCHAR(255),
    CONSTRAINT pk_product_branch_availability PRIMARY KEY (id),
    CONSTRAINT uk_pba_product_branch UNIQUE (product_id, branch_id),
    CONSTRAINT fk_pba_product FOREIGN KEY (product_id) REFERENCES product (id),
    CONSTRAINT fk_pba_branch FOREIGN KEY (branch_id) REFERENCES branch (id)
);

CREATE TABLE recipe (
    id         UUID        NOT NULL,
    version    BIGINT      NOT NULL,
    product_id UUID        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    created_by VARCHAR(255),
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by VARCHAR(255),
    CONSTRAINT pk_recipe PRIMARY KEY (id),
    CONSTRAINT uk_recipe_product UNIQUE (product_id),
    CONSTRAINT fk_recipe_product FOREIGN KEY (product_id) REFERENCES product (id)
);

CREATE TABLE recipe_line (
    id            UUID          NOT NULL,
    version       BIGINT        NOT NULL,
    recipe_id     UUID,
    ingredient_id UUID          NOT NULL,
    quantity      NUMERIC(19,4) NOT NULL,
    unit          VARCHAR(16)   NOT NULL,
    CONSTRAINT pk_recipe_line PRIMARY KEY (id),
    CONSTRAINT fk_recipe_line_recipe FOREIGN KEY (recipe_id) REFERENCES recipe (id)
);

CREATE INDEX idx_category_company_id ON category (company_id);
CREATE INDEX idx_product_company_id ON product (company_id);
CREATE INDEX idx_product_category_id ON product (category_id);
CREATE INDEX idx_modifier_group_id ON modifier (modifier_group_id);
CREATE INDEX idx_mrl_modifier_id ON modifier_recipe_line (modifier_id);
CREATE INDEX idx_recipe_line_recipe_id ON recipe_line (recipe_id);
CREATE INDEX idx_pba_product_id ON product_branch_availability (product_id);
