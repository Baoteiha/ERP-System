CREATE TABLE ledger_reference (
    ref_type   VARCHAR(32)  NOT NULL,
    ref_id     UUID         NOT NULL,
    branch_id  UUID         NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (ref_type, ref_id)
);
