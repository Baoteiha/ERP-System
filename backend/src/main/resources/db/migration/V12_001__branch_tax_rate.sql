ALTER TABLE branch ADD COLUMN tax_rate NUMERIC(5,4) NOT NULL DEFAULT 0 CHECK (tax_rate >= 0 AND tax_rate <= 1);
