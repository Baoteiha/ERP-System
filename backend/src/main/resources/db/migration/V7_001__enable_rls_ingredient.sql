-- Layer 2 (prototype): database-enforced tenant isolation on `ingredient` via
-- PostgreSQL Row-Level Security. Every statement is automatically filtered by the
-- per-transaction setting `app.company_id`, which the application sets from the
-- authenticated user's company. This closes the whole class of "forgot to scope a
-- query" bugs (IDOR) at the database, not just in application code.
--
-- IMPORTANT: superusers and the table owner bypass RLS. FORCE makes the owner
-- subject to it too, but superusers ALWAYS bypass — so in production the app must
-- connect as a NON-superuser role for this to take effect.

ALTER TABLE ingredient ENABLE ROW LEVEL SECURITY;
ALTER TABLE ingredient FORCE ROW LEVEL SECURITY;

-- current_setting('app.company_id', true): the second arg (missing_ok) returns NULL
-- when the setting is absent, so an unset tenant matches NO rows (fail-closed)
-- instead of raising "unrecognized configuration parameter".
CREATE POLICY ingredient_tenant_isolation ON ingredient
    USING      (company_id = current_setting('app.company_id', true)::uuid)
    WITH CHECK (company_id = current_setting('app.company_id', true)::uuid);
