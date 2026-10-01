# The tenant seam: Hibernate's `@TenantId` scopes every company-owned row

Every row a **Company** owns carries `company_id`, and the application no
longer scopes it by hand. Company-owned entities extend `TenantOwnedEntity`
(`@TenantId @Column(name = "company_id")`): branch, category, product,
modifier_group, ingredient, supplier, item, role, app_user and employee.
`Shift` is branch-scoped but has carried its company since V20, so its own
`company_id` is a `@TenantId` too. Branch-scoped tables without `company_id`
(orders, stock, purchase orders) keep their **Branch** scoping
(`findByIdAndBranchId`).

How the company reaches Hibernate:

- `TenantContext` holds the company the current thread acts for.
  `JwtAuthenticationFilter` is the request edge: it sets the access token's
  company (`startRequest`, its only caller) before any lookup — the grant
  cache already reads branches through it — and clears the scope in `finally`
  on every path: authenticated, anonymous, and every early 400/401/403. A
  container thread never legitimately brings a scope in, so one found on
  arrival was leaked by earlier work; it is logged (WARN) and cleared before
  the request runs, so not even an anonymous request reads or stamps as it.
  Elsewhere, scopes nest only through `runAs`/`runAsSystem`, which restore
  what they interrupted. `require()` answers 401 without a company. The two
  persistence identifiers below are never a company: `startRequest` and
  `runAs` refuse them, and a token naming one is refused (401), so a request
  can reach neither the system scope nor the blind one by its data.
- `TenantIdentifierResolver` gives that company to each Hibernate session when
  it opens; `HibernateTenantConfig` registers it
  (`hibernate.tenant_identifier_resolver`).
- **Reads.** Hibernate adds `company_id = :tenant` to every HQL/Criteria query
  and, in Hibernate 7, to id lookups as well: `findById`, `existsById` and
  `findAllById` of another company's row come back empty
  (`TenantSeamTest`), so no scoping repository base class is needed. Another
  company's id is simply not found → 404. A query rooted at a tenant entity
  scopes what it selects through it: a modifier is found through its group
  (`ModifierRepository.findThroughGroup`).
- **Writes.** A new row takes its company from `TenantContext` at persist, so
  `getCompanyId()` is readable at once (an event published in the same
  transaction carries it). Hibernate checks at insert that it is the session's
  company and refuses any other ("assigned tenant id differs from current
  tenant id"); `company_id` is not updatable and has no setter. A tenant-owned
  row is therefore only ever written inside its own company — outside one, the
  insert is refused.
- **No tenant.** A session opened with no company gets an identifier that
  matches none (`TenantContext.NO_TENANT_ID`): it sees no tenant-owned row.
  The seam fails closed.
- **System scope.** `TenantContext.runAsSystem` gives the session a root
  identifier (`TenantContext.SYSTEM_TENANT_ID`), for which `isRoot` lifts the
  filter, and its connection is the schema owner's, which row-level security
  does not filter (Task 31). Main code enters it only through
  `SystemTransactions`, for work that runs before any company is known or must
  see every company: login, refresh and logout (`AuthService`: the user by
  email, the refresh token by its hash), startup (`DataInitializer`: the
  permission catalog sync across every company), the cross-company uniqueness
  checks of a user's email and a company's code, and the refresh-token purge.
  Every query there names its row (email, token hash, user id, company id,
  code). The first company's own rows are seeded inside that company
  (`TenantContext.runAs`).

A session takes its tenant when it opens, which Spring does at transaction
begin. `runAsSystem`/`runAs` must therefore wrap a transaction, never run inside
one: `AuthService` and `DataInitializer` open theirs with a `TransactionTemplate`
inside the scope. For the same reason `spring.jpa.open-in-view: false` is
load-bearing: open-in-view binds one session to the whole request as it enters
MVC, and every transaction in the request joins that session. Login and refresh
would then run on the request's session — which has no company — instead of
opening one in the system scope, and would find no user.

What the seam does not see is SQL Hibernate does not write. These stay
explicitly scoped, and row-level security (Task 31) is their backstop:

- `RoleRepository.lockByIdAndCompanyId` — `SELECT … FOR UPDATE` on `role`,
  names the company.
- `StockLedgerService` — `INSERT … ON CONFLICT` into `ledger_reference` and
  `stock_item`, both branch-scoped tables, keyed by the branch.
- `OrderRepository.netSalesByDay` — reads `sales_order` (branch-scoped) by the
  active branch.
- `TenantTransactionManager` — `set_config('app.company_id', …)` as each
  transaction begins.

One native query must see every company and has no backstop:
`UserRepository.existsByEmailInAnyCompany`. Login finds a user by email alone,
so an email is unique system-wide, and creating or renaming a user checks it
across companies. Under row-level security it would silently see only the
tenant's rows, and a duplicate would fall through to the unique index's generic
409; it therefore runs on the owner (BYPASSRLS) connection, through
`SystemTransactions`, as login, refresh and startup do.

Published module APIs that take a `companyId` (`OrganizationApi`, `StaffApi`,
`InventoryApi.validateConsumption`, `IngredientReferenceCheck`) keep it: a
caller may run as the system (login reads the user's live branches through
`listActiveBranches`). Inside a tenant the seam has already narrowed the rows
to that company; the named company still decides, so the answer is the same in
either scope.

## Considered options

- **`findByIdAndCompanyId` at every call site (before).** Each new query was a
  chance to forget the company, and the code review's IDORs were exactly that.
- **A plain Hibernate `@Filter`.** Opt-in per session: a session that never
  enables it sees every company. The `@TenantId` filter is enabled by Hibernate
  itself for every session, and a session cannot open without an identifier.
- **A scoping repository base class.** Only needed if `@TenantId` did not
  filter id lookups; in Hibernate 7 it does.
- **No company means every company.** Convenient for startup and tests, but
  any path that forgot the tenant would see all of them. Rejected: the system
  scope is named, narrow and explicit.
- **Row-level security alone.** It also covers native SQL, but needs the
  non-superuser role and a per-transaction setting (Task 31), and reports a
  forgotten scope only as missing rows in production. Both stay: the seam in
  the application, RLS as the backstop.

## Consequences

- Services no longer pass a company to repositories; a new query on a
  tenant-owned entity is scoped without anyone remembering to.
- Work handed to another thread (a pool, `@Async`) does not carry the tenant
  and sees nothing until it sets one (`TenantContext.runAs`).
- Tests that seed another company write inside it
  (`TenantContext.runAs(companyB, …)`) and read rows back as the system
  (`runAsSystem`). Never make a request inside such a block: MockMvc runs the
  JWT filter on the test thread, the filter treats the block's scope as a leak
  and clears it, and the rest of the block then runs with no company.
- Task 31 sets `app.company_id` from `TenantContext` at transaction begin and
  runs the system scope on a BYPASSRLS owner connection. Database work outside
  a transaction therefore sees no tenant rows, so every repository interface is
  `@Transactional(readOnly = true)`: a query called from outside a service
  (the grant cache in the JWT filter) still begins in its company.
- The remaining `CurrentUser` uses outside identity are for the user id and
  permissions, not the company (`IdentityApi.currentCompanyId` already reads
  `TenantContext`); Task 32 moves them behind `IdentityApi`.
