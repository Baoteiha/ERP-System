# ERP Transformation Plan — ERP-Cafe → general business ERP

**Status:** proposed roadmap, 2026-08-24.
**Working assumptions** (revise here if wrong):

1. The target is a **multi-tenant SaaS product** — many customer companies on one
   deployment — not an internal tool for one business. The existing
   company/branch tenancy model already points this way.
2. **Solo developer**, Vietnamese market first (VND, Zalo in the inventory
   spec), agent-assisted workflow (spec → tickets → implement per slice).
3. F&B (the café) remains the **first vertical** and the proving ground; the
   transformation extracts a general core underneath it rather than rewriting.

## 1. Strategy in one paragraph

Do not rewrite. The target is **microservices** (ADR-0002), reached by
**staged strangler extraction** from the working modular monolith — its module
seams (`api/` facades, package-per-context) become service boundaries.
Stabilize what exists, harden tenancy to enterprise grade, swap identity for
an IdP, then extract services in coupling order and split core from
**vertical services**, with per-tenant module entitlements (ADR-0005). New
verticals then become sibling services, not forks. Sequence: **security →
correctness → extraction/generalization → new capability.**
Each phase lands via the established flow (`/to-spec` → `/to-tickets` →
`/implement`), one tracer-bullet ticket at a time, so any phase can pause
without leaving the system broken.

## 2. What we're starting from

### Generalizes as-is (the future core)

| Module | Notes |
|---|---|
| `organization` | company/branch is the ERP backbone; "branch" may become "location" |
| `identity` | RBAC + per-branch grants; roles-as-data confirmed (custom roles are a core enterprise need) |
| `inventory` | stock ledger, moving-average cost, movements — standard ERP inventory; `Unit`/`Dimension` closed vocabulary (ADR-0001) |
| `purchasing` | supplier / PO / receiving — fully general |
| `sales` | order-to-cash skeleton — general |
| `staff` | employees, shifts, labor cost — general HR-lite |
| `reporting` | cross-module composition over `api/` facades — general |
| `common` | Money, Unit, Dimension, auditing, optimistic locking, BranchContext |

### Café-specific (the first vertical)

- `catalog`: menu, products, **modifiers** (F&B/POS-specific), **recipes**
  (an F&B **bill of materials** — the general concept underneath is BOM).
- POS-flavored flows: shift clock on shared terminals, cashier role shape.

### Known debts the plan must clear (from this session's reviews)

- **Live:** `StockLedgerService.normalize` sums quantities across units
  (stock/COGS corruption); G06 permission backfill (managers 403 after
  upgrade); staff/report perms OWNER-only sync.
- **Uncommitted:** `Item` feature + V8/V9 migrations (V8 blocked by RLS
  `FORCE` + non-superuser role); `Unit.convert` has no production caller;
  receiving doesn't convert; blank-SKU 500; update DTOs silently discard
  fields; ingredient delete orphans items. Domain docs (CONTEXT.md,
  ADR-0001, spec edits) also uncommitted.
- **Auth spec §9:** G07–G14 (permission union across branches, revocation
  lag, static HS256 secret, no rate limiting/lockout/reset, no audit trail).

## 3. Phases

### Phase 0 — Stabilize (finish what's in flight)

*Goal: a green, committed, honest baseline. No generalization on a bleeding base.*

- Commit the domain-modeling docs (CONTEXT.md, ADR-0001, spec edits).
- Fix `normalize`: convert via `Unit` before merging; ledger honours
  `StockLine.unit`. (Settle the open unit-grilling questions: base-unit =
  canonical per dimension? conversion at the ledger seam? — record as ADR.)
- Fix V8 migration (`SET LOCAL row_security = off` for the canonicalisation
  UPDATE) and land the `Item` feature: receiving converts through the item's
  purchase unit; PO lines snapshot (itemId nullable + unit NOT NULL);
  blank-SKU normalisation; reject silently-ignored update fields.
- G06: generalize `syncOwnerPermissions` → add-only reconciliation of **all**
  default roles against `DefaultRole.permissions()`.
- Map `DataIntegrityViolationException` → 409.

**Exit:** full suite green, working tree clean, inventory spec statuses true.

### Phase 1 — Enterprise tenancy (make the isolation structural)

*Goal: cross-tenant access becomes unwritable, not just fixed. This is the
enterprise table-stakes phase — G01–G05 fixes were point repairs; this makes
the class impossible.*

- **Non-superuser app DB role** (migration + config). Prerequisite for
  everything RLS; without it, policies are theatre.
- Move `TenantRlsAspect` → `common`, widen pointcut to all application
  services; RLS policies (V7_002 pattern) on **every** tenant table —
  company-scoped tables on `company_id`, branch-scoped via their branch.
- **Scoped repository base types** (`CompanyScopedRepository`,
  `BranchScopedRepository`) that do not expose bare `findById`/`findAll`;
  migrate all repositories.
- **ArchUnit guardrails:** every endpoint has `@PreAuthorize` with a string
  from `Permissions.ALL`; no repository outside the scoped base types; unique
  test-data prefixes.
- **Adopt Spring Modulith** (ADR-0004): boundary verification tests over the
  existing packages, per-module integration tests, event registry wired —
  the seams every later extraction will cut along.
- One seam-level isolation test: seed two companies, assert every scoped
  repository returns nothing cross-tenant (replaces per-service tests).

**Exit:** RLS active in dev (non-superuser) and CI; an unscoped query fails a
build or returns zero foreign rows at runtime.

### Phase 2 — Enterprise identity (Zitadel)

*Goal: SSO, MFA, passkeys, login audit — the four drivers already identified.
Self-hosted; availability engineering is part of the phase, not an afterthought.*

1. Stand up Zitadel (HA posture + backup); map **Zitadel Organization ↔
   Company**.
2. Backend → OAuth2 **resource server**: `JwtService` validates instead of
   mints; `RefreshToken` deleted; `User` becomes a projection keyed by IdP
   subject, provisioned on first login. `Role`/`Permission`/`UserBranchAccess`
   **stay local** (business data, not identity).
3. **Two auth flows:** back-office OIDC (MFA, passkeys) vs **terminal
   sessions** for POS (device-bound terminal auth + per-cashier PIN; must
   survive a short IdP outage on an already-open till).
4. Per-organization **SSO federation** for the customer that asks; login/audit
   events from Zitadel's log + local domain audit.
5. Resolve G07 here: evaluate effective permissions **per active branch**
   (claims can no longer be a flat union) — design decision, record as ADR.

**Exit:** password login retired; G08–G14 closed or explicitly accepted.

### Phase 3 — Extraction + core/vertical split (the actual transformation)

*Goal: an industry-agnostic core any business can run, with F&B as the first
vertical **service**. Services leave the monolith in coupling order —
reporting → staff → supply → sales/`fnb-catalog` last (ADR-0002) — each one
first hardened as a Modulith module, then externalized (gateway route, own
database, events over RabbitMQ).*

- Introduce **module entitlements**: per-company activation of modules
  (an F&B tenant sees catalog/recipes; a services tenant doesn't). Entitlement
  checks live beside `@PreAuthorize` (a permission you can't hold if the
  module isn't licensed).
- Generalize vocabulary (multi-context `CONTEXT-MAP.md`): core contexts
  (organization, identity, inventory, purchasing, sales, staff, reporting) +
  `verticals/fnb` (menu, modifiers, recipe→**BOM** specialization). Recipe
  explosion becomes the F&B adapter of a general BOM/consumption seam on
  `InventoryApi`.
- Rename judiciously where café language leaks into core (branch→location is
  the main candidate); keep table names stable, rename at the API/UI layer
  first.
- Custom-roles UI (roles-as-data is now load-bearing); role templates per
  vertical.
- Missing enterprise-core capabilities, added *behind facades, minimal first*:
  GL export (INV-707 shape: period COGS + sales journal, consumable by an
  accounting system — **not** a full GL), AR/AP aging from orders/POs,
  document attachments, domain-event **audit trail** (append-only, per
  company).

**Exit:** a second tenant can run with the F&B module off and nothing café
shows anywhere; the café tenant is unchanged.

### Phase 4 — Platform & operations (SaaS-grade)

- CI (full suite + ArchUnit on PR), staging env, HA Postgres + tested
  restore, observability (metrics/tracing/structured logs per tenant),
  rate limiting at the edge.
- Public API versioning policy (`/api/v1` discipline), webhooks for
  integration (order completed, stock low, PO received), bulk import/export
  (the migration path *into* the product for real customers).
- i18n (vi/en) across frontend + error messages; VND/multi-currency decision
  for `Money` (currency exists — decide whether to activate it).
- Subscription/billing per company + entitlements admin.

### Phase 5 — Second vertical (prove the core)

Pick the nearest neighbor — **retail** (same POS shape, no recipes/BOM,
barcode + simple SKU sales) — and build it as a module against the core with
no core changes as the acceptance test. Where the core bends, that's the
finding; fix the seam, not the vertical. (Restaurant chains, mini-marts, and
pharmacies are the Vietnamese SME candidates; retail stresses the least.)

## 4. Decisions already made (do not relitigate)

- Target runtime is microservices via staged extraction from the monolith
  (ADR-0002): `iam-tenancy`, `supply`, `sales`, `staff`, `reporting`, one
  service per vertical; extraction order reporting → staff → supply →
  sales/catalog split last. Security phases complete in the monolith first.
- Tenancy: shared schema + `company_id` + RLS inside every service database
  (ADR-0003); dedicated-DB premium tier deferred.
- Spring Modulith modules + transactional event registry as the extraction
  seam (ADR-0004): sync facades for queries, domain events for facts,
  RabbitMQ once externalized; no distributed transactions, idempotent
  consumers.
- Verticals ship as services behind one entitlement-gated Angular shell —
  no microfrontends (ADR-0005).
- Roles are **data** (custom roles are core); permission *catalog* is code.
- No per-ingredient unit conversion table (ADR-0001); packaging is not a unit.
- PO lines **snapshot** quantity/unit/cost; itemId is provenance.
- Tenancy enforcement is layered: scoped repositories (L1) + RLS (L2);
  filters establish context, the data layer enforces it.
- Zitadel over Keycloak/OpenFGA; org-per-company; grants stay local.

## 5. Open questions (answer before the phase that needs them)

| Question | Needed by |
|---|---|
| Confirm assumption 1 (SaaS product vs internal tool) | now — shapes P4 |
| Base unit restricted to canonical (g/ml/pc) + data rescale? | P0 (`normalize` fix) |
| Receipt in a different unit than ordered — allowed? | P0 (`Item` landing) |
| Per-branch permission evaluation design (G07) | P2 |
| branch→location rename depth (API only vs schema) | P3 |
| Multi-currency activation | P4 |
| Second vertical choice | P5 |

## 6. Rhythm

One phase = one `/to-spec` → `/to-tickets` cycle; tickets are tracer bullets
worked blockers-first; `/implement` + `/code-review` per ticket; commit format
`[Action] Feature`. Phases 0–1 are weeks; 2–3 are the heart of the
transformation; 4–5 follow demand. Anything cut from a phase is recorded here,
not silently dropped.
