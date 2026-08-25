# Tenant isolation: shared schema + RLS inside every service database

Each service owns its database (no cross-service joins); within each database,
all tenants share the tables, scoped by `company_id` with PostgreSQL row-level
security as the enforcement backstop — the model already proven on
`ingredient` (V7) — plus company-scoped repository methods in the application
layer.

## Considered options

Schema-per-tenant and database-per-tenant offer harder walls and per-tenant
restore, but multiply Flyway runs and connection routing by tenant count — and
multiplied again by service count under ADR-0002, which is operationally
untenable for a solo operator. A dedicated-database premium tier can be added
later for a compliance-bound customer; the `company_id` discipline on every
row is what keeps that extraction possible.

## Consequences

- The tenant id travels in the access token and is set per transaction
  (`app.company_id`) in every service; policies follow the V7_002 pattern.
- Requires the non-superuser application role everywhere — superusers bypass
  RLS, so this is a standing prerequisite, not a dev-only concern.
- Cross-tenant analytics (if ever offered) must be built as explicit,
  policy-exempt read models, never ad-hoc queries.
