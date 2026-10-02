-- The application connects as erp_app, a role row-level security binds, and every table a
-- company owns is filtered by the company each transaction begins in (CMN-6, INV-7, INV-17).
--
-- Two database identities from here on:
--   * the schema owner (spring.flyway.user) runs Flyway, and the system work that must see every
--     company before one is known: login, refresh, logout, startup seeding, the cross-company
--     email and company-code checks, and the refresh-token purge. It must own the schema and its
--     tables and hold BYPASSRLS (or be a superuser): V8 turns row_security off, which only such a
--     role may do on a FORCE ROW LEVEL SECURITY table, and the system work must not be filtered.
--     It also needs CREATEROLE, unless erp_app was created beforehand (docker/postgres-init does so
--     for dev).
--   * erp_app (spring.datasource.*) does all request work, as NOSUPERUSER NOBYPASSRLS. The app
--     sets app.company_id at the start of every transaction (TenantTransactionManager). A role
--     belongs to the whole PostgreSQL cluster, not one database: run one cluster per environment.
--
-- erp_app is created here only if missing, with the password the app connects with (the
-- appRolePassword placeholder, which application.yml takes from spring.datasource.password; a
-- Flyway run outside the app must supply it). Every check runs first, in a statement without the
-- password, so a misconfigured deploy fails before the password is sent: PostgreSQL logs a failed
-- statement in full. The placeholder may be a SCRAM verifier ('SCRAM-SHA-256$...', which PostgreSQL
-- stores as given), so that even an echo exposes only a hash. A password containing $pw$ would end
-- its dollar quote early. A role that already exists keeps its password, and is refused if it could
-- bypass the policies, itself or through a role it can become.
--
-- Which rows a company sees, table by table (a policy with only USING also checks written rows,
-- so a row can be neither read nor written outside the company):
--   * a table with company_id: the rows of the company in force (company itself: its own row);
--   * a branch-owned table: the rows whose branch is visible;
--   * any other table: the rows whose parent row is visible (a line through its order, a
--     modifier through its group, a refresh token through its user).
-- Each table follows that one path. Foreign keys are checked past row-level security, so a row
-- that names another company's row in some other column (a stock row's ingredient, a grant's role)
-- is kept out by the application's own checks, not by these policies.
-- With no company in force NULLIF yields NULL and nothing matches: the policies fail closed.
-- Two tables have no policy: permission is one catalog for every company (erp_app may only read
-- it; startup syncs it as the owner) and the Flyway history, which erp_app cannot touch.

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = current_user AND (rolsuper OR rolbypassrls)) THEN
        RAISE EXCEPTION 'Flyway runs as %, which lacks BYPASSRLS: run it as the schema owner with BYPASSRLS',
            current_user;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'erp_app') THEN
        IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = current_user AND (rolsuper OR rolcreaterole)) THEN
            RAISE EXCEPTION 'erp_app does not exist and % lacks CREATEROLE: create erp_app first, or grant CREATEROLE',
                current_user;
        END IF;
    ELSIF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'erp_app' AND (rolsuper OR rolbypassrls)) THEN
        RAISE EXCEPTION 'erp_app is a superuser or holds BYPASSRLS, so no policy would apply to it';
    ELSIF EXISTS (SELECT 1 FROM pg_roles r
                  WHERE r.rolname <> 'erp_app'
                    AND (r.rolsuper OR r.rolbypassrls OR r.rolname = current_user)
                    AND pg_has_role('erp_app', r.oid, 'MEMBER')) THEN
        RAISE EXCEPTION 'erp_app is a member of the schema owner or of a role that bypasses row-level security';
    END IF;
END $$;

-- Runs only once the checks above have passed. Dollar-quoted, so any password is a literal; Flyway
-- misses a placeholder written right after a '$', hence the leading '-', which substr drops.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'erp_app') THEN
        EXECUTE format('CREATE ROLE erp_app LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE PASSWORD %L',
            substr($pw$-${appRolePassword}$pw$, 2));
    END IF;
END $$;

-- PostgreSQL 15 already withholds CREATE on the schema from everyone; older versions did not.
REVOKE CREATE ON SCHEMA ${flyway:defaultSchema} FROM PUBLIC;
GRANT USAGE ON SCHEMA ${flyway:defaultSchema} TO erp_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA ${flyway:defaultSchema} TO erp_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA ${flyway:defaultSchema} TO erp_app;
REVOKE ALL ON ${flyway:defaultSchema}.${flyway:table} FROM erp_app;
REVOKE INSERT, UPDATE, DELETE ON permission FROM erp_app;

-- Tables later migrations create get the same grants, but no policy: each needs its own.
ALTER DEFAULT PRIVILEGES IN SCHEMA ${flyway:defaultSchema}
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO erp_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA ${flyway:defaultSchema}
    GRANT USAGE, SELECT ON SEQUENCES TO erp_app;

-- ingredient (V7) and item (V9) already have this policy.
ALTER TABLE company ENABLE ROW LEVEL SECURITY;
ALTER TABLE company FORCE ROW LEVEL SECURITY;
CREATE POLICY company_tenant_isolation ON company
    USING (id = NULLIF(current_setting('app.company_id', true), '')::uuid);

ALTER TABLE branch ENABLE ROW LEVEL SECURITY;
ALTER TABLE branch FORCE ROW LEVEL SECURITY;
CREATE POLICY branch_tenant_isolation ON branch
    USING (company_id = NULLIF(current_setting('app.company_id', true), '')::uuid);

ALTER TABLE role ENABLE ROW LEVEL SECURITY;
ALTER TABLE role FORCE ROW LEVEL SECURITY;
CREATE POLICY role_tenant_isolation ON role
    USING (company_id = NULLIF(current_setting('app.company_id', true), '')::uuid);

ALTER TABLE app_user ENABLE ROW LEVEL SECURITY;
ALTER TABLE app_user FORCE ROW LEVEL SECURITY;
CREATE POLICY app_user_tenant_isolation ON app_user
    USING (company_id = NULLIF(current_setting('app.company_id', true), '')::uuid);

ALTER TABLE category ENABLE ROW LEVEL SECURITY;
ALTER TABLE category FORCE ROW LEVEL SECURITY;
CREATE POLICY category_tenant_isolation ON category
    USING (company_id = NULLIF(current_setting('app.company_id', true), '')::uuid);

ALTER TABLE modifier_group ENABLE ROW LEVEL SECURITY;
ALTER TABLE modifier_group FORCE ROW LEVEL SECURITY;
CREATE POLICY modifier_group_tenant_isolation ON modifier_group
    USING (company_id = NULLIF(current_setting('app.company_id', true), '')::uuid);

ALTER TABLE product ENABLE ROW LEVEL SECURITY;
ALTER TABLE product FORCE ROW LEVEL SECURITY;
CREATE POLICY product_tenant_isolation ON product
    USING (company_id = NULLIF(current_setting('app.company_id', true), '')::uuid);

ALTER TABLE supplier ENABLE ROW LEVEL SECURITY;
ALTER TABLE supplier FORCE ROW LEVEL SECURITY;
CREATE POLICY supplier_tenant_isolation ON supplier
    USING (company_id = NULLIF(current_setting('app.company_id', true), '')::uuid);

ALTER TABLE employee ENABLE ROW LEVEL SECURITY;
ALTER TABLE employee FORCE ROW LEVEL SECURITY;
CREATE POLICY employee_tenant_isolation ON employee
    USING (company_id = NULLIF(current_setting('app.company_id', true), '')::uuid);

-- shift is branch-owned too, but has carried its company since V20
ALTER TABLE shift ENABLE ROW LEVEL SECURITY;
ALTER TABLE shift FORCE ROW LEVEL SECURITY;
CREATE POLICY shift_tenant_isolation ON shift
    USING (company_id = NULLIF(current_setting('app.company_id', true), '')::uuid);

-- Branch-owned: the uncorrelated subquery is the company's few branches, read once per statement.
ALTER TABLE user_branch_access ENABLE ROW LEVEL SECURITY;
ALTER TABLE user_branch_access FORCE ROW LEVEL SECURITY;
CREATE POLICY user_branch_access_tenant_isolation ON user_branch_access
    USING (branch_id IN (SELECT b.id FROM branch b));

ALTER TABLE product_branch_availability ENABLE ROW LEVEL SECURITY;
ALTER TABLE product_branch_availability FORCE ROW LEVEL SECURITY;
CREATE POLICY product_branch_availability_tenant_isolation ON product_branch_availability
    USING (branch_id IN (SELECT b.id FROM branch b));

ALTER TABLE stock_item ENABLE ROW LEVEL SECURITY;
ALTER TABLE stock_item FORCE ROW LEVEL SECURITY;
CREATE POLICY stock_item_tenant_isolation ON stock_item
    USING (branch_id IN (SELECT b.id FROM branch b));

ALTER TABLE stock_movement ENABLE ROW LEVEL SECURITY;
ALTER TABLE stock_movement FORCE ROW LEVEL SECURITY;
CREATE POLICY stock_movement_tenant_isolation ON stock_movement
    USING (branch_id IN (SELECT b.id FROM branch b));

ALTER TABLE purchase_order ENABLE ROW LEVEL SECURITY;
ALTER TABLE purchase_order FORCE ROW LEVEL SECURITY;
CREATE POLICY purchase_order_tenant_isolation ON purchase_order
    USING (branch_id IN (SELECT b.id FROM branch b));

ALTER TABLE sales_order ENABLE ROW LEVEL SECURITY;
ALTER TABLE sales_order FORCE ROW LEVEL SECURITY;
CREATE POLICY sales_order_tenant_isolation ON sales_order
    USING (branch_id IN (SELECT b.id FROM branch b));

ALTER TABLE ledger_reference ENABLE ROW LEVEL SECURITY;
ALTER TABLE ledger_reference FORCE ROW LEVEL SECURITY;
CREATE POLICY ledger_reference_tenant_isolation ON ledger_reference
    USING (branch_id IN (SELECT b.id FROM branch b));

-- Children: the correlated subquery is one primary-key lookup of the parent per row.
ALTER TABLE role_permission ENABLE ROW LEVEL SECURITY;
ALTER TABLE role_permission FORCE ROW LEVEL SECURITY;
CREATE POLICY role_permission_tenant_isolation ON role_permission
    USING (EXISTS (SELECT 1 FROM role p WHERE p.id = role_permission.role_id));

ALTER TABLE refresh_token ENABLE ROW LEVEL SECURITY;
ALTER TABLE refresh_token FORCE ROW LEVEL SECURITY;
CREATE POLICY refresh_token_tenant_isolation ON refresh_token
    USING (EXISTS (SELECT 1 FROM app_user p WHERE p.id = refresh_token.user_id));

ALTER TABLE modifier ENABLE ROW LEVEL SECURITY;
ALTER TABLE modifier FORCE ROW LEVEL SECURITY;
CREATE POLICY modifier_tenant_isolation ON modifier
    USING (EXISTS (SELECT 1 FROM modifier_group p WHERE p.id = modifier.modifier_group_id));

ALTER TABLE modifier_recipe_line ENABLE ROW LEVEL SECURITY;
ALTER TABLE modifier_recipe_line FORCE ROW LEVEL SECURITY;
CREATE POLICY modifier_recipe_line_tenant_isolation ON modifier_recipe_line
    USING (EXISTS (SELECT 1 FROM modifier p WHERE p.id = modifier_recipe_line.modifier_id));

ALTER TABLE product_modifier_group ENABLE ROW LEVEL SECURITY;
ALTER TABLE product_modifier_group FORCE ROW LEVEL SECURITY;
CREATE POLICY product_modifier_group_tenant_isolation ON product_modifier_group
    USING (EXISTS (SELECT 1 FROM product p WHERE p.id = product_modifier_group.product_id));

ALTER TABLE recipe ENABLE ROW LEVEL SECURITY;
ALTER TABLE recipe FORCE ROW LEVEL SECURITY;
CREATE POLICY recipe_tenant_isolation ON recipe
    USING (EXISTS (SELECT 1 FROM product p WHERE p.id = recipe.product_id));

ALTER TABLE recipe_line ENABLE ROW LEVEL SECURITY;
ALTER TABLE recipe_line FORCE ROW LEVEL SECURITY;
CREATE POLICY recipe_line_tenant_isolation ON recipe_line
    USING (EXISTS (SELECT 1 FROM recipe p WHERE p.id = recipe_line.recipe_id));

ALTER TABLE purchase_order_line ENABLE ROW LEVEL SECURITY;
ALTER TABLE purchase_order_line FORCE ROW LEVEL SECURITY;
CREATE POLICY purchase_order_line_tenant_isolation ON purchase_order_line
    USING (EXISTS (SELECT 1 FROM purchase_order p WHERE p.id = purchase_order_line.purchase_order_id));

ALTER TABLE order_line ENABLE ROW LEVEL SECURITY;
ALTER TABLE order_line FORCE ROW LEVEL SECURITY;
CREATE POLICY order_line_tenant_isolation ON order_line
    USING (EXISTS (SELECT 1 FROM sales_order p WHERE p.id = order_line.order_id));

ALTER TABLE order_line_modifier ENABLE ROW LEVEL SECURITY;
ALTER TABLE order_line_modifier FORCE ROW LEVEL SECURITY;
CREATE POLICY order_line_modifier_tenant_isolation ON order_line_modifier
    USING (EXISTS (SELECT 1 FROM order_line p WHERE p.id = order_line_modifier.order_line_id));

ALTER TABLE payment ENABLE ROW LEVEL SECURITY;
ALTER TABLE payment FORCE ROW LEVEL SECURITY;
CREATE POLICY payment_tenant_isolation ON payment
    USING (EXISTS (SELECT 1 FROM sales_order p WHERE p.id = payment.order_id));
