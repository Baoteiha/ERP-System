-- Staff API ready for extraction: shifts carry their own company so labor cost can be
-- scoped without joining another module's tables, and employees may hold a hashed PIN
-- for clocking. The foreign keys to company/branch go: those tables belong to the
-- organization module, which a future staff service will not share a database with.
-- Indexes stay.

ALTER TABLE shift ADD COLUMN company_id UUID;

UPDATE shift SET company_id = (SELECT e.company_id FROM employee e WHERE e.id = shift.employee_id);

ALTER TABLE shift ALTER COLUMN company_id SET NOT NULL;

CREATE INDEX idx_shift_company_branch_clock_out ON shift (company_id, branch_id, clock_out_at);

ALTER TABLE employee ADD COLUMN pin_hash VARCHAR(100);

ALTER TABLE employee DROP CONSTRAINT fk_employee_company;
ALTER TABLE shift DROP CONSTRAINT fk_shift_branch;
