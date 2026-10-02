-- Hardens V22's guard on erp_app, which an existing role passes keeping whatever it holds. Refused here:
--   * an erp_app with CREATEROLE: it can give itself membership in other roles (before PostgreSQL 16,
--     in almost any), and so become one no policy binds;
--   * an erp_app that owns a table of the schema, itself or through a role it is a member of: a
--     table's owner may switch row-level security off for it, or drop its policies.
-- Either way no policy would bind the app. Nothing is changed: fix the role and run the migration again.
DO $$
DECLARE
    owned TEXT;
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'erp_app' AND rolcreaterole) THEN
        RAISE EXCEPTION 'erp_app holds CREATEROLE, so it could become a role row-level security does not bind: ALTER ROLE erp_app NOCREATEROLE';
    END IF;
    SELECT string_agg(format('%I (owner %I)', c.relname, pg_get_userbyid(c.relowner)), ', ' ORDER BY c.relname)
    INTO owned
    FROM pg_class c
    JOIN pg_namespace ns ON ns.oid = c.relnamespace
    WHERE ns.nspname = current_schema() AND c.relkind IN ('r', 'p')
      AND pg_has_role('erp_app', c.relowner, 'MEMBER');
    IF owned IS NOT NULL THEN
        RAISE EXCEPTION 'erp_app owns, itself or through a role it is a member of, these tables: %. It could switch row-level security off for them. Give each to the schema owner (ALTER TABLE ... OWNER TO the Flyway user), or take the owning role from erp_app (REVOKE <role> FROM erp_app)', owned;
    END IF;
END $$;
