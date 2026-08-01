-- Harden the ingredient RLS policy against the empty-string case.
--
-- A PostgreSQL placeholder setting (like app.company_id) that has been SET LOCAL in
-- a transaction and then reverted reads back as '' (empty string), not NULL, for the
-- rest of that connection's life. '' :: uuid raises "invalid input syntax for type
-- uuid", which would make queries ERROR instead of failing closed. NULLIF maps '' to
-- NULL first, so an absent OR blank tenant simply matches no rows.
ALTER POLICY ingredient_tenant_isolation ON ingredient
    USING      (company_id = NULLIF(current_setting('app.company_id', true), '')::uuid)
    WITH CHECK (company_id = NULLIF(current_setting('app.company_id', true), '')::uuid);
