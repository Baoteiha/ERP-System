# Verticals ship as services behind one entitlement-gated frontend shell

An industry vertical (first: `fnb-catalog` — menu, modifiers, recipes-as-BOM)
is its own backend service, and its UI is a lazy-loaded Angular feature module
inside the single shell application, switched per tenant by module
entitlements held in `iam-tenancy`. One frontend build serves every tenant;
what a tenant sees is decided at runtime by entitlement, not by deployment.

## Considered options

Microfrontends (module federation) would let vertical UIs deploy
independently, matching the backend split — but for a solo operator they add a
second distributed system (frontend versioning, shared-dependency management,
runtime composition) with none of the backend's payoff. Entitlement-gated lazy
modules deliver the per-tenant experience at monolith-frontend cost.

## Consequences

- Entitlements are a first-class concept in `iam-tenancy`: per-company module
  activation, enforced backend-side beside permissions (an endpoint's
  permission cannot be held if its module is not licensed) and frontend-side
  by route/menu gating.
- The core must never reference a vertical: verticals subscribe to core
  events and call core APIs (ADR-0004), never the reverse.
- Adding a vertical = new service + new Angular feature module + entitlement
  row; the acceptance test for the core is that this requires zero core
  changes (transformation plan, Phase 5).
