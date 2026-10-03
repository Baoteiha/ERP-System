# Stock Guard Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Block any sale that would take an ingredient below zero, and show the cashier the problem on the POS before paying.

**Architecture:** The authoritative check lives in `StockLedgerService.deduct`. That method already holds a pessimistic lock on every stock row a completing order uses, so it can refuse atomically: the transaction rolls back, the payment is not recorded and the order stays OPEN. Two read-only helpers on `InventoryApi` answer "what would be short": `shortfalls` serves the price check (`/orders/quote`) and `unavailable` serves the menu flag (`/menu`). Both reuse the ledger's unit conversion. The POS reads both.

**Tech Stack:** Spring Boot 4.1 / Java 21 / Hibernate 7 / PostgreSQL 16 / Testcontainers; Angular 22 / Vitest.

**Spec:** `docs/superpowers/specs/2026-10-03-stock-guard-design.md`

## Global Constraints

**Branch and commits**
- Work on branch `feat/stock-guard`. Never commit to `dev`, `main`, `fix/code-review` or `feat/command-centre-ui`. Never push or merge.
- Commit subjects use the form `[Action] Feature`. **No `Co-Authored-By` trailer and no AI attribution.**
- Stage by path only.
- Never stage any of these:
  - `.DS_Store`, `.claude/`, `.hermes/`, `frontend/.claude/`
  - `README.md`, `ERP-Cafe-Kanban.xlsx`, `docs/interview-guide.html`, `erp-cafe-*.html`
  - the uncommitted Docker files: `docker-compose.yml`, `.env.example`, `.env`, `.gitignore`, `backend/Dockerfile`, `backend/.dockerignore`, `frontend/Dockerfile`, `frontend/.dockerignore`, `frontend/nginx.conf`
- Never touch `git stash`.

**Commands and gates**
- Backend: `cd backend && ./gradlew test`. It needs Docker for Testcontainers. Run the full suite before every backend commit; it must have **zero failures**.
- Frontend: `cd frontend && npm test`, `npm run lint` (0 errors, warnings ≤ 20) and `npx ng build`. All must exit 0 before a frontend commit.
- Report every exit code.

**Code rules**
- Module boundaries: modules reach each other only through `<module>.api`. `ArchitectureTest` enforces this.
- New Java classes get class Javadoc in exactly the form "Class: X" plus one sentence "Purpose: …".
- Money and quantities use `BigDecimal`, never `double`. Ledger quantities have scale 4.
- Error contract: every ProblemDetail carries a string `code`. The new code is `INSUFFICIENT_STOCK` (HTTP 422).

**Tests**
- Never weaken or delete an existing assertion. Tests that complete sales without stock must get stock in their setup.

**Docker safety**
- Only Testcontainers, or a stack you started yourself with `--rm`.
- Never touch the containers named `btmh-*`, `b2b-*`, `erpcafe-*` or `erpcafe-qa-*`.

**Frontend conventions**
- Follow the command-centre conventions: tokens only, accessible names, status shown as text and not colour alone, and `aria-describedby` for reasons.

## Review Focus

1. **Two tills pay for the last unit at the same moment.** Exactly one completes; the other gets 422 `INSUFFICIENT_STOCK`; stock ends at 0. Pinned in Task 1.
2. **A refused payment changes nothing.** The order stays OPEN, there is no payment row, no movement, on-hand is unchanged, and no `ledger_reference` claim survives, so a retry after restocking succeeds. Pinned in Task 1.
3. **An ingredient the branch never stocked.** It blocks, while a product with no recipe sells with no stock at all. Pinned in Task 1.
4. **A modifier that adds an ingredient** (for example, Size L uses more milk). The quote reports it as short even though the tile says available. Pinned in Task 2.
5. **Another till takes the stock between quote and Pay.** The POS shows the server's message inline and refreshes the menu and quote. Pinned in Task 3.

---

### Task 1: Block sales that would go below zero

**Files:**
- Create: `backend/src/main/java/vn/essvn/erpcafe/inventory/api/StockShortfall.java`
- Modify:
  - `common/exception/BusinessRuleException.java`: add an optional code and properties.
  - `common/web/GlobalExceptionHandler.java`: use them.
  - `inventory/application/StockLedgerService.java`: the `deduct` check.
  - `inventory/api/InventoryApi.java`: Javadoc on `deductForOrder`.
- Test:
  - new `backend/src/test/java/vn/essvn/erpcafe/inventory/StockGuardIntegrationTest.java`;
  - the existing tests that complete sales without stock. Seven files touch payments: `reporting/ReportFixtures.java`, `identity/PerBranchPermissionTest.java`, `sales/SalesTenantIsolationTest.java`, `sales/SalesFlowIntegrationTest.java`, `catalog/RecipeVersioningTest.java`, `sales/OrderPaymentLifecycleTest.java` and `inventory/ReverseDeductionIntegrationTest.java`. Grep for others that complete orders.

**Interfaces:**
- **Produces:**
  - `public record StockShortfall(UUID ingredientId, String ingredientName, String unit, BigDecimal required, BigDecimal onHand)` in `inventory.api`. `unit` is the base-unit symbol; `required` and `onHand` are at ledger scale.
  - `BusinessRuleException(String code, String message, Map<String, Object> properties)` plus `getCode()` and `getProperties()`. The existing `BusinessRuleException(String message)` keeps code `BUSINESS_RULE` and empty properties.
  - When an order would go below zero, `StockLedgerService.deduct` throws `new BusinessRuleException("INSUFFICIENT_STOCK", "Not enough stock: <name> needs <required> <unit>, <onHand> left; …", Map.of("shortfalls", List<StockShortfall>))`.
  - The handler answers 422 with `code` = the exception's code, and adds each property (here `shortfalls`) to the ProblemDetail.

- [ ] **Step 1: Write the failing tests** in `StockGuardIntegrationTest`. Follow the existing integration patterns: `AbstractIntegrationTest`, MockMvc, the seeded admin, products with recipes as in `SalesFlowIntegrationTest`. Cover:
  1. **Refused sale.** An order needing more than on-hand: the paying request gets 422 with `$.code == "INSUFFICIENT_STOCK"` and `$.shortfalls[0].ingredientName` / `required` / `onHand` / `unit`, plus `$.detail` containing "Not enough stock". Afterwards:
     - the order status is still `OPEN` and it has no payments;
     - on-hand is unchanged;
     - there are no `SALE_DEPLETION` movements for that order;
     - there is no `ledger_reference` row for `("ORDER", orderId)` (check with `jdbcTemplate`).
  2. **Retry after restock.** After restocking (adjust), the same order is paid again successfully and stock is deducted.
  3. **Exactly enough.** Stock equal to the requirement: the sale completes and on-hand is exactly 0.
  4. **Untracked ingredient.** An ingredient with no stock row at the branch gives 422, and its `onHand` is `0.0000` in shortfalls.
  5. **No recipe.** A product without a recipe completes with no stock at all.
  6. **Every shortfall listed.** An order short on two ingredients lists both.
  7. **Race.** The stock is exactly one unit's worth and two different orders are paid concurrently: two threads, a start latch, each in its own request.
     - Exactly one gets 200/201 with `COMPLETED`; the other gets 422 `INSUFFICIENT_STOCK`.
     - Final on-hand is 0, never negative.
     - Use the repo's existing concurrency test style (look for `CountDownLatch` / `ExecutorService` in `sales/`).
- [ ] **Step 2: Run them and make sure they fail.** Run `./gradlew test --tests '*StockGuardIntegrationTest'`. Expect: the refusal tests fail, because today the sale completes with negative stock.
- [ ] **Step 3: Implement the rule.**
  - **`BusinessRuleException`:** add the field `code` (default `"BUSINESS_RULE"`) and `properties` (an unmodifiable map, default empty), plus the new constructor.
  - **`GlobalExceptionHandler.handleBusinessRule`:** `ProblemDetail pd = problem(UNPROCESSABLE_CONTENT, ex.getCode(), ex.getMessage()); ex.getProperties().forEach(pd::setProperty); return pd;`
  - **`StockLedgerService.deduct`:**
    - **First pass:** after the idempotency claim, lock every normalized line's row with `lockOrCreate`; the `TreeMap` order already gives the global lock order. Compute `newQty` per line and collect a `StockShortfall` for each line where it is below 0. Read ingredient names through the existing ingredient repository, one batched read.
    - **If any shortfall:** throw `BusinessRuleException("INSUFFICIENT_STOCK", message, Map.of("shortfalls", list))` BEFORE writing any quantity or movement. Format the message numbers with `stripTrailingZeros().toPlainString()` (e.g. "101", "51", "1.5").
    - **Otherwise:** run the existing second pass. Remove the warn-but-allow log and its comment.
  - **Javadoc:** update it on `deduct` and `InventoryApi.deductForOrder` to "Refuses (INSUFFICIENT_STOCK) when any ingredient would go below zero; nothing moves."
  - **Error contract test:** if `ErrorContractIntegrationTest` (or similar) enumerates codes, add `INSUFFICIENT_STOCK`.
- [ ] **Step 4: Fix the fixtures.** Run the full suite. For every existing test now failing because a sale has no stock, add stock in its setup (e.g. an `/stock/adjust` call or the repo helper the tests already use) sized for the test. Never change what an assertion checks. List each touched test in the report with the reason.
- [ ] **Step 5: Verify.** `./gradlew test` passes with zero failures. Report the exit code and the test count.
- [ ] **Step 6: Commit.** Use the subject `[Fix] Block sales that would take stock below zero`. Stage the new and changed files by path.

### Task 2: Shortfalls in the price check and availability on the menu

**Files:**
- Modify:
  - `inventory/api/InventoryApi.java`, `inventory/application/InventoryApiImpl.java` and `inventory/application/StockLedgerService.java`: the two read-only helpers.
  - `inventory/api/ConsumptionResolver.java` and `catalog/application/CatalogConsumptionResolver.java`: the batched "one of each" consumption.
  - `sales/application/OrderService.java`: the quote result with shortfalls.
  - `sales/web/OrderController.java` and `sales/web/OrderDtos.java`: `stockShortfalls` on the quote response.
  - `catalog/application/MenuService.java`, `catalog/web/MenuController.java` and `catalog/web/MenuDtos.java`: `available` on the menu.
- Test:
  - new `backend/src/test/java/vn/essvn/erpcafe/sales/QuoteShortfallIntegrationTest.java`;
  - new `backend/src/test/java/vn/essvn/erpcafe/catalog/MenuAvailabilityIntegrationTest.java`.

**Interfaces:**
- **Consumes (from Task 1):** `StockShortfall`.
- **Produces:**
  - **`InventoryApi.shortfalls(UUID branchId, List<StockLine> lines)` → `List<StockShortfall>`.**
    - It is read-only: no lock, no claim, no write.
    - It normalizes through the ledger's conversion (base units, merged per ingredient).
    - A missing stock row counts as 0. An unknown or foreign ingredient is treated as missing, the same as `normalize` does.
    - It returns an empty list when the lines can be fulfilled.
  - **`InventoryApi.unavailable(UUID branchId, Map<UUID, List<StockLine>> linesByKey)` → `Set<UUID>`.**
    - It returns the keys whose lines can't be fulfilled from current stock. Keys are evaluated independently, not cumulatively.
    - It makes **one** stock read for all ingredients across all keys.
  - **`ConsumptionResolver.oneOfEach(UUID companyId, Collection<UUID> productIds)` → `Map<UUID, List<StockLine>>`.** This is the consumption of one unit of each product at its current recipe version, with no modifiers. Products without a recipe are absent from the map. The recipes are read in batched queries, not per product.
  - **Quote response:**
    - `OrderService.quote` returns `QuoteResult(Order order, List<StockShortfall> shortfalls)`, where the shortfalls are `inventoryApi.shortfalls(branchId, stockLinesFor(order))`.
    - `OrderDtos.OrderResponse` gains `List<StockShortfallDto> stockShortfalls` as the last component. Jackson's `non_null` setting means it is set only by the quote endpoint and is `null` (omitted) elsewhere.
    - `StockShortfallDto(UUID ingredientId, String ingredientName, String unit, BigDecimal required, BigDecimal onHand)`.
  - **Menu:**
    - `MenuDtos.MenuProduct` gains `boolean available` as its last component.
    - `MenuService.MenuEntry` gains `boolean available`.
    - `MenuService.menu()` computes it with `oneOfEach` plus `inventoryApi.unavailable`.

- [ ] **Step 1: Write the failing tests.**
  - **Quote:**
    - A cart within stock gives `stockShortfalls == []`.
    - A cart over stock lists the shortfall with figures. The quote is still 200 and nothing is persisted: no order and no movement.
    - **Modifier case.** A product whose base recipe fits but a selected modifier adds an ingredient that is out of stock: the quote lists that ingredient.
    - The `POST /orders` (create) response has no `stockShortfalls` key.
  - **Menu:**
    - `available` is false exactly for products whose one-of-each consumption exceeds stock, including untracked ingredients.
    - It is true for products without a recipe.
    - Selling down to 0 then flips `available` to false.
  - **Batching:** assert the stock read happens once. Count SQL statements with Hibernate statistics, or a `DataSource` proxy if the repo has one; otherwise assert through a spy on the stock repository in an `@SpringBootTest`. State which approach you used.
- [ ] **Step 2: Run them and make sure they fail.** Run the new test classes and expect failures from the missing fields and methods.
- [ ] **Step 3: Implement.**
  - **Stock reads:** share the conversion code in `StockLedgerService` (`normalize`) for both read-only helpers. The stock read is `stockItemRepository.findByBranchIdAndIngredientIdIn(branchId, ids)`; add it if missing.
  - **ArchUnit:** keep it green. Catalog may depend on `inventory.api`; it already implements `ConsumptionResolver` from there.
- [ ] **Step 4: Verify.** The full `./gradlew test` passes with zero failures. Report the exit code and the count.
- [ ] **Step 5: Commit.** Use the subject `[Add] Stock shortfalls on the price check and availability on the menu`.

### Task 3: POS shows stock problems and handles a refusal

**Files:**
- Modify:
  - `frontend/src/app/core/models.ts`:
    - `MenuProduct.available: boolean`;
    - `OrderResponse.stockShortfalls?: StockShortfall[]`;
    - `export interface StockShortfall { ingredientId: string; ingredientName: string; unit: string; required: string; onHand: string }`.
  - `frontend/src/app/features/sales/pos.ts`
  - `frontend/src/app/features/sales/pos.spec.ts`
- Check: `frontend/src/app/features/sales/orders.ts`. "Add payment" errors already reach the user through the error interceptor's toast with the server's `detail`. Confirm that an `INSUFFICIENT_STOCK` refusal shows its detail, and add a spec if needed.

**Interfaces:**
- **Consumes:** Task 2's `/menu` `available` and the quote's `stockShortfalls`; Task 1's `INSUFFICIENT_STOCK` ProblemDetail with `shortfalls`.

- [ ] **Step 1: Write the failing specs** in `pos.spec.ts`:
  1. **Unavailable product.** A menu product with `available: false` renders its tile with the visible text "Out of stock". The button is `disabled`, its accessible name contains "out of stock", and clicking it does not add a line.
  2. **Quote shortfalls.** A quote response with `stockShortfalls` renders a `role="alert"` block listing "Bánh croissant: needs 101 pc, 51 left". Format the numbers with the `qty` pipe. The Pay button is disabled and has `aria-describedby` pointing at a visible "Not enough stock" reason.
  3. **Shortfalls cleared.** A later quote with no shortfalls removes the block and enables Pay.
  4. **Refusal at Pay.** The payment answers 422 `INSUFFICIENT_STOCK` with a `detail`. The detail shows inline in the order panel (in addition to any toast), the menu is re-requested, and the cart is re-quoted.
  5. **Reload after a sale.** After a successful sale (PAID or COMPLETED), the menu is re-requested.
- [ ] **Step 2: Run them and make sure they fail.** `npm test` fails on the new specs.
- [ ] **Step 3: Implement** in `pos.ts`.
  - **Tiles:** add the disabled state to `.pos-tile`. Keep the visible "Out of stock" text, and use a muted style with the `--text-muted` token.
  - **Shortfall block:** red tint via `--red-soft` / `--red` and text.
  - **Inline refusal:** keep it in a signal that clears when the cart changes.
  - **Styles:** POS component styles are near the 6 kB cap. Put new shared rules in `styles.scss` under `/* ---- screens: pos */`.
- [ ] **Step 4: Visual check.** A dev server with sample data runs at http://localhost:4301 against the QA backend, on the old backend build. The backend changes are not deployed there, so the menu has no `available` field and the quote has no shortfalls. Verify the visuals by intercepting the `/menu` and `/orders/quote` responses with puppeteer request interception. Use a throwaway script in `/private/tmp/claude-501/-Users-phamngocbao-Documents-Personal-Project-ERP-Cafe/dcc2a75d-66e9-46b7-b7ef-a8d8c55df261/scratchpad/vqa/`; `shoot.mjs` and `ff-*.mjs` there are examples. Take a shot in both themes and at 400px. Do not start or stop servers or Docker.
- [ ] **Step 5: Verify.** `npm test`, `npm run lint` and `npx ng build` all exit 0.
- [ ] **Step 6: Commit.** Use the subject `[Add] POS shows out-of-stock products and blocks short carts`.

### Task 4: Docs

**Files:**
- Modify:
  - `doc/erp-cafe-inventory-spec.md`: the negative-stock / warn-but-allow statements.
  - `backend/ARCHITECTURE.md`: the error-code list, adding `INSUFFICIENT_STOCK`, and any "warn but allow" sentence.
  - `CONTEXT.md`: add "stock shortfall" to the glossary if the glossary lists inventory terms.

- [ ] **Step 1:** Grep for `negative`, `warn-but-allow`, `warn but allow` and `go negative` across `doc/`, `docs/`, `backend/ARCHITECTURE.md` and `CONTEXT.md`. Update each statement to the new rule:
  - sales are refused below zero with `INSUFFICIENT_STOCK`;
  - waste and adjustments are unchanged;
  - untracked counts as 0.

  Cite the code or test for each change in the report. Do NOT edit `docs/interview-guide.html` (the user's file) or the `erp-cafe-*.html` reports.
- [ ] **Step 2: Commit.** Use the subject `[Docs] Sales stop at zero stock`.
