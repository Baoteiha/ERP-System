# Code Review Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix every item in the 2026-09-30 module plan: close the cross-tenant holes, make money and stock correct, give the app one access policy, and make tenancy structural.

**Architecture:** Work lands on branch `fix/code-review` (off `dev` @ `e6fdc04`) in four phases that each leave the system working: stop the leaks → money and stock → access model → structural (tenant seam, RLS, events, boundaries). Each task is test-first against a real Postgres (Testcontainers) or, for the frontend, Vitest.

**Tech Stack:** Java 21, Spring Boot 4.1, Spring Security 7, Hibernate 7 / JPA, Flyway, PostgreSQL 16, Testcontainers, MapStruct; Angular 22 (standalone, zoneless, signals), TypeScript 6, Vitest (added in Task 6).

**Spec:** `erp-cafe-module-plan.html` (repo root, untracked) — 130 items with IDs such as `SAL-1`, each with why / where / test. Findings behind it: `erp-cafe-code-review.html`. Where this plan and the spec disagree, the spec's intent wins; rulings are recorded in the ledger.

## Global Constraints

- Branch `fix/code-review`. Never commit to `dev` or `main`, never push, never merge.
- Commit subject format `[Action] Feature` (e.g. `[Fix] Scope order lookups to the active branch`). **No `Co-Authored-By` trailer and no AI attribution of any kind.** One commit per logical fix; a task may have several commits.
- Stage only files you changed, by path. Never stage `.DS_Store`, `.claude/`, `.hermes/`, `frontend/.claude/`, `README.md`, `ERP-Cafe-Kanban.xlsx`, `docs/interview-guide.html`, `erp-cafe-code-review.html`, `erp-cafe-module-plan.html`. Never `git add -A` / `git add .`.
- Never touch `git stash` (the user's stash `stash@{0}` must survive).
- Test-first: write the failing test, run it and see it fail for the right reason, implement, run it green, then run the module's wider tests.
- Backend suite: `cd backend && ./gradlew test`. Until Task 31 the ONLY allowed failures are the two tests in `support/AppDatabaseRoleTest` (they prove RLS is not active yet). Any other failure is a regression you must fix. Targeted runs: `./gradlew test --tests '*SalesTenantIsolationTest'`. Docker must be running (Testcontainers).
- Frontend: `cd frontend && npx ng build` must pass. From Task 6 on, `npm test` (Vitest, single run) must pass too.
- Flyway: never edit an applied migration (V1…V10). New migrations use the next free major version at the time you write it (`ls backend/src/main/resources/db/migration`): `V<N>_001__<snake_description>.sql`. One major version per task.
- Tenant rule: every lookup by ID of tenant-owned data is scoped to the caller's company (company-scoped data) or active branch (branch-scoped data) and returns **404** for another tenant's row — never 403, never 422 "different company".
- Error semantics (from Task 2 on): 400 malformed/validation/missing branch header, 401 unauthenticated or bad credentials, 403 permission denied, 404 not found (including another tenant's row), 409 conflict/stale version/in-use, 422 business rule, 429 throttled. Every ProblemDetail body carries a string `code` property.
- Money: `BigDecimal`/`Money` only, `RoundingMode.HALF_EVEN`, never `double`/`float` in the backend. Currency VND. Monetary results that are stored are rounded to the `Money` scale already used by the entity.
- New classes get a Javadoc of exactly this form, and nothing more: `/** \n * Class: <Name>\n * Purpose: <one sentence>.\n */`. Do not rewrite existing Javadoc except where a task says a comment is wrong. Match the surrounding comment density; comments explain *why*, never narrate *what*.
- Domain vocabulary follows `CONTEXT.md` (Company, Branch, Ingredient, Item, Movement, base unit…).
- Integration tests extend `vn.essvn.erpcafe.support.AbstractIntegrationTest` (MockMvc + Testcontainers, profile `test`). The seeded OWNER is `admin@esscafe.vn` / `admin1234` (`adminToken()`). Seed another company directly through repositories, as `staff/StaffTenantIsolationTest#seedForeign` does, with unique suffixes from a static `AtomicInteger SEQ`.
- No new dependencies unless the task names them.
- Do not change behaviour outside the task's items. If you find another bug, note it in your report; do not fix it.

## Not in this plan

- **ID-12** (study notes in `SecurityConfig` / `SecurityProperties`): those edits are the user's own uncommitted work, stashed as `stash@{0}` before this branch was created. They are the user's to keep, move or drop. Task 2 edits `SecurityConfig`, so re-applying the stash may need a small manual merge.

## Review Focus

1. **Foreign IDs anywhere.** Any endpoint or published API method handed another company's or another branch's ID must answer 404 and change nothing. Isolation tests must assert the foreign row is unchanged, not only the status.
2. **VND rounding.** Tax and percentage discounts produce fractional đồng; every stored amount must be rounded HALF_EVEN at one place, and totals must add up exactly (grand = net + tax; net = gross − discounts).
3. **Two requests at once.** Two cashiers paying/completing the same order, two first sales of a new ingredient at a branch, two tabs refreshing one token: exactly one wins, the other gets a clean 409/401, never a 500 or a double effect.
4. **Legacy rows.** Existing data must survive every migration: PAID orders that were never completed, the old `subtotal` meaning, soft-deleted rows that collide with new partial unique indexes, orders without the new timestamps.
5. **Shared till.** Logout, login as someone else, switch branch: no token, branch ID or cached page data from the previous user or branch leaks into the next request.

Each line above is pinned by a test in the task that owns the code (Tasks 3, 9, 12, 8, 7 respectively), in addition to the task's own tests.

---

## Phase 1 — Stop the leaks

### Task 1: Fail fast on a missing profile and the dev JWT secret

Spec items: CMN-1, CMN-8.

**Files:**
- Modify: `backend/src/main/resources/application.yml` (remove `spring.profiles.active: dev`)
- Modify: `backend/build.gradle.kts` (bootRun defaults to the `dev` profile)
- Modify: `backend/src/main/resources/application-prod.yml` (`admin-password: ${ADMIN_PASSWORD:}`)
- Modify: `backend/src/main/java/vn/essvn/erpcafe/config/SecurityProperties.java` (`@Validated`, constraints)
- Create: `backend/src/main/java/vn/essvn/erpcafe/config/JwtSecretGuard.java`
- Modify: `backend/src/main/java/vn/essvn/erpcafe/bootstrap/DataInitializer.java` (blank admin password only fails when seeding an empty database)
- Test: `backend/src/test/java/vn/essvn/erpcafe/config/JwtSecretGuardTest.java`

**Interfaces:**
- Produces: `JwtSecretGuard` — a Spring bean that throws `IllegalStateException` at startup when the active profiles contain neither `dev` nor `test` AND the JWT secret equals the dev secret literal from `application-dev.yml` (`dev-erp-cafe-jwt-secret-change-me-in-production-0123456789`) or the test secret literal. Static helper `static void check(Collection<String> activeProfiles, String secret)` so it is unit-testable without Spring.
- `SecurityProperties.Jwt.secret`: `@NotBlank`, and at least 32 bytes UTF-8 (a custom check or `@Size(min = 32)` is acceptable since secrets are ASCII).

- [ ] **Step 1: Write failing tests** in `JwtSecretGuardTest` (plain JUnit 5, no Spring):
  - `check(List.of(), DEV_SECRET)` throws `IllegalStateException` whose message names the dev secret problem.
  - `check(List.of("prod"), DEV_SECRET)` throws.
  - `check(List.of("dev"), DEV_SECRET)` does not throw.
  - `check(List.of("test"), TEST_SECRET)` does not throw.
  - `check(List.of("prod"), "a-real-secret-of-at-least-32-bytes-long!!")` does not throw.
- [ ] **Step 2:** `cd backend && ./gradlew test --tests '*JwtSecretGuardTest'` → FAIL (class missing).
- [ ] **Step 3: Implement.** Remove the default profile from `application.yml`. In `build.gradle.kts` add `tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") { systemProperty("spring.profiles.active", System.getProperty("spring.profiles.active") ?: "dev") }` so `./gradlew bootRun` still starts in dev. Add `JwtSecretGuard` (reads `Environment.getActiveProfiles()` and `SecurityProperties`, runs in its constructor or `@PostConstruct`). Add `@Validated` + constraints to `SecurityProperties`. Make `ADMIN_PASSWORD` optional in prod and make `DataInitializer` throw a clear `IllegalStateException("app.security.bootstrap.admin-password is required to seed an empty database")` only when it actually needs to seed the first admin.
- [ ] **Step 4:** Targeted test green; full `./gradlew test` (only the two known failures).
- [ ] **Step 5: Commit** `[Fix] Fail fast on a missing profile and the dev JWT secret`.

### Task 2: One error contract, and 401 for bad credentials

Spec items: CMN-2, CMN-4, CMN-7, REP-1, ID-5, ID-9 (the 401 part).

**Files:**
- Modify: `backend/src/main/java/vn/essvn/erpcafe/common/web/GlobalExceptionHandler.java`
- Create: `backend/src/main/java/vn/essvn/erpcafe/common/exception/BadRequestException.java`
- Create: `backend/src/main/java/vn/essvn/erpcafe/common/exception/UnauthorizedException.java`
- Modify: `backend/src/main/java/vn/essvn/erpcafe/common/context/BranchContext.java` (`require()` throws `BadRequestException("X-Branch-Id header is required")`)
- Modify: `backend/src/main/java/vn/essvn/erpcafe/identity/security/CurrentUser.java` (`require()` throws `UnauthorizedException`)
- Modify: `backend/src/main/java/vn/essvn/erpcafe/identity/security/SecurityConfig.java` (permit `/error` and `DispatcherType.ERROR`)
- Modify: `backend/src/main/java/vn/essvn/erpcafe/identity/application/AuthService.java` (bad credentials → `UnauthorizedException("Invalid email or password")`)
- Modify tests that pin 422 for bad credentials: `identity/AuthFlowIntegrationTest.java` (~line 68) and any throttle/refresh test asserting 422 for a wrong password.
- Test: `backend/src/test/java/vn/essvn/erpcafe/common/ErrorContractIntegrationTest.java` (MockMvc)
- Test: `backend/src/test/java/vn/essvn/erpcafe/support/ErrorDispatchHttpTest.java` (`@SpringBootTest(webEnvironment = RANDOM_PORT)`, real HTTP via `RestClient`/`TestRestTemplate`, same Testcontainers container — make `AbstractIntegrationTest.POSTGRES` `protected static` or add a small shared base if needed)

**Interfaces:**
- Produces: `BadRequestException extends RuntimeException` → 400 `code=BAD_REQUEST`; `UnauthorizedException extends RuntimeException` → 401 `code=UNAUTHORIZED`.
- Produces: every ProblemDetail from `GlobalExceptionHandler` carries property `code` with one of: `VALIDATION` (400 bean validation), `BAD_REQUEST` (400 other: unreadable body, type mismatch, missing header/param, `BadRequestException`), `UNAUTHORIZED` (401), `FORBIDDEN` (403), `NOT_FOUND` (404, incl. unknown route), `CONFLICT` (409, incl. optimistic lock and data integrity), `BUSINESS_RULE` (422), `TOO_MANY_REQUESTS` (429), `INTERNAL` (500). Later tasks and the frontend switch on `code`.
- `GlobalExceptionHandler extends ResponseEntityExceptionHandler` and keeps today's validation body shape (existing tests read field errors — keep them passing).
- Optimistic lock message is fixed: `This record was changed by someone else. Reload and try again.` (no entity class names).
- `AccessDeniedException` → 403 `FORBIDDEN`; `AuthenticationException` → 401. A catch-all `Exception` handler → 500 `INTERNAL` with a generic detail and the exception logged at ERROR.

- [ ] **Step 1: Write failing tests** in `ErrorContractIntegrationTest` (each also asserts `$.code`):
  - POST `/api/v1/ingredients` with body `{not json` → 400 `BAD_REQUEST`.
  - GET `/api/v1/ingredients/not-a-uuid` → 400 `BAD_REQUEST`.
  - GET `/api/v1/reports/summary` without `X-Branch-Id` (admin token) → 400 `BAD_REQUEST`.
  - GET `/api/v1/no-such-route` with a valid token → 404 `NOT_FOUND`.
  - A cashier-only permission denial (create a cashier via the users API as existing tests do, then call an endpoint needing `user:write`) → 403 `FORBIDDEN`.
  - Validation failure (existing invalid ingredient body) → 400 `VALIDATION`, field errors still present.
  - Stale version update on an ingredient → 409 `CONFLICT` with the fixed message and no class name in `detail`.
  - Login with a wrong password → 401 `UNAUTHORIZED`.
- [ ] **Step 2: Write failing test** `ErrorDispatchHttpTest` over a real port: an authenticated GET to an unknown route returns 404 (not 401); an authenticated GET `/api/v1/reports/summary` without the branch header returns 400 (not 401).
- [ ] **Step 3:** Run both → FAIL.
- [ ] **Step 4: Implement** the handler, exceptions, `BranchContext`/`CurrentUser` changes, `/error` permit, and bad-credential 401. Update tests that pinned 422 for a wrong password to 401.
- [ ] **Step 5:** Targeted tests green; full suite (only the two known failures).
- [ ] **Step 6: Commit** `[Fix] Map every error to its real status and code` and `[Fix] Return 401 for bad credentials` (two commits).

### Task 3: Scope sales, catalog and branch lookups to the caller

Spec items: SAL-1, CAT-1, ORG-2, INV-21, INV-12 (the `baseUnitsOf` scoping only).

**Files:**
- Modify: `sales/persistence/OrderRepository.java` (`Optional<Order> findByIdAndBranchId(UUID id, UUID branchId)`), `sales/application/OrderService.java` (`get` uses it with the active branch)
- Modify: catalog repositories (`ProductRepository`, `CategoryRepository`, `ModifierGroupRepository`, `ModifierRepository`, `RecipeRepository` as needed) with company-scoped finders; `catalog/application/ProductService.java`, `CategoryService.java`, `ModifierService.java`, `PricingService.java`, `RecipeService.java`, `CatalogApiImpl.java`
- Modify: `organization/api/OrganizationApi.java` + its implementation: delete `branchExists(UUID)`; add `Optional<UUID> companyIdOf(UUID branchId)`
- Modify: `inventory/application/StockService.java` (~87-93) and `PurchaseOrderService.java` (~155-169): foreign IDs → 404 via scoped finders
- Modify: `inventory/application/StockLedgerService.java` `baseUnitsOf`: only ingredients of the company that owns the branch (`IngredientRepository.findAllByIdInAndCompanyId`)
- Test: `backend/src/test/java/vn/essvn/erpcafe/sales/SalesTenantIsolationTest.java`
- Test: `backend/src/test/java/vn/essvn/erpcafe/catalog/CatalogTenantIsolationTest.java`

**Interfaces:**
- Consumes: Task 2 error contract (404 `NOT_FOUND`).
- Produces: `OrganizationApi.companyIdOf(UUID branchId): Optional<UUID>`; `OrganizationApi.branchExists` no longer exists (use `branchExistsInCompany`).
- Catalog lookups use the caller's company (`CurrentUser.require().companyId()`, as the rest of the code does today; Task 30 replaces this source).

- [ ] **Step 1: Write failing tests.** `SalesTenantIsolationTest`: seed company B with a branch, category, product and an OPEN order at B's branch (via repositories). As the admin (company A, own branch header): GET, `/payments`, `/complete`, `/cancel`, `/void`, `/refund` on B's order → 404 each, and B's order row is unchanged (status, payments count) afterwards. Also: an order at a second branch of company A that the header does not select → 404 (branch isolation). `CatalogTenantIsolationTest`: B's product GET/PUT/DELETE, `PUT /products/{id}/recipe`, `PUT /products/{id}/modifier-groups`, availability PUT, B's category GET/PUT/DELETE, B's modifier group GET/DELETE → 404 and B's rows unchanged; availability PUT for A's product with B's branch ID → 404; creating an order line with B's product ID → 404.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3: Implement** the scoped finders and replace every bare `findById` on tenant data in these services (`grep -n "findById(" backend/src/main/java/vn/essvn/erpcafe/{catalog,sales}` must return only non-tenant lookups afterwards). Remove `branchExists`. Scope `baseUnitsOf`.
- [ ] **Step 4:** Targeted green; full suite.
- [ ] **Step 5: Commit** `[Fix] Scope order lookups to the active branch`, `[Fix] Scope catalog lookups to the caller's company`, `[Fix] Return 404 for another company's stock and purchase IDs`.

### Task 4: Scope the remaining identity lookups

Spec item: ID-1.

**Files:**
- Modify: `identity/application/RoleService.java` (`get` → `findByIdAndCompanyId`; `requireMutableRole`/`requireCustomRole` answer 404 for another company's role)
- Modify: `identity/application/UserService.java` (`accessOf` and `revokeAccess` first resolve the user with the company-scoped `get(userId)`; `grantAccess` resolves the role with the company-scoped finder)
- Test: `backend/src/test/java/vn/essvn/erpcafe/identity/RoleTenantIsolationTest.java`

- [ ] **Step 1: Failing tests:** seed company B with a role, a user and a grant. As admin: GET `/api/v1/roles/{B-role}` → 404; PUT/DELETE B's role → 404; GET `/api/v1/users/{B-user}/access` → 404; DELETE `/api/v1/users/{B-user}/access/{B-branch}` → 404 **and B's grant still exists** (query the repository); POST `/users/{A-user}/access` with B's role → 404.
- [ ] **Step 2:** Run → FAIL. **Step 3:** Implement. **Step 4:** Green + full suite.
- [ ] **Step 5: Commit** `[Fix] Scope role and grant lookups to the caller's company`.

### Task 5: Reject invalid values at the edge

Spec items: CAT-3, INV-1, ID-4, CAT-8, CAT-17, CAT-15, STF-3.

**Files:**
- Modify: `catalog/web/ProductDtos.java` (`BranchAvailabilityRequest.priceOverride` `@PositiveOrZero`; `UpdateProductRequest.active` → `Boolean`, `null` = unchanged)
- Modify: `catalog/application/ProductService.java` (honour `null` active)
- Modify: `inventory/web/PurchaseOrderDtos.java` (`ReceiptRequest.unitCostOverride` `@PositiveOrZero`)
- Create: `common/web/MaxUtf8Bytes.java` + `MaxUtf8BytesValidator.java` (constraint: string's UTF-8 length ≤ `value()`)
- Modify: `identity/web/UserDtos.java` (every password field `@MaxUtf8Bytes(72)`; keep `@Size(min = 8)`)
- Modify: `catalog/web/ModifierDtos.java` (group request: `@AssertTrue` min ≤ max; `@Valid` on `recipeDeltas`)
- Modify: catalog recipe request DTO: base recipe lines require `@Positive` quantity (introduce a dedicated recipe-line record if `ConsumptionLineDto` is shared with signed modifier deltas)
- Modify: `staff/web/StaffDtos.java` + `EmployeeController`/`EmployeeService` (employee `active` `Boolean`, `null` = unchanged)
- Test: `backend/src/test/java/vn/essvn/erpcafe/common/InputValidationIntegrationTest.java` (one class, one test per rule)

- [ ] **Step 1: Failing tests** (all expect 400 `VALIDATION` unless stated): availability with `priceOverride: -1`; receipt with `unitCostOverride: -400`; create user with a 73-byte password (e.g. `"ệ".repeat(25)` = 75 bytes) — and a 72-byte ASCII password is accepted; modifier group `minSelect: 3, maxSelect: 1`; modifier group with a recipe delta missing `ingredientId`; recipe line with quantity `0`; product update omitting `active` keeps an inactive product inactive and an active one active (200); employee update omitting `active` keeps an inactive employee inactive (200).
- [ ] **Step 2:** Run → FAIL. **Step 3:** Implement. **Step 4:** Green + full suite.
- [ ] **Step 5: Commit** `[Fix] Reject negative prices, costs and out-of-range inputs` and `[Fix] Keep active flags unchanged when omitted`.

### Task 6: Frontend test runner, lint and explicit strict mode

Spec items: FE-7, FE-13.

**Files:**
- Modify: `frontend/package.json` (devDependencies: `vitest`, `jsdom`, `angular-eslint`, `eslint`, `typescript-eslint` at versions compatible with Angular 22 / TS 6; scripts `"test": "ng test --watch=false"`, `"lint": "ng lint"`, `"format": "prettier --write \"src/**/*.{ts,html,scss}\""`)
- Modify: `frontend/angular.json` (add `test` target using `@angular/build:unit-test` with the Vitest runner and `tsconfig.spec.json`; add `lint` target; remove `skipTests: true` from schematics)
- Modify: `frontend/tsconfig.json` (`"strict": true` explicit)
- Create: `frontend/eslint.config.js` (angular-eslint recommended + template recommended/accessibility)
- Create: `frontend/src/app/core/util.spec.ts` (first real spec, e.g. for a pure helper in `util.ts` or `units.ts`)

- [ ] **Step 1:** Add the runner config and one failing spec (assert a wrong value first), run `npm test` → FAIL for the assertion (proves the runner works).
- [ ] **Step 2:** Fix the assertion to the real expected value → PASS.
- [ ] **Step 3:** Add ESLint; run `npm run lint`. Fix only errors in the config's recommended rule set that block the run; if the existing code produces many warnings, keep them as warnings (do not mass-refactor components in this task). `npx ng build` must pass.
- [ ] **Step 4: Commit** `[Add] Vitest test runner and ESLint for the frontend`.

### Task 7: Fix the frontend auth pipeline and login

Spec items: FE-1, FE-2, FE-3, FE-4, FE-5, FE-6, FE-9, FE-11.

**Files:**
- Modify: `frontend/src/app/core/http.interceptors.ts`
- Modify: `frontend/src/app/core/auth.service.ts`
- Modify: `frontend/src/app/features/auth/login.ts`
- Modify: `frontend/src/app/layout/shell.ts`
- Test: `frontend/src/app/core/http.interceptors.spec.ts`, `frontend/src/app/core/auth.service.spec.ts`, `frontend/src/app/features/auth/login.spec.ts`

**Interfaces:**
- Consumes: Task 2 — bad credentials 401 `UNAUTHORIZED`, throttling 429, every error has `code`; Task 6 — Vitest.
- Behaviour: only a failed **refresh** logs the user out; an error from the retried request propagates normally. When a refresh fails, every request queued behind it errors (no hang). Login shows "Invalid email or password." for 401, "Too many attempts. Wait a minute and try again." for 429, and "Cannot reach the server. Check your connection and try again." for status 0; nothing mentions `:8090`. The login form starts empty. Logout removes tokens **and** `erpcafe.branch`, and waits for the logout POST (or sends it with `keepalive`) before navigating. After login, a stored branch not in `me.branchIds` is replaced by the first of `me.branchIds`. The branch switcher lists only `me.branchIds`. The 422-on-`/orders` toast suppression is removed (errors with `code` `BUSINESS_RULE` toast their `detail`).

- [ ] **Step 1: Failing specs** (HttpTestingController + TestBed):
  - 401 → refresh OK → retry returns 409: the error reaches the caller, `logout` not called, no navigation to `/login`.
  - 401 → refresh fails: user logged out and navigated to `/login`; a second request queued during the refresh errors instead of hanging (assert it completes with an error).
  - Two concurrent 401s trigger exactly one refresh call.
  - `logout()` clears access token, refresh token and `erpcafe.branch`.
  - Login component: 401 → "Invalid email or password."; 429 → the throttle message; status 0 → the connection message; initial form values are empty strings.
  - After `me` loads with `branchIds: ['b2']` and stored branch `'b1'`, active branch becomes `'b2'`.
- [ ] **Step 2:** `npm test` → FAIL. **Step 3:** Implement. **Step 4:** `npm test` and `npx ng build` green.
- [ ] **Step 5: Commit** `[Fix] Log out only when the token refresh fails`, `[Fix] Clear the branch on logout and keep it within the user's branches`, `[Fix] Show accurate login errors and drop the dev hints`.

---

## Phase 2 — Money and stock

### Task 8: Order payment lifecycle, completing on full payment

Spec items: SAL-2, SAL-4, SAL-5, SAL-6, SAL-14. Decision from the user: **a sale deducts stock when it is fully paid** (paying in full completes the order).

**Files:**
- Modify: `sales/domain/Order.java`, `sales/application/OrderService.java`, `sales/web/OrderController.java` (if response/flows change)
- Modify: `frontend/src/app/features/sales/pos.ts` (paid dialog: no Complete button; shows COMPLETED), `frontend/src/app/features/sales/orders.ts` (Complete only offered for legacy PAID orders)
- Modify: `backend/src/test/java/vn/essvn/erpcafe/sales/SalesFlowIntegrationTest.java` (existing flows now complete on payment)
- Test: `backend/src/test/java/vn/essvn/erpcafe/sales/OrderPaymentLifecycleTest.java`

**Interfaces:**
- Produces (domain rules): `Order.addPayment` only while OPEN; a payment larger than the remaining balance → 422 (`BusinessRuleException`) — change is the till's business. When the balance reaches zero the order becomes PAID and, **in the same transaction**, `OrderService` completes it (deduct stock via `InventoryApi.deductForOrder`, record COGS, status COMPLETED). If completion fails, the payment fails with that reason and nothing is saved. A zero-total order completes at creation. `complete()` checks status **before** any side effect (PAID required; otherwise 422 and no movements). `cancel()` on an OPEN order with payments is allowed: status CANCELLED and one REFUND payment entry per SALE tender (same method, same amount). Refund/void pay back at most the grand total. Recording a refund entry is not callable from outside `Order`'s own transitions.
- `POST /orders/{id}/complete` stays (for legacy PAID orders) with the rule above.

- [ ] **Step 1: Failing tests** in `OrderPaymentLifecycleTest` (seed a product with a one-ingredient recipe and stock via the API as `SalesFlowIntegrationTest` does):
  - Full payment → response status COMPLETED, stock on hand reduced by the recipe quantity, `cogsTotal` set.
  - Payment larger than the balance → 422, no payment stored.
  - Payment on a COMPLETED order → 422.
  - Part payment then cancel → 200, status CANCELLED, payments contain a REFUND entry equal to the part payment, same method.
  - 100% order discount (zero total) → status COMPLETED at creation, stock reduced.
  - `POST /complete` on an OPEN order → 422 and no stock movement rows for that order.
  - Void of a COMPLETED order records a refund equal to the grand total.
  - (Review Focus 3) Two concurrent full payments on one order (two threads, each its own request): exactly one succeeds; the other gets 409 or 422; stock is deducted once.
- [ ] **Step 2:** Run → FAIL. **Step 3:** Implement backend; update `SalesFlowIntegrationTest` expectations; update POS/orders pages. **Step 4:** Backend suite + `npm test` + `npx ng build`.
- [ ] **Step 5: Commit** `[Fix] Complete a sale when it is fully paid` and `[Fix] Enforce payment, cancel and refund rules on orders`.

### Task 9: Move totals into Order and fix what subtotal means

Spec items: SAL-10, SAL-11, SAL-7 (discount bounds).

**Files:**
- Modify: `sales/domain/Order.java`, `sales/domain/OrderLine.java`, `sales/application/OrderService.java`
- Create: `backend/src/main/resources/db/migration/V<next>_001__order_subtotal_is_gross.sql`
- Modify: `frontend/src/app/features/sales/orders.ts` (slip shows gross, discounts, net, tax, total that add up)
- Test: `backend/src/test/java/vn/essvn/erpcafe/sales/domain/OrderTotalsTest.java` (plain unit tests, no Spring), plus an integration assertion in `OrderPaymentLifecycleTest` or a new `OrderTotalsIntegrationTest`

**Interfaces:**
- Produces: `Order.addLine(PricedLine priced, int quantity, BigDecimal lineDiscount)` computes the line total; `Order.applyOrderDiscount(BigDecimal)`; `Order.setTaxRate(BigDecimal)` (or constructor arg) — totals are recomputed inside `Order` on every change. No public `setTotals`/`setLineTotal`. `getLines()`/`getPayments()` return unmodifiable views.
- Definitions (stored columns): `subtotal` = gross = Σ(unit total × qty) before any discount; `discount_total` = Σ line discounts + order discount; net = subtotal − discount_total; `tax_total` = round(net × tax_rate, HALF_EVEN, Money scale); `grand_total` = net + tax_total. Bounds: 0 ≤ line discount ≤ line gross; 0 ≤ order discount ≤ subtotal − line discounts; otherwise 422.
- Migration: existing rows stored `subtotal` net of line discounts; add back each order's Σ `order_line.line_discount` so old rows follow the new definition. Grand totals of existing rows are not changed.

- [ ] **Step 1: Failing unit tests** in `OrderTotalsTest`: 2 × 25,000 with line discount 5,000 and order discount 3,000 at tax 0.1 → subtotal 50,000, discount_total 8,000, tax 4,200, grand 46,200. A tax that yields a fractional đồng (e.g. net 33,333 × 0.08 = 2,666.64) rounds HALF_EVEN to the Money scale and grand = net + tax exactly (Review Focus 2). Line discount above line gross → `BusinessRuleException`. Order discount above remaining subtotal → exception. `getLines().add(...)` throws `UnsupportedOperationException`.
- [ ] **Step 2: Failing integration test:** create an order via the API with the numbers above → response `subtotal`, `discountTotal`, `taxTotal`, `grandTotal` as computed.
- [ ] **Step 3:** Run → FAIL. **Step 4:** Implement + migration. **Step 5:** Suite green.
- [ ] **Step 6: Commit** `[Refactor] Compute order totals inside Order` and `[Fix] Store gross subtotal and count line discounts once`.

### Task 10: Tax rate comes from the branch

Spec item: SAL-7 (tax rate from branch settings).

**Files:**
- Create: `backend/src/main/resources/db/migration/V<next>_001__branch_tax_rate.sql` (`ALTER TABLE branch ADD COLUMN tax_rate NUMERIC(5,4) NOT NULL DEFAULT 0 CHECK (tax_rate >= 0 AND tax_rate <= 1)`)
- Modify: `organization/domain/Branch.java`, branch request/response DTOs (tax rate validated 0..1), `organization/api/BranchDto.java` (+ `BigDecimal taxRate`), organization mapper/API impl
- Modify: `sales/web/OrderDtos.java` (remove `taxRate` from the create request), `sales/application/OrderService.java` (rate from `OrganizationApi.findBranch(activeBranch)`)
- Modify: `frontend/src/app/features/organization/branches.ts` (tax rate field), `frontend/src/app/features/sales/pos.ts` (shows the branch's rate read-only; no tax input), `frontend/src/app/core/models.ts`, `api.services.ts`
- Test: extend `organization/OrganizationTenantIsolationTest` or new `BranchTaxRateIntegrationTest`

- [ ] **Step 1: Failing tests:** update a branch with `taxRate: 0.08` → persisted; `taxRate: 1.5` → 400; an order created at that branch has `taxRate` 0.08 and tax on net even if the client sends `taxRate: 0.5`.
- [ ] **Step 2:** FAIL. **Step 3:** Implement. **Step 4:** Suite + frontend build/tests.
- [ ] **Step 5: Commit** `[Add] Branch tax rate used for every order`.

### Task 11: Reverse the recorded deduction on void and refund

Spec items: INV-10, SAL-8 (and the known "void/refund COGS doesn't net" finding).

**Files:**
- Modify: `inventory/api/InventoryApi.java` (remove `returnForOrder`; add `CogsResult reverseDeduction(UUID branchId, String refType, UUID refId, String reversalRefType)`)
- Modify: `inventory/application/StockLedgerService.java`, `inventory/application/InventoryApiImpl.java`
- Modify: `sales/application/OrderService.java` (void/refund call `reverseDeduction` when stock was deducted; `stockLinesFor` no longer used on reversal)
- Modify: `catalog/api/CatalogApi.java` Javadoc of `explode`: the result reflects the recipe *now* and must not be used to reverse a past sale
- Test: `backend/src/test/java/vn/essvn/erpcafe/inventory/ReverseDeductionIntegrationTest.java`

**Interfaces:**
- Produces: `reverseDeduction` writes one SALE_REVERSAL movement per recorded SALE_DEPLETION movement of `(refType, refId)`, same ingredient, same quantity (opposite sign), **same unit cost** as the depletion; restores stock on hand; returns the reversed cost. Calling it twice for the same reference is a no-op returning zero cost (idempotent). The moving-average cost is recomputed as a receipt at the original cost.

- [ ] **Step 1: Failing tests:** receive 1,000 g at 0.20 → sell a product using 18 g → change the recipe to 20 g → receive 1,000 g at 0.30 → void the order: stock on hand equals the pre-void total + 18 g (net change of the sale = 0); `InventoryApi.depletionCost` over the period = 0; calling void's reversal twice (direct API call) writes no extra movements.
- [ ] **Step 2:** FAIL. **Step 3:** Implement. **Step 4:** Suite.
- [ ] **Step 5: Commit** `[Fix] Reverse the recorded deduction on void and refund`.

### Task 12: Ledger concurrency — first stock row and one deduction per reference

Spec items: INV-8, INV-9, SAL-9.

**Files:**
- Create: `backend/src/main/resources/db/migration/V<next>_001__ledger_reference.sql` (`ledger_reference(ref_type VARCHAR(32), ref_id UUID, branch_id UUID NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now(), PRIMARY KEY (ref_type, ref_id))`)
- Modify: `inventory/application/StockLedgerService.java` (claim the reference with `INSERT … ON CONFLICT DO NOTHING` and treat 0 rows as "already done"; create a missing stock row with `INSERT … ON CONFLICT DO NOTHING` then `SELECT … FOR UPDATE`)
- Modify: `inventory/application/StockService.java` (`setReorderLevel` uses the same create-then-lock path)
- Test: `backend/src/test/java/vn/essvn/erpcafe/inventory/LedgerConcurrencyTest.java` (two threads, each with its own `TransactionTemplate` transaction, synchronised with a `CountDownLatch`)

- [ ] **Step 1: Failing tests** (Review Focus 3): two threads deduct the same `(ORDER, refId)` concurrently → exactly one set of SALE_DEPLETION movements, the other call returns the "already deducted" result without error; two threads each make the first deduction of a new ingredient at a branch with no stock row → both succeed, one stock row, on-hand = −(q1 + q2).
- [ ] **Step 2:** FAIL (expect a unique-key error or duplicate movements). **Step 3:** Implement. **Step 4:** Suite.
- [ ] **Step 5: Commit** `[Fix] Claim each ledger reference once and create stock rows without a race`.

### Task 13: Ingredient units and average cost stay correct

Spec items: INV-2, INV-3, INV-4.

**Files:**
- Modify: `inventory/application/StockLedgerService.java` (average-cost reset; conversion throws when the ingredient is missing)
- Modify: `inventory/application/IngredientService.java` (base-unit change guard; delete guard)
- Test: `backend/src/test/java/vn/essvn/erpcafe/inventory/IngredientIntegrityTest.java` (+ unit cases in `inventory/application/StockLedgerServiceTest.java`)

**Interfaces:**
- Rules: when stock on hand ≤ 0 before a receipt, the new average cost = the receipt's unit cost. Changing an ingredient's base unit is refused with 409 once any stock row with non-zero quantity or any movement exists for it; a change to a different `Dimension` is always refused with 422. Deleting an ingredient with non-zero stock at any branch or an open (not fully received, not cancelled) PO line → 409. Converting a line whose ingredient is missing or soft-deleted → 422 `BusinessRuleException` naming the ingredient ID.

- [ ] **Step 1: Failing tests:** sell 100 g with no stock (on hand −100 at cost 0), receive 1,000 g at 0.24 → average 0.24; ingredient with stock ml → l → 409; ingredient without movements g → ml → 422; g → kg with no movements → 200; delete with stock → 409; delete with an open PO line → 409; deduct in kg against a soft-deleted ingredient → 422.
- [ ] **Step 2:** FAIL. **Step 3:** Implement. **Step 4:** Suite.
- [ ] **Step 5: Commit** `[Fix] Reset average cost after negative stock` and `[Fix] Guard base-unit changes and ingredient deletes`.

### Task 14: One place for unit conversion, and recipes validated at save

Spec items: INV-11, INV-12, CAT-4.

**Files:**
- Modify: `inventory/api/InventoryApi.java` (remove `ingredientExists`; add `void validateConsumption(UUID companyId, List<StockLine> lines)`)
- Modify: `inventory/application/StockLedgerService.java` (single `toBase(ingredientId, qty, unit)` used by deduct, receive, adjust, waste; `receive/adjust/waste` take a unit)
- Modify: `inventory/application/PurchaseOrderService.java` (conversion removed; passes the receipt unit to the ledger)
- Modify: `inventory/web/StockDtos` (or equivalent adjust/waste request: optional `unit`, default the ingredient's base unit), `inventory/application/StockService.java`
- Modify: `catalog/application/RecipeService.java`, `ModifierService.java` (call `validateConsumption` on save)
- Modify: `frontend/src/app/features/inventory/stock.ts` (adjust/waste form has a unit select limited to the ingredient's dimension, default base unit)
- Test: `backend/src/test/java/vn/essvn/erpcafe/inventory/UnitConversionSeamTest.java`, `backend/src/test/java/vn/essvn/erpcafe/catalog/RecipeValidationIntegrationTest.java`

**Interfaces:**
- Produces: `validateConsumption` throws `BusinessRuleException` (422) listing each problem: unknown ingredient ID, ingredient of another company, unknown unit, unit of a different dimension.

- [ ] **Step 1: Failing tests:** adjust +2 kg on a gram ingredient → on hand +2,000 g; waste 0.5 `l` on an ml ingredient → on hand −500 ml; adjust in `g` on an ml ingredient → 422 (different dimension); receipt in kg on a gram ingredient still converts (existing `ReceivingConversionTest` stays green); save a recipe with an unknown ingredient ID → 422; with unit `cup` → 422; with `g` against an ml ingredient → 422; with a valid line → 200.
- [ ] **Step 2:** FAIL. **Step 3:** Implement. **Step 4:** Suite + frontend.
- [ ] **Step 5: Commit** `[Refactor] Convert units once, inside the ledger` and `[Fix] Validate recipe ingredients and units when saved`.

### Task 15: Deepen CatalogApi pricing

Spec items: CAT-2, CAT-6, SAL-3, CAT-14.

**Files:**
- Modify: `catalog/api/CatalogApi.java` → exactly `PricedLine priceLine(UUID branchId, UUID productId, Set<UUID> modifierIds)` and `List<IngredientConsumption> explode(UUID productId, int quantity, Set<UUID> modifierIds)`
- Delete: `CatalogApi.priceOf`, `productExists`, `validateModifierSelection` (folded into `priceLine`)
- Modify/Delete: `catalog/application/PricingService.java` (merge into the single pricing path; `ProductController`'s price endpoint uses it), `CatalogApiImpl.java` (remove the duplicated modifier sum; fix stale comments at ~70-71 and `RecipeService` ~17-18)
- Modify: `sales/application/OrderService.java` (single `priceLine` call per line)
- Test: `backend/src/test/java/vn/essvn/erpcafe/catalog/PricingRulesIntegrationTest.java`

**Interfaces:**
- `priceLine` enforces, in order: product exists in the branch's company (else 404); product active (else 422); availability at the branch not `available=false` (else 422); every modifier belongs to one of the product's groups, is active, and group min/max selection holds (else 422); price = branch override if set, else base price, plus modifier deltas.

- [ ] **Step 1: Failing tests:** order with an inactive product → 422; product marked unavailable at the branch → 422; branch override 28,000 on base 25,000 → line price 28,000; modifier from a group not attached to the product → 422; exceeding a group's `maxSelect` → 422; missing a required group → 422 (existing behaviour kept); `GET /products/{id}/price` (existing endpoint) returns the same number as the order line.
- [ ] **Step 2:** FAIL. **Step 3:** Implement. **Step 4:** Suite.
- [ ] **Step 5: Commit** `[Refactor] One pricing path behind CatalogApi.priceLine` and `[Fix] Refuse inactive and unavailable products at the till`.

### Task 16: Reports that add up

Spec items: REP-2, REP-3, REP-4, REP-6, REP-7, REP-8, SAL-20, REP-10.

**Files:**
- Modify: `sales/api/SalesSummary.java` → `record SalesSummary(long orderCount, BigDecimal grossSales, BigDecimal discountTotal, BigDecimal netSales, BigDecimal taxTotal, BigDecimal grandTotal)`; Javadoc on `SalesApi` states: amounts from COMPLETED orders (and legacy PAID), net sales exclude tax, days use the configured zone
- Modify: `sales/persistence/OrderRepository.java` (summary query; `revenueByDay` grouped in the configured zone), `sales/application/SalesReportService.java`
- Create: `reporting/application/ReportingProperties.java` (`app.reporting.zone`, default `Asia/Ho_Chi_Minh`; `app.reporting.max-range-days`, default 366)
- Modify: `reporting/application/ReportService.java` (gross margin = netSales − ledger COGS; net margin = gross margin − labour; outlets only for the caller's `branchIds`), `reporting/web/ReportController.java` (one period resolver: default "today" in the zone, `from < to`, range ≤ max → else 400), `reporting/...ReportModels.java` (one COGS figure: ledger depletion net of reversals)
- Modify: `frontend/src/app/features/reports/reports.ts` (new fields; zero-filled days; cancel stale requests with `switchMap`), `frontend/src/app/features/dashboard/dashboard.ts` (Sales tile from `/reports/summary`)
- Test: `backend/src/test/java/vn/essvn/erpcafe/reporting/ReportFiguresIntegrationTest.java`, `backend/src/test/java/vn/essvn/erpcafe/reporting/application/ReportServiceTest.java` (stubbed facades)

- [ ] **Step 1: Failing tests:** one completed order (gross 50,000, discounts 8,000, tax 10% → tax 4,200, grand 46,200, COGS 10,000): summary `netSales` 42,000, gross margin 32,000; an order at 2026-03-02T17:30:00Z (00:30 on 3 March in UTC+7) lands on 2026-03-03 in `sales-by-day`; `from` after `to` → 400; a 400-day range → 400; a user granted one of two branches gets one outlet row; `ReportServiceTest` with stubbed facades checks margin arithmetic.
- [ ] **Step 2:** FAIL. **Step 3:** Implement. **Step 4:** Suite + frontend.
- [ ] **Step 5: Commit** `[Fix] Compute margins on net sales and group days in Vietnam time`, `[Fix] Show outlets only for the caller's branches`, `[Refactor] Resolve report periods in one place`.

### Task 17: Book sales and refunds on the day they happen

Spec items: REP-5, SAL-16.

**Files:**
- Create: `backend/src/main/resources/db/migration/V<next>_001__order_event_times.sql` (add `completed_at TIMESTAMPTZ`, `reversed_at TIMESTAMPTZ` to `sales_order`; backfill `completed_at = updated_at` for COMPLETED, `reversed_at = updated_at` for VOID/REFUNDED; index `(branch_id, completed_at)`, `(branch_id, reversed_at)`)
- Modify: `sales/domain/Order.java` (set the timestamps in `complete`, `voidOrder`, `refund`), `sales/persistence/OrderRepository.java` (period figures = Σ completed in period − Σ reversed in period for orders that had completed)
- Modify: `staff` labour query Javadoc: labour counts COMPLETED shifts by clock-out time (unchanged rule, now documented on `StaffApi`)
- Test: `backend/src/test/java/vn/essvn/erpcafe/reporting/ReportPeriodBasisTest.java`

**Interfaces:**
- Revenue, COGS (ledger time) and the reversal all land in the period where they happened. A sale on day 1 refunded on day 8: the day 1–7 report still shows the sale and its COGS; the day 8–14 report shows the refund as negative net sales and negative COGS of the same size.

- [ ] **Step 1: Failing test:** set timestamps explicitly (update rows via repository/JdbcTemplate after the API flow) for a sale on day 1 and its refund on day 8; assert both periods as above and that each period's gross margin is consistent (net sales − COGS).
- [ ] **Step 2:** FAIL. **Step 3:** Implement. **Step 4:** Suite.
- [ ] **Step 5: Commit** `[Fix] Book sales and refunds in the period they happen`.

### Task 18: Staff time clock rules and pay privacy

Spec items: STF-1, STF-2, STF-4, STF-5, STF-6, STF-7, STF-8, STF-11, STF-12.

**Files:**
- Create: `backend/src/main/resources/db/migration/V<next>_001__one_open_shift_per_employee.sql` (`CREATE UNIQUE INDEX uk_shift_one_open_per_employee ON shift (employee_id) WHERE status = 'IN_PROGRESS'`; drop the full-name uniqueness constraint if one exists)
- Modify: `staff/domain/Shift.java` (manager `adjust(Instant clockIn, Instant clockOut)`; max length), `staff/application/ShiftService.java` (clock-in checks: employee active, no other open shift → 422; clock-out beyond 16 h → 422 "Shift is longer than 16 hours; ask a manager to adjust it"), `EmployeeService.java` (no name uniqueness; deactivation cancels the employee's future SCHEDULED shifts)
- Modify: `staff/web/ShiftController.java` (`POST /api/v1/shifts/{id}/adjust` `{clockIn?, clockOut}` needs `staff:write`; names looked up only for returned shifts; validate `from < to` → 400), `staff/web/EmployeeController.java` + DTOs (hourly rate, phone, email and labour cost are `null` unless the caller holds `staff:write`), `staff/persistence/ShiftRepository.java` (open-shifts query used)
- Create: `staff/application/StaffProperties.java` (`app.staff.max-shift-hours`, default 16)
- Modify: `frontend/src/app/features/staff/shifts.ts` (open-shifts panel; overnight shifts allowed — end before start means next day; Adjust action for managers), `team.ts` (hide rate/contact columns when absent)
- Test: `backend/src/test/java/vn/essvn/erpcafe/staff/TimeClockRulesTest.java`, `backend/src/test/java/vn/essvn/erpcafe/staff/domain/ShiftTest.java` (unit: illegal transitions, rounding)

- [ ] **Step 1: Failing tests:** second clock-in for an employee with an open shift at another branch → 422; clock-in of a deactivated employee → 422; clock-out after 17 h → 422; manager adjust sets clock-out, status COMPLETED, labour cost = hours × snapshot rate rounded HALF_EVEN; a cashier's employee list has `hourlyRate` null and shift `laborCost` null; two employees with the same full name → both 201; deactivating an employee cancels their future scheduled shifts; `ShiftTest` covers every illegal transition.
- [ ] **Step 2:** FAIL. **Step 3:** Implement. **Step 4:** Suite + frontend.
- [ ] **Step 5: Commit** `[Fix] One open shift per employee and no clock-in when deactivated`, `[Add] Manager shift adjustment with a maximum shift length`, `[Fix] Hide pay data from staff readers`.

### Task 19: Catalog management gaps

Spec items: CAT-7, CAT-10, CAT-18, CAT-12.

**Files:**
- Modify: `catalog/application/CategoryService.java` (delete in use → 409)
- Modify: `catalog/application/ModifierService.java`, `catalog/web/ModifierController.java`, DTOs (`PUT /modifier-groups/{id}` name/min/max/required; `PUT /modifier-groups/{id}/modifiers/{modifierId}` name/priceDelta/active/recipeDeltas; `DELETE /modifier-groups/{id}/modifiers/{modifierId}`; `DELETE /modifier-groups/{id}` in use by a product → 409)
- Modify: `catalog/application/RecipeService.java` + recipe request/response (version-in-body optimistic check, same pattern as `IngredientService.requireCurrentVersion`)
- Modify: `backend/src/main/resources/application.yml` (`spring.jpa.properties.hibernate.default_batch_fetch_size: 50`)
- Modify: `frontend/src/app/features/catalog/modifiers.ts` (edit/remove modifier, edit group), `products.ts` (send recipe version)
- Test: `backend/src/test/java/vn/essvn/erpcafe/catalog/CatalogManagementIntegrationTest.java`

- [ ] **Step 1: Failing tests:** delete category with products → 409; edit a modifier's price → later order uses new price; delete a modifier → no longer selectable (422 when used); delete a group attached to a product → 409; stale recipe version → 409; a page of 20 products loads with a bounded number of statements (use Hibernate `Statistics`: prepared statement count ≤ 5).
- [ ] **Step 2:** FAIL. **Step 3:** Implement. **Step 4:** Suite + frontend.
- [ ] **Step 5: Commit** `[Add] Edit and remove modifiers and groups`, `[Fix] Guard category and group deletes in use`, `[Fix] Reject stale recipe saves and batch-load catalog collections`.

### Task 20: The till prices from the server

Spec items: SAL-12, CAT-11, F5 (duplicate orders).

**Files:**
- Create: `catalog/web/MenuController.java` or an endpoint on `ProductController`: `GET /api/v1/menu` (branch from `X-Branch-Id`) → active products available at the branch with `effectivePrice`, and each product's active modifier groups/modifiers
- Modify: `sales/web/OrderController.java`, `sales/application/OrderService.java`: `POST /api/v1/orders/quote` (same body as create; returns the computed order without saving); create accepts `clientRequestId` (UUID, required from the POS) — a repeat with the same `(branch, clientRequestId)` returns the existing order (200) instead of creating another
- Create: `backend/src/main/resources/db/migration/V<next>_001__order_client_request_id.sql` (`client_request_id UUID`, unique `(branch_id, client_request_id)` where not null)
- Modify: `frontend/src/app/features/sales/pos.ts` (tiles from `/menu`; cart state in signals; totals from `/orders/quote` debounced 250 ms; Pay creates with a `clientRequestId` kept until the payment succeeds, retries only the payment if it failed; money displayed from server strings, no JS `Number` arithmetic for totals), `api.services.ts`, `models.ts`
- Test: `backend/src/test/java/vn/essvn/erpcafe/sales/QuoteAndIdempotencyTest.java`, `frontend/src/app/features/sales/pos.spec.ts`

- [ ] **Step 1: Failing tests:** `/menu` returns 28,000 for a product with a branch override and omits a product unavailable at the branch; `/orders/quote` equals the later created order's totals; creating twice with one `clientRequestId` returns the same order ID and one row; POS spec: changing search filters tiles, changing a line discount updates the displayed total from the quote response, a failed payment followed by Pay again does not call create a second time.
- [ ] **Step 2:** FAIL. **Step 3:** Implement. **Step 4:** Suite + frontend.
- [ ] **Step 5: Commit** `[Add] Branch menu and order quote endpoints`, `[Fix] Make order creation idempotent from the till`, `[Fix] Price the till from the server`.

### Task 21: Refund each tender, and the orders page

Spec items: SAL-13, SAL-17, SAL-19, SAL-21.

**Files:**
- Modify: `sales/domain/Order.java` (refund/void write one REFUND entry per SALE tender, same method and amount; `OrderStatus` Javadoc: void = the sale was a mistake, refund = the customer returned it; both allowed from PAID and COMPLETED)
- Modify: `sales/web/OrderController.java` (refund/void request no longer needs a method)
- Modify: `frontend/src/app/features/sales/orders.ts` (paging with next/previous; Refund shown with `sales:refund`; Void and Refund both offered for PAID/COMPLETED with the explanation above), `api.services.ts`
- Test: extend `OrderPaymentLifecycleTest`

- [ ] **Step 1: Failing tests:** pay 30,000 card + 16,200 cash, refund → two REFUND entries: card 30,000 and cash 16,200; void the same way; listing page 2 of orders returns the older orders.
- [ ] **Step 2:** FAIL. **Step 3:** Implement. **Step 4:** Suite + frontend.
- [ ] **Step 5: Commit** `[Fix] Refund each original payment method` and `[Add] Paged orders list with correct refund permissions`.

### Task 22: Frontend page fixes

Spec items: CAT-5, CAT-16, CAT-19 (unit dropdown and forms on the products page), INV-5 (display), INV-6, INV-20, INV-22 (UI part), FE-12, FE-15, FE-16, REP-10 (if not already done in Task 16).

**Files:**
- Modify: `frontend/src/app/features/catalog/products.ts` (a failed recipe load shows an error and disables Save; incomplete lines block Save with a message; recipe unit is a select of units in the ingredient's dimension; price override 0 is sent as `"0"`; wrap inputs in a `<form>` with `required`)
- Modify: `frontend/src/app/features/catalog/modifiers.ts` (incomplete delta lines block Save)
- Modify: `frontend/src/app/features/inventory/purchase-orders.ts` (each line shows the ingredient's base unit next to quantity and cost, labelled "per <unit>"; remove the "Receiving converts" hint from `items.ts`; Cancel offered for PARTIALLY_RECEIVED; reload on branch change)
- Modify: `frontend/src/app/features/inventory/stock.ts` (reload on branch change; icons keyed by `PURCHASE_RECEIPT`, `SALE_DEPLETION`, `SALE_REVERSAL`, `ADJUSTMENT`, `WASTE` — use the real enum values; type `MovementResponse.type` as a union)
- Modify: `frontend/src/app/core/models.ts` (remove `LOCKED`; add missing account-lifecycle types), `api.services.ts` (add the account-lifecycle endpoints shipped 2026-09-07: profile, status, password change/reset)
- Modify: `frontend/src/app/shared/modal.ts`, `overlays.ts` (only the topmost overlay handles Escape/Tab — a shared overlay stack)
- Modify: `frontend/src/app/shared/icon.ts` (render SVG through a template instead of `bypassSecurityTrustHtml`)
- Test: `frontend/src/app/features/catalog/products.spec.ts`, `frontend/src/app/shared/overlays.spec.ts`, `frontend/src/app/features/inventory/stock.spec.ts`

- [ ] **Step 1: Failing specs:** products: a failed `getRecipe` shows an error and Save is disabled; a line missing its unit blocks Save; override 0 is sent as `"0"`. Overlays: Escape with a confirm open over a modal closes only the confirm. Stock: switching the active branch reloads the stock list.
- [ ] **Step 2:** FAIL. **Step 3:** Implement. **Step 4:** `npm test`, `npm run lint`, `npx ng build`.
- [ ] **Step 5: Commit** `[Fix] Stop the recipe editor losing data`, `[Fix] Show units on purchase lines and reload pages on branch change`, `[Fix] Close only the top overlay on Escape`, `[Refactor] Sync frontend models with the API`.

---

## Phase 3 — Who can manage whom

### Task 23: One access policy, and a permission sync that respects removals

Spec items: ID-2, CMN-3.

**Files:**
- Create: `identity/domain/AccessPolicy.java` (pure domain service, no Spring dependencies in its logic)
- Modify: `identity/application/UserService.java` (every write: create, update profile/email, status, reset password, grant, revoke), `RoleService.java` (create/update/delete role permissions)
- Create: `backend/src/main/resources/db/migration/V<next>_001__role_permission_catalog_version.sql` (`role.synced_catalog_version INT NOT NULL DEFAULT 0`) and a `Permissions.CATALOG_VERSION` constant with a per-permission "since version" map (every existing permission = version 1)
- Modify: `bootstrap/DataInitializer.java` (`syncDefaultRolePermissions` adds only permissions whose "since" > the role's `synced_catalog_version`, then sets it to `CATALOG_VERSION`)
- Modify: `identity/DefaultRolePermissionSyncTest.java` (its current assertion that removed permissions come back is replaced)
- Test: `backend/src/test/java/vn/essvn/erpcafe/identity/AccessPolicyTest.java` (unit), `backend/src/test/java/vn/essvn/erpcafe/identity/PrivilegeEscalationIntegrationTest.java`

**Interfaces:**
- `AccessPolicy.requireCanGrant(AuthPrincipal actor, Role role, UUID branchId)`: role permissions ⊆ actor permissions AND actor holds `branchId` (403 otherwise). `requireCanManage(AuthPrincipal actor, Set<String> targetPermissions)`: target's permissions ⊆ actor's (403). `requireCanEditRole(AuthPrincipal actor, Set<String> newPermissions)`: ⊆ actor's (403). OWNER passes all.
- Sync rule: a permission is added to a default role once, when it is new to the catalog; an owner's removal survives restarts.

- [ ] **Step 1: Failing tests** (logged in as a MANAGER created through the API): grant self OWNER → 403; grant self a branch the manager does not hold → 403; reset the owner's password → 403; change the owner's email → 403; disable the owner → 403; create a role with a permission the manager lacks → 403; manager grants CASHIER at their own branch → 200. Sync: remove `user:write` from MANAGER, run the sync (call the initializer method), MANAGER still lacks it; bump a test catalog entry to a newer version → it is added.
- [ ] **Step 2:** FAIL. **Step 3:** Implement. **Step 4:** Suite.
- [ ] **Step 5: Commit** `[Fix] Let users grant and manage only within their own permissions` and `[Fix] Keep permission removals across restarts`.

### Task 24: Branch lifecycle and company creation

Spec items: ORG-1, ORG-3, ORG-4 (G19), ORG-5, ORG-6.

**Files:**
- Create: `organization/api/BranchCreated.java`, `BranchDeactivated.java`, `BranchDeleted.java` (records: `branchId`, `companyId`, `actorUserId` where relevant)
- Modify: `organization/application/BranchService.java` (publish events with `ApplicationEventPublisher`; delete refused with 409 when the branch has open orders, IN_PROGRESS shifts or non-zero stock — ask via new published API methods `SalesApi.hasOpenOrders(branchId)`, `StaffApi.hasOpenShifts(branchId)`, `InventoryApi.hasStockOnHand(branchId)`)
- Modify: `organization/api/OrganizationApi.java`: every method takes `companyId` (`findBranch(UUID companyId, UUID branchId)`, `branchExistsInCompany`, `listActiveBranches`, `companyIdOf` stays); update every caller, including `OrderService`'s tax-rate lookup from Task 10
- Create: `identity/application/BranchAccessListener.java` (`@EventListener` in the same transaction: `BranchCreated` → grant the creator the OWNER role at the new branch if the creator is an owner, else the creator's role at the branch they acted from; `BranchDeleted`/`BranchDeactivated` → delete grants for that branch)
- Modify: `identity/application/AuthService.java` (`buildPrincipal` includes only active, not deleted branches — use `OrganizationApi.listActiveBranches(companyId)` to filter)
- Modify: `organization/web/CompanyController.java` / `CompanyService.java` (`POST /companies` removed from the tenant API → 403 for every tenant role; keep company update for the caller's own company); `frontend/src/app/features/organization/companies.ts` (no "New company")
- Test: `backend/src/test/java/vn/essvn/erpcafe/organization/BranchLifecycleIntegrationTest.java`

**Rulings baked in:** events are plain Spring application events handled synchronously inside the publishing transaction (the outbox arrives in Task 33).

- [ ] **Step 1: Failing tests:** owner creates a branch → the owner's next `/auth/me` lists it; delete a branch → a user who had it no longer gets it in `/auth/me` and a request with that branch header → 403; deactivate a branch → same; delete a branch with an open order → 409; tenant `POST /companies` → 403.
- [ ] **Step 2:** FAIL. **Step 3:** Implement. **Step 4:** Suite + frontend.
- [ ] **Step 5: Commit** `[Add] Branch lifecycle events and creator access`, `[Fix] Keep deleted and inactive branches out of access`, `[Remove] Company creation from the tenant API`.

### Task 25: Login hardening

Spec items: ID-3, ID-6, ID-9 (token purge), ID-11, ID-13.

**Files:**
- Modify: `identity/application/AuthService.java` (unknown email: run `passwordEncoder.matches` against a fixed dummy BCrypt hash; normalise email `trim().toLowerCase(Locale.ROOT)` on login, create and update)
- Modify: `identity/application/LoginAttemptService.java` (bounded map — `LinkedHashMap` with eviction or a `ConcurrentHashMap` swept by `@Scheduled`; add a per-address limit alongside per-email)
- Create: `backend/src/main/resources/db/migration/V<next>_001__user_email_lowercase.sql` (lower-case existing emails; replace `uk_user_email` with a unique index on `lower(email)`; index `refresh_token(expires_at)`)
- Create: `identity/application/RefreshTokenPurger.java` (`@Scheduled` daily delete of tokens expired more than 7 days ago; `@EnableScheduling` if not present)
- Modify: `backend/src/main/resources/application.yml` (`server.forward-headers-strategy: framework`)
- Modify: `config/SecurityProperties.java` (move `app.security.login.*` into it)
- Modify: `identity/security/JwtAuthenticationFilter.java` registration (a `FilterRegistrationBean` with `setEnabled(false)` so it runs only in the security chain)
- Test: `backend/src/test/java/vn/essvn/erpcafe/identity/LoginHardeningTest.java`

- [ ] **Step 1: Failing tests:** login with `Admin@EssCafe.VN` works; creating `ADMIN@esscafe.vn` → 409; unknown-email login still invokes the encoder (spy the `PasswordEncoder` bean with `@MockitoSpyBean`); 20 failed logins across different emails from one address → 429; purger deletes an expired token and keeps a live one; the JWT filter runs once per request (count invocations with a spy or log capture).
- [ ] **Step 2:** FAIL. **Step 3:** Implement. **Step 4:** Suite.
- [ ] **Step 5: Commit** `[Fix] Close the login timing leak and bound the throttle`, `[Fix] Treat emails case-insensitively`, `[Add] Purge expired refresh tokens`.

### Task 26: Route guards, lazy routes and tab sync

Spec items: FE-8, FE-10.

**Files:**
- Modify: `frontend/src/app/app.routes.ts` (`loadComponent` for every feature; `canMatch` guard checking the permission each page needs, from `core/permissions.ts`)
- Create: `frontend/src/app/core/permission.guard.ts`
- Modify: `frontend/src/app/core/auth.service.ts` (`storage` event listener picks up tokens refreshed in another tab; refresh is single-flight across tabs with `navigator.locks.request('erpcafe-refresh', …)` when available)
- Test: `frontend/src/app/core/permission.guard.spec.ts`, extend `auth.service.spec.ts`

- [ ] **Step 1: Failing specs:** a cashier matching `/users` is redirected to `/`; an owner matches it; a `storage` event with a new access token updates `AuthService.accessToken()`.
- [ ] **Step 2:** FAIL. **Step 3:** Implement. **Step 4:** `npm test`, lint, build (check the initial bundle shrank).
- [ ] **Step 5: Commit** `[Add] Permission route guards and lazy routes` and `[Fix] Keep tabs on one refresh token`.

### Task 27: Permissions per active branch

Spec item: ID-7 (G07). Depends on Task 24 (creator access) and Task 23.

**Files:**
- Modify: `identity/security/AuthPrincipal.java` (`Map<UUID, Set<String>> permissionsByBranch`; `permissions` becomes the set for the active branch, computed per request)
- Modify: `identity/security/JwtService.java` + `JwtAuthenticationFilter.java` (token carries branch → role IDs; filter resolves the active branch's role permissions — cached per role for 60 s — and sets authorities from them; branchless endpoints (`/users`, `/roles`, `/companies`, `/auth/me`) use the union — ruling: company-level administration needs the union until company-wide grants exist)
- Create: `docs/adr/0007-per-branch-permission-evaluation.md` (decision and the union exception)
- Modify: `frontend/src/app/core/auth.service.ts` + `permissions.ts` (permission checks use the active branch's set)
- Test: `backend/src/test/java/vn/essvn/erpcafe/identity/PerBranchPermissionTest.java`

- [ ] **Step 1: Failing test:** user MANAGER at branch A and CASHIER at branch B: `POST /orders/{id}/refund` with header B → 403; with header A → allowed; `GET /users` → allowed (union rule).
- [ ] **Step 2:** FAIL. **Step 3:** Implement. **Step 4:** Suite + frontend.
- [ ] **Step 5: Commit** `[Fix] Evaluate permissions for the active branch` and `[Docs] ADR-0007 per-branch permission evaluation`.

### Task 28: Staff API ready for extraction, and PIN clocking

Spec items: STF-9, STF-10.

**Files:**
- Modify: `staff/api/StaffApi.java` → `BigDecimal laborCost(UUID companyId, UUID branchId, Instant from, Instant to)` and `Map<UUID, BigDecimal> laborCostByBranch(UUID companyId, Collection<UUID> branchIds, Instant from, Instant to)`; Javadoc states the rule (COMPLETED shifts by clock-out time)
- Create: `backend/src/main/resources/db/migration/V<next>_001__staff_company_and_pin.sql` (`shift.company_id` backfilled from employee; `employee.pin_hash VARCHAR(100)`; drop the foreign keys from `employee`/`shift` to `company`/`branch` — keep indexes)
- Modify: `staff/domain/Employee.java` (`setPin(String rawPin, PasswordEncoder)`, `pinMatches`), `EmployeeController` (`PUT /employees/{id}/pin` needs `staff:write`, 4–6 digits), `ShiftController` clock-in/out require `{ "pin": "1234" }` of the shift's employee → 403 on mismatch; employees without a PIN can still clock (ruling: PIN optional until set, so existing teams keep working)
- Modify: `reporting/application/ReportService.java` (use the batch method)
- Modify: `frontend/src/app/features/staff/shifts.ts`, `team.ts` (PIN prompt, set PIN)
- Test: `backend/src/test/java/vn/essvn/erpcafe/staff/PinClockTest.java`

- [ ] **Step 1: Failing tests:** employee with PIN 1234: clock-in with 0000 → 403; with 1234 → 200; `laborCost` with another company's ID for the branch → zero/404 as specified (404); batch method equals the per-branch sums.
- [ ] **Step 2:** FAIL. **Step 3:** Implement. **Step 4:** Suite + frontend.
- [ ] **Step 5: Commit** `[Add] Employee PIN for clocking in and out` and `[Refactor] Tenant-safe, batchable StaffApi`.

---

## Phase 4 — Make it structural

### Task 29: Schema hygiene

Spec items: CAT-9, INV-14, ORG-7, INV-13, SAL-15, INV-15.

**Files:**
- Create: `backend/src/main/resources/db/migration/V<next>_001__partial_unique_and_indexes.sql`:
  - replace full UNIQUE constraints with partial unique indexes `WHERE deleted = FALSE` on: category `(company_id, name)`, modifier_group `(company_id, name)`, product `(company_id, sku)`, ingredient `(company_id, name)`, supplier `(company_id, name)`, company `(code)`, branch `(company_id, code)` (use the exact current column sets from V1/V3/V4)
  - indexes: `sales_order (branch_id, status, created_at)`; `stock_movement (branch_id, created_at DESC)`, `(branch_id, ingredient_id, created_at DESC)`, `(branch_id, type, created_at)`
  - `order_line.order_id`, `payment.order_id`, `payment.amount`, `order_line.line_total` NOT NULL; CHECK `order_line.quantity > 0`, `payment.amount > 0`
- Modify: sales entity mappings (`@JoinColumn(nullable = false)` on child collections)
- Modify: service pre-checks that query name/sku/code uniqueness to ignore deleted rows
- Modify: `inventory/web/PurchaseOrderController.java` (default sort `createdAt,desc`)
- Test: `backend/src/test/java/vn/essvn/erpcafe/common/SchemaHygieneIntegrationTest.java`

- [ ] **Step 1: Failing tests:** delete then recreate SKU `LAT1` → 201; same for ingredient name, supplier name, branch code, category name; PO list default order is newest first; inserting a payment with amount 0 via JDBC fails the CHECK. (Review Focus 4: the migration runs on a database that already has a soft-deleted duplicate — seed one in a test migration fixture or by inserting rows before recreating, and assert it applies.)
- [ ] **Step 2:** FAIL. **Step 3:** Implement. **Step 4:** Suite.
- [ ] **Step 5: Commit** `[Fix] Let deleted names and codes be reused` and `[Add] Indexes and constraints for sales and the ledger`.

### Task 30: The tenant seam

Spec items: CMN-5 (and the tenant half of ID-8).

**Files:**
- Create: `common/context/TenantContext.java` (thread-bound company ID; `require()` throws `UnauthorizedException`; `runAsSystem(Supplier)` for bootstrap/login paths)
- Modify: `identity/security/JwtAuthenticationFilter.java` (sets and clears `TenantContext` next to `BranchContext`)
- Create: `common/persistence/TenantIdentifierResolver.java` (`CurrentTenantIdentifierResolver<UUID>`; `isRoot` true inside `runAsSystem`), Hibernate config registering it
- Create: `common/domain/TenantOwnedEntity.java` (`@TenantId @Column(name = "company_id")`) and move company-scoped entities onto it (Company-level tables: category, product, modifier_group, ingredient, supplier, item, employee, role, user; branch-scoped tables keep `branch_id` scoping via `BranchScopedEntity` and add `company_id` only if the column already exists)
- Modify: services and repositories: remove `findByIdAndCompanyId`-style finders and `currentUser.require().companyId()` calls that the seam now covers; keep branch scoping
- Modify: `bootstrap/DataInitializer.java`, login/refresh paths (`AuthService`) to run inside `TenantContext.runAsSystem`
- Create: `docs/adr/0008-tenant-seam-hibernate-tenant-id.md`
- Test: `backend/src/test/java/vn/essvn/erpcafe/common/TenantSeamTest.java`; every existing `*TenantIsolation*Test` must stay green

- [ ] **Step 1: Failing test:** with `TenantContext` set to company A, `productRepository.findById(B-product)` returns empty (prove `@TenantId` filters id lookups — if Hibernate does not, add a scoping `repositoryBaseClass` that applies the restriction, and the test proves that instead); saving a new product stamps `company_id` = A even if the entity was built without it; `runAsSystem` sees both companies.
- [ ] **Step 2:** FAIL. **Step 3:** Implement module by module (organization, identity, catalog, inventory, staff), running that module's isolation tests after each. **Step 4:** Full suite.
- [ ] **Step 5: Commit** one per module plus `[Add] Tenant context and Hibernate tenant discriminator` and `[Docs] ADR-0008 tenant seam`.

### Task 31: Row-level security under a non-superuser role

Spec items: CMN-6, INV-7, INV-17 (task B1 of the transformation plan). Makes `support/AppDatabaseRoleTest` green.

**Files:**
- Create: `backend/src/main/resources/db/migration/V<next>_001__app_role_and_rls.sql`: create role `erp_app` (LOGIN, NOSUPERUSER, NOBYPASSRLS) if missing, grant DML on all tables and sequences, default privileges for future tables; enable + force RLS with the V7_002 policy pattern on every company-owned table (`company_id = NULLIF(current_setting('app.company_id', true), '')::uuid`) and on branch-owned tables through their branch; a `bypass` policy for the migration/owner role is not needed (owner uses BYPASSRLS)
- Modify: `backend/docker-compose.yml` + an init script creating `erp_app` with a password; `application-dev.yml`/`application-prod.yml`: `spring.flyway.user/password` = owner role with BYPASSRLS, `spring.datasource.*` = `erp_app`
- Modify: `support/AbstractIntegrationTest.java`: Flyway connects as the container's superuser; the app datasource connects as `erp_app` (created by the migration; password from a test property)
- Modify: move `inventory/application/TenantRlsAspect.java` → `common/persistence/`, and set `app.company_id` at transaction begin for every transaction from `TenantContext` (a `JpaTransactionManager` subclass overriding `doBegin`, replacing the aspect and `RlsConfig`)
- Modify: login, refresh, `DataInitializer`: run on a separate `DataSource`/transaction manager as the owner role (BYPASSRLS) — the minimum set of queries that must see all tenants
- Modify: `backend/src/main/resources/db/migration/V8_001…` **no** — leave it; document in the new migration's header that Flyway now runs as a BYPASSRLS owner, which is what V8 needs
- Test: `support/AppDatabaseRoleTest` (existing) green; `backend/src/test/java/vn/essvn/erpcafe/common/RlsEverywhereTest.java`

- [ ] **Step 1:** Run `AppDatabaseRoleTest` → FAIL (baseline). Write `RlsEverywhereTest`: with `app.company_id` = A, a raw JDBC `SELECT count(*)` on each tenant table returns only A's rows; with no tenant set, 0 rows; login still works; the app starts on an empty database (migrations as owner).
- [ ] **Step 2:** Implement. **Step 3:** Full suite — **zero failures from here on.**
- [ ] **Step 4: Commit** `[Add] Non-superuser app role with row-level security on every tenant table`, `[Refactor] Set the tenant at transaction begin for every transaction`.

### Task 32: Module boundaries

Spec items: INV-16, ID-8.

**Files:**
- Modify: `backend/build.gradle.kts` (test dependency `com.tngtech.archunit:archunit-junit5`, latest 1.x)
- Modify: `identity/api/IdentityApi.java` → `UUID requireUserId()`, `boolean hasPermission(String permission)`; remove the Optional getters
- Modify: every module that imports `identity.security.*` (organization, catalog, inventory, staff, reporting) to use `TenantContext` / `IdentityApi`
- Create: `backend/src/test/java/vn/essvn/erpcafe/ArchitectureTest.java`

**Rules the test enforces:** a module's classes may depend on another module only through `<module>.api..` (plus `common..`); `identity.security..` is used only by `identity..` and `config..`; every `@RestController` public method is annotated `@PreAuthorize` except the documented allow-list (`AuthController` login/refresh/logout/me, `POST /users/me/password`); no class outside `*.persistence..` references a Spring Data repository of another module.

- [ ] **Step 1:** Write `ArchitectureTest` → FAIL on the current `CurrentUser` imports. **Step 2:** Fix the imports and reshape `IdentityApi`. **Step 3:** Suite.
- [ ] **Step 4: Commit** `[Add] Architecture tests for module boundaries` and `[Refactor] Keep identity security internal`.

### Task 33: Events and an event-fed reporting read model

Spec items: REP-9 (and the event half of ORG-5).

**Files:**
- Decide at the start (ledger the ruling): add `org.springframework.modulith:spring-modulith-starter-jpa` + `spring-modulith-events-api` **if** a release compatible with Spring Boot 4.1 resolves; otherwise build a minimal transactional outbox in `common/events/` (`domain_event` table, `DomainEventPublisher` writing in the business transaction, a `@Scheduled` dispatcher delivering to `@EventListener`s after commit, `processed_at`, retries, idempotent consumers keyed by event ID).
- Create: `sales/api/OrderCompleted.java`, `OrderReversed.java` (orderId, companyId, branchId, occurredAt, netSales, taxTotal, cogs), `staff/api/ShiftCompleted.java` (shiftId, companyId, branchId, clockOut, laborCost)
- Create: `reporting/persistence/DailyBranchFigures` entity + table `report_daily_branch (company_id, branch_id, day, order_count, net_sales, tax_total, cogs, labor_cost, PRIMARY KEY (branch_id, day))` via migration, with a backfill from existing orders/shifts
- Create: `reporting/application/ReportProjection.java` (consumes the three events, upserts by day in the reporting zone, idempotent by event ID)
- Modify: `reporting/application/ReportService.java` (summary/sales-by-day/outlets read the projection; no per-branch facade fan-out)
- Create: `docs/adr/0009-event-publication-and-reporting-read-model.md`
- Test: `backend/src/test/java/vn/essvn/erpcafe/reporting/ReportProjectionTest.java`

- [ ] **Step 1: Failing tests:** complete an order → after the dispatcher runs (trigger it directly in the test), the day row has the order's net sales and COGS; the same event delivered twice changes nothing; a refund event on a later day writes negatives on that day; report endpoints return the same figures as Task 16/17 tests (those tests stay green).
- [ ] **Step 2:** FAIL. **Step 3:** Implement. **Step 4:** Suite.
- [ ] **Step 5: Commit** `[Add] Transactional domain events`, `[Add] Event-fed reporting read model`, `[Docs] ADR-0009 events and reporting read model`.

### Task 34: Recipes as a versioned consumption seam

Spec item: CAT-13.

**Files:**
- Create: `inventory/api/ConsumptionResolver.java` (port owned by the core: `List<StockLine> consumptionFor(UUID companyId, UUID productId, int quantity, Set<UUID> modifierIds, UUID recipeVersionId)`); catalog implements it; sales depends on the port, not on `CatalogApi.explode`
- Create: migration: `recipe_version (id, recipe_id, version_no, created_at)` immutable, recipe lines and modifier recipe lines belong to a version; `order_line.recipe_version_id` snapshot; `unit` stored as the canonical `Unit` symbol (validated)
- Modify: `catalog/domain/Recipe`, `RecipeLine`, `ModifierRecipeLine` → one embeddable `ConsumptionLine(ingredientId, quantity, Unit unit)`; saving a recipe creates a new version
- Modify: `sales` completion uses the snapshotted version; Task 11's reversal stays movement-based
- Modify: `CONTEXT.md` (term: recipe = the F&B bill of materials; remove "Avoid: BOM") and `docs/adr/0005-…` note; create `docs/adr/0010-recipes-as-versioned-consumption.md`
- Test: `backend/src/test/java/vn/essvn/erpcafe/catalog/RecipeVersioningTest.java`

- [ ] **Step 1: Failing tests:** an order created under recipe v1 (18 g) and paid after the recipe changes to v2 (20 g) deducts 18 g; saving a recipe twice produces versions 1 and 2; a recipe line with unit `cup` is rejected (Task 14 behaviour kept).
- [ ] **Step 2:** FAIL. **Step 3:** Implement. **Step 4:** Suite.
- [ ] **Step 5: Commit** `[Refactor] Recipes as versioned consumption behind a core port` and `[Docs] ADR-0010 and glossary update`.

### Task 35: Purchase lines reference items and carry their unit

Spec items: INV-18, INV-5 (backend part).

**Files:**
- Create: migration: `purchase_order_line.item_id UUID NULL` (FK to item), `unit VARCHAR(16) NOT NULL` backfilled with the ingredient's base unit, unit cost is per that unit
- Modify: `inventory/domain/PurchaseOrderLine.java`, `PurchaseOrderService.java`, DTOs (a line is either an item — unit and default cost from the item — or an ingredient + unit); `ReceiptRequest.unit` defaults to the line's unit; conversion through the ledger (Task 14)
- Modify: `frontend/src/app/features/inventory/purchase-orders.ts` (pick an item, or an ingredient + unit)
- Test: `backend/src/test/java/vn/essvn/erpcafe/inventory/PurchaseLineUnitTest.java`

- [ ] **Step 1: Failing tests:** a PO line for an item bought per kg at 210,000 → receiving 10 adds 10,000 g at 210 per g; a line created with ingredient + `kg` behaves the same; existing lines (backfilled base unit) receive as before.
- [ ] **Step 2:** FAIL. **Step 3:** Implement. **Step 4:** Suite + frontend.
- [ ] **Step 5: Commit** `[Add] Purchase lines with items and their own unit`.

### Task 36: Remaining authentication gaps

Spec item: ID-10 (G08, G09, G12, G14, G18; G13 ruled below).

**Files:**
- G09: `identity/security/JwtService.java` + `SecurityProperties` (key set `app.security.jwt.keys[]` with `kid`, one marked active; tokens carry `kid`, `iss` = `erp-cafe`, `aud` = `erp-cafe-api`; validation checks all three and accepts any configured key → rotation without logout)
- G08: migration `app_user.auth_version INT NOT NULL DEFAULT 0`; incremented on grant/revoke/role-permission change/status change; tokens carry `av`; the filter compares against a 30 s cached value per user → 401 `UNAUTHORIZED` when stale
- G14: migration `auth_event (id, company_id, user_id, type, ip, occurred_at, detail JSONB)`; recorded for login success/failure, refresh, reuse detection, logout, grant, revoke, password change/reset, status change; `GET /api/v1/auth-events` (`user:read`, company-scoped, paged)
- G18: replace the in-memory throttle store with a `login_attempt` table (key, window_start, count) so replicas share it
- G12: `POST /auth/password-reset/request {email}` (always 202), token (32 random bytes, stored hashed, 30 min, single use) sent through a `MailSender` port with a logging adapter; `POST /auth/password-reset/confirm {token, newPassword}`; frontend "Forgot password" and reset pages
- **G13 ruling (ledger it):** one email across companies needs a company choice at login and conflicts with the planned identity-provider move; it is deferred and recorded as open in the auth spec.
- Test: `backend/src/test/java/vn/essvn/erpcafe/identity/AuthGapsIntegrationTest.java`, extend `JwtServiceTest`

- [ ] **Step 1: Failing tests:** token signed with a retired-but-configured key validates; wrong `aud` → 401; after a role change the old access token → 401; a login failure writes an `auth_event`; throttle counts survive a new `LoginAttemptService` instance (DB-backed); reset request → logging adapter captured a token → confirm sets the password → token reuse → 422.
- [ ] **Step 2:** FAIL. **Step 3:** Implement. **Step 4:** Suite + frontend.
- [ ] **Step 5: Commit** one per gap: `[Add] JWT key rotation with issuer and audience`, `[Fix] Invalidate tokens when access changes`, `[Add] Authentication audit trail`, `[Fix] Share the login throttle across instances`, `[Add] Password reset by email token`.

### Task 37: Refresh token in an HttpOnly cookie

Spec item: FE-14.

**Files:**
- Modify: `identity/web/AuthController.java` (login/refresh set `Set-Cookie: erpcafe_refresh=<token>; HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth` — `Secure` off only in the `dev`/`test` profiles; refresh/logout read the cookie; response bodies no longer contain `refreshToken`; refresh requires header `X-Requested-With: erpcafe`)
- Modify: `frontend/src/app/core/auth.service.ts`, `http.interceptors.ts` (no refresh token in storage; `withCredentials` on auth calls; send the header)
- Test: extend `AuthFlowIntegrationTest`, `RefreshTokenReuseIntegrationTest`; frontend `auth.service.spec.ts`

- [ ] **Step 1: Failing tests:** login response has the cookie with `HttpOnly` and `SameSite=Strict` and no `refreshToken` field; refresh without the header → 403; with cookie + header → new access token and a rotated cookie; reuse detection still revokes the family; frontend never writes a refresh token to `localStorage`.
- [ ] **Step 2:** FAIL. **Step 3:** Implement. **Step 4:** Suite + frontend.
- [ ] **Step 5: Commit** `[Fix] Keep the refresh token in an HttpOnly cookie`.

### Task 38: Cleanup

Spec items: SAL-18, INV-19, INV-22 (backend part), ORG-8, CMN-9, STF-12 (remainder), CAT-19 (remainder: split `products.ts`, shared save handler and status badge helpers).

**Files:**
- Modify: remove duplicated `activeBranch()` (sales, inventory) and `requireCurrentVersion` (extract one helper in `common`); keep `OrganizationApi.findBranch` (Task 10 uses it); remove public `Branch.setCompanyId`; move the organization facade out of `BranchService` into `OrganizationApiImpl`
- Modify: `Unit` conversion results rounded to the ledger's `NUMERIC(19,4)` scale explicitly (HALF_EVEN) with a test; inactive ingredients and suppliers refused on new POs/receipts (422); zero adjustments → 400
- Modify: `Money` validates currency against ISO 4217 (`Currency.getInstance`); `application-prod.yml` disables Swagger (`springdoc.api-docs.enabled: false`, `springdoc.swagger-ui.enabled: false`); OpenAPI bearer scheme
- Modify: frontend: a shared `status-badge` component/pipe using tokens (no hex), a shared save-handler helper, split `products.ts` into list + editor components
- Test: add a test for each backend behaviour change; frontend specs for the badge

- [ ] **Step 1:** Failing tests for the behaviour changes (rounding scale, inactive supplier on PO → 422, zero adjustment → 400, `Money.of(x, "XYZ")` throws). **Step 2:** Implement + refactors. **Step 3:** Suite, `npm test`, lint, build.
- [ ] **Step 4: Commit** grouped: `[Refactor] Remove duplicated branch and version helpers`, `[Fix] Small inventory and money guards`, `[Refactor] Shared badges and save handling in the frontend`.

### Task 39: Documentation truth pass

**Files:**
- Modify: `doc/erp-cafe-auth-spec.md` §9 (close every gap fixed on this branch with its test; G13 stays open with the ruling), `doc/erp-cafe-inventory-spec.md` statuses, `CONTEXT.md` (new terms: tenant context, ledger reference, recipe version, net sales), `doc/erp-transformation-plan.md` (Phase 1 done items), `backend/ARCHITECTURE.md` if it describes changed flows
- No code changes.

- [ ] **Step 1:** For each doc statement touched by this branch, verify against code/tests and update. **Step 2: Commit** `[Docs] Truth pass after the code-review fixes`.
