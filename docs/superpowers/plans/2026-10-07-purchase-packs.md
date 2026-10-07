# Purchase Packs, Goods Receipts and Retail Purchases Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let suppliers' items declare one pack ("1 hộp = 100 pc"). Then:
- purchase orders are placed in whole packs, from supplier items only;
- each delivery is recorded as a goods receipt, with damaged units written off at the price paid,
  over-deliveries charged or free, and replacements received;
- a one-step retail purchase is added for buying at a shop.

**Architecture:** Packs live on `item` and are copied onto `purchase_order_line`. A line counts its
quantity and price in its *ordering unit*: the pack when it has one, otherwise its unit.

A pure value class, `ReceiptSplit`, turns one delivered line into:
- what the order counts;
- what enters stock, at cost and free;
- what was lost.

The new `GoodsReceivingService` writes `goods_receipt` rows and calls the **unchanged**
`StockLedgerService.receive` with only the usable units. Retail purchases reuse create → send →
receive in one transaction.

On the frontend:
- an `ItemFormComponent` is shared by the Items screen and the purchase forms;
- a `PurchaseLinesComponent` line editor is shared by New PO and Retail purchase;
- a receive drawer and a replacement dialog are added;
- `purchase-math.ts` mirrors the server's rules for live previews.

**Tech Stack:** Java 21, Spring Boot 4.1, Spring Data JPA (Hibernate), Flyway, PostgreSQL 16,
MockMvc + Testcontainers, MapStruct; Angular 22 standalone components with signals, Vitest through
`ng test`.

**Spec:** `docs/superpowers/specs/2026-10-07-purchase-packs-design.md`

## Global Constraints

- Work directly on branch `dev`. Commit after every task. Never push.
- Commit subjects use the form `[Action] Feature`, with Action one of Add, Change, Fix, Test,
  Refactor, Docs. Do **not** add a `Co-Authored-By` trailer.
- Every **new** Java class or record (nested ones too) gets exactly this Javadoc:
  `/**\n * Class: <Name>\n * Purpose: <one sentence>.\n */`. Do not rewrite existing Javadoc beyond
  the sentences this plan names.
- Every controller method has `@PreAuthorize("hasAuthority('<one permission>')")`, naming one known
  permission. `ArchitectureTest` enforces this.
- `StockLedgerService` is not modified.
- Money is stored at `Money.SCALE` (4 decimal places), rounding HALF_EVEN. Divisions run at
  `Unit.CALC_SCALE` (10) before storage.
- The unit vocabulary stays mg, g, kg, ml, cl, l, pc. A pack is a label of at most 32 characters
  over a quantity of the item's own unit (ADR-0001).
- A line's ordering unit is its pack when it has one, otherwise its `unit`. `ordered_qty`,
  `received_qty` and `unit_cost` are in that ordering unit.
- New stock movements from receiving use `ref_type = 'GOODS_RECEIPT'` and `ref_id` = the receipt id.
- The frontend UI copy is English.
- Commands:
  - backend tests: `cd backend && ./gradlew test --tests '<fully.qualified.Class>'`
  - full backend suite: `cd backend && ./gradlew test --rerun`
  - one frontend spec: `cd frontend && npx ng test --watch=false --include <path>`
  - full frontend suite: `cd frontend && npm test`

  Docker must be running for backend tests.

## Review Focus

1. **A whole box arrives ruined** (damaged equals every unit delivered). Nothing enters stock and no
   zero-quantity ledger call is made. The box still counts toward the order, and the loss is the full
   box price. Pinned in Task 5, `wholeBoxDamaged_entersNothing_butCountsTheBox`.
2. **Double-clicking "Confirm receipt" on the last outstanding quantity.** The second request is
   refused because the order is RECEIVED, and stock enters once. Pinned in Task 5,
   `secondReceipt_onAFullyReceivedOrder_isRefused`.
3. **The item's pack is edited between ordering and receiving.** Receiving uses the order's copy.
   Pinned in Task 5, `packEditedAfterOrdering_receivesWithTheOrdersPack`.
4. **Two replacement lines in one request** for the same lost line, together above the loss. Refused,
   and nothing enters stock. Pinned in Task 6,
   `twoReplacementsForOneLine_areCappedTogether_andNothingEnters`.
5. **The item form first renders with no ingredient, then an ingredient counted in `pc` is picked.**
   The unit list shows only `pc` (the screenshot bug). Pinned in Task 9,
   `offersOnlyTheIngredientsDimension_afterPickingIt`.

---

## File map

**Backend: create**
- `backend/src/main/resources/db/migration/V29_001__purchase_packs_and_goods_receipts.sql`
- `backend/src/main/java/vn/essvn/erpcafe/inventory/domain/PurchaseOrderKind.java`
- `backend/src/main/java/vn/essvn/erpcafe/inventory/domain/GoodsReceiptKind.java`
- `backend/src/main/java/vn/essvn/erpcafe/inventory/domain/OverDeliveryKind.java`
- `backend/src/main/java/vn/essvn/erpcafe/inventory/domain/ReceiptSplit.java`
- `backend/src/main/java/vn/essvn/erpcafe/inventory/domain/GoodsReceipt.java`
- `backend/src/main/java/vn/essvn/erpcafe/inventory/domain/GoodsReceiptLine.java`
- `backend/src/main/java/vn/essvn/erpcafe/inventory/persistence/GoodsReceiptRepository.java`
- `backend/src/main/java/vn/essvn/erpcafe/inventory/application/GoodsReceivingService.java`
- Tests:
  - `backend/src/test/java/vn/essvn/erpcafe/inventory/ItemPackTest.java`
  - `backend/src/test/java/vn/essvn/erpcafe/inventory/domain/ReceiptSplitTest.java`
  - `backend/src/test/java/vn/essvn/erpcafe/inventory/PackReceivingTest.java`
  - `backend/src/test/java/vn/essvn/erpcafe/inventory/ReceivingCompensationTest.java`
  - `backend/src/test/java/vn/essvn/erpcafe/inventory/RetailPurchaseTest.java`

**Backend: modify**
- Domain: `inventory/domain/Item.java`, `PurchaseOrder.java`, `PurchaseOrderLine.java`
- Application: `inventory/application/ItemService.java`, `PurchaseOrderService.java`,
  `PurchaseInputs.java`
- Persistence: `inventory/persistence/ItemRepository.java`
- Web: `inventory/web/ItemDtos.java`, `ItemController.java`, `PurchaseOrderDtos.java`,
  `PurchaseOrderController.java`
- Identity: `identity/domain/Permissions.java`, `identity/domain/DefaultRole.java`
- Tests:
  - `support/AbstractIntegrationTest.java`, `common/RlsEverywhereTest.java`,
    `identity/DefaultRolePermissionSyncTest.java`
  - eleven tests that buy stock through ingredient lines (Task 3)

**Frontend: create** (`frontend/src/app/…`)
- `features/inventory/purchase-math.ts` (+ `.spec.ts`)
- `features/inventory/item-form.ts` (+ `.spec.ts`)
- `features/inventory/purchase-lines.ts` (+ `.spec.ts`)
- `features/inventory/receive-drawer.ts` (+ `.spec.ts`)
- `features/inventory/compensation-dialog.ts`
- `features/inventory/retail-purchase.ts` (+ `.spec.ts`)

**Frontend: modify**
- `core/units.ts`, `core/models.ts`, `core/api.services.ts`
- `features/inventory/items.ts`
- `features/inventory/purchase-orders.ts` (+ `.spec.ts`)
- `features/inventory/stock.ts` (+ `.spec.ts`)
- `layout/nav.ts`, `layout/command-palette.ts`, `app.routes.ts`

**Docs:** `CONTEXT.md`, `docs/adr/0001-no-per-ingredient-unit-conversion-table.md`,
`docs/adr/0006-conversion-at-the-ledger-seam.md`

---

### Task 1: Schema: packs, order kind, goods receipts, row-level security

**Files:**
- Create: `backend/src/main/resources/db/migration/V29_001__purchase_packs_and_goods_receipts.sql`
- Modify: `backend/src/test/java/vn/essvn/erpcafe/common/RlsEverywhereTest.java`

**Interfaces:**
- Produces:
  - tables `goods_receipt` and `goods_receipt_line`;
  - columns `item.pack_label`, `item.pack_size`, `purchase_order_line.pack_label`,
    `purchase_order_line.pack_size` and `purchase_order.kind`;
  - the unique index `uk_item_supplier_ingredient_unit_pack`.

- [ ] **Step 1: Make the RLS test expect the new tables**

In `RlsEverywhereTest`, add `"goods_receipt"` to the branch-owned list:

```java
        for (String table : List.of("user_branch_access", "product_branch_availability", "stock_item",
                "stock_movement", "purchase_order", "sales_order", "ledger_reference", "goods_receipt")) {
            COMPANY_OF.put(table, BRANCH_COMPANY);
        }
```

Directly after the `purchase_order_line` entry, add:

```java
        COMPANY_OF.put("goods_receipt_line", "(SELECT b.company_id FROM goods_receipt r"
                + " JOIN branch b ON b.id = r.branch_id WHERE r.id = t.goods_receipt_id)");
```

In `seedEveryTable()`, replace the `purchase_order_line` insert:

```java
        db.update("INSERT INTO purchase_order_line (id, version, purchase_order_id, ingredient_id, ordered_qty, unit)"
                + " VALUES (?, 0, ?, ?, 1000, 'g')", UUID.randomUUID(), purchaseOrder, ingredient);
```

with:

```java
        UUID purchaseLine = UUID.randomUUID();
        db.update("INSERT INTO purchase_order_line (id, version, purchase_order_id, ingredient_id, ordered_qty, unit)"
                + " VALUES (?, 0, ?, ?, 1000, 'g')", purchaseLine, purchaseOrder, ingredient);
        UUID goodsReceipt = UUID.randomUUID();
        db.update("INSERT INTO goods_receipt (id, version, branch_id, purchase_order_id, kind, " + audit
                + "?, 0, ?, ?, 'DELIVERY', now(), now())", goodsReceipt, branch, purchaseOrder);
        db.update("INSERT INTO goods_receipt_line (id, version, goods_receipt_id, purchase_order_line_id,"
                + " delivered_qty, unit_cost, cost_per_unit, currency) VALUES (?, 0, ?, ?, 1000, 1, 1, 'VND')",
                UUID.randomUUID(), goodsReceipt, purchaseLine);
```

- [ ] **Step 2: Run it and watch it fail**

Run: `cd backend && ./gradlew test --tests 'vn.essvn.erpcafe.common.RlsEverywhereTest'`
Expected: FAIL. The seed throws `relation "goods_receipt" does not exist`, and
`everyTableACompanyCanOwn_isUnderForcedRowLevelSecurity` fails on the missing tables.

- [ ] **Step 3: Write the migration**

Create `V29_001__purchase_packs_and_goods_receipts.sql`:

```sql
-- Purchase packs, goods receipts and retail purchases (spec 2026-10-07).
--   * item.pack_label / pack_size: an optional pack, a label over a quantity of the item's own unit
--     (ADR-0001). Both or neither.
--   * purchase_order_line.pack_label / pack_size: the item's pack copied when the order was placed. A
--     line with a pack counts ordered_qty, received_qty and unit_cost in packs.
--   * purchase_order.kind: ORDER, or RETAIL for a purchase saved already received.
--   * goods_receipt / goods_receipt_line: one receipt per delivery or replacement received.

ALTER TABLE item ADD COLUMN pack_label VARCHAR(32);
ALTER TABLE item ADD COLUMN pack_size NUMERIC(19,4);
ALTER TABLE item ADD CONSTRAINT ck_item_pack CHECK (
    (pack_label IS NULL AND pack_size IS NULL) OR (pack_label IS NOT NULL AND pack_size > 0));

-- One live row per way of buying a thing: a box of 100 and loose pieces from one supplier are two ways.
DROP INDEX uk_item_supplier_ingredient_unit;
CREATE UNIQUE INDEX uk_item_supplier_ingredient_unit_pack ON item
    (supplier_id, ingredient_id, unit, COALESCE(lower(pack_label), ''), COALESCE(pack_size, 0))
    WHERE deleted = FALSE;

ALTER TABLE purchase_order_line ADD COLUMN pack_label VARCHAR(32);
ALTER TABLE purchase_order_line ADD COLUMN pack_size NUMERIC(19,4);
ALTER TABLE purchase_order_line ADD CONSTRAINT ck_pol_pack CHECK (
    (pack_label IS NULL AND pack_size IS NULL) OR (pack_label IS NOT NULL AND pack_size > 0));

ALTER TABLE purchase_order ADD COLUMN kind VARCHAR(16) NOT NULL DEFAULT 'ORDER';
ALTER TABLE purchase_order ADD CONSTRAINT ck_purchase_order_kind CHECK (kind IN ('ORDER', 'RETAIL'));

CREATE TABLE goods_receipt (
    id                UUID         NOT NULL,
    version           BIGINT       NOT NULL,
    branch_id         UUID         NOT NULL,
    purchase_order_id UUID         NOT NULL,
    kind              VARCHAR(16)  NOT NULL,
    note              VARCHAR(255),
    created_at        TIMESTAMPTZ  NOT NULL,
    created_by        VARCHAR(255),
    updated_at        TIMESTAMPTZ  NOT NULL,
    updated_by        VARCHAR(255),
    CONSTRAINT pk_goods_receipt PRIMARY KEY (id),
    CONSTRAINT fk_goods_receipt_branch FOREIGN KEY (branch_id) REFERENCES branch (id),
    CONSTRAINT fk_goods_receipt_order FOREIGN KEY (purchase_order_id) REFERENCES purchase_order (id),
    CONSTRAINT ck_goods_receipt_kind CHECK (kind IN ('DELIVERY', 'COMPENSATION'))
);
CREATE INDEX idx_goods_receipt_order ON goods_receipt (purchase_order_id);

CREATE TABLE goods_receipt_line (
    id                     UUID          NOT NULL,
    version                BIGINT        NOT NULL,
    goods_receipt_id       UUID          NOT NULL,
    purchase_order_line_id UUID          NOT NULL,
    delivered_qty          NUMERIC(19,4) NOT NULL,
    damaged_qty            NUMERIC(19,4) NOT NULL DEFAULT 0,
    loss_qty               NUMERIC(19,4) NOT NULL DEFAULT 0,
    over_delivered_qty     NUMERIC(19,4) NOT NULL DEFAULT 0,
    over_delivery_kind     VARCHAR(16),
    unit_cost              NUMERIC(19,4) NOT NULL,
    currency               VARCHAR(3)    NOT NULL,
    cost_per_unit          NUMERIC(19,4) NOT NULL,
    compensated_qty        NUMERIC(19,4) NOT NULL DEFAULT 0,
    replacement_qty        NUMERIC(19,4) NOT NULL DEFAULT 0,
    compensates_line_id    UUID,
    note                   VARCHAR(255),
    CONSTRAINT pk_goods_receipt_line PRIMARY KEY (id),
    CONSTRAINT fk_grl_receipt FOREIGN KEY (goods_receipt_id) REFERENCES goods_receipt (id),
    CONSTRAINT fk_grl_po_line FOREIGN KEY (purchase_order_line_id) REFERENCES purchase_order_line (id),
    CONSTRAINT fk_grl_compensates FOREIGN KEY (compensates_line_id) REFERENCES goods_receipt_line (id),
    CONSTRAINT ck_grl_over_delivery CHECK ((over_delivered_qty = 0 AND over_delivery_kind IS NULL)
        OR (over_delivered_qty > 0 AND over_delivery_kind IN ('CHARGED', 'FREE'))),
    CONSTRAINT ck_grl_quantities CHECK (delivered_qty >= 0 AND damaged_qty >= 0 AND replacement_qty >= 0
        AND loss_qty >= 0 AND loss_qty <= damaged_qty AND compensated_qty >= 0 AND compensated_qty <= loss_qty)
);
CREATE INDEX idx_grl_receipt ON goods_receipt_line (goods_receipt_id);

ALTER TABLE goods_receipt ENABLE ROW LEVEL SECURITY;
ALTER TABLE goods_receipt FORCE ROW LEVEL SECURITY;
CREATE POLICY goods_receipt_tenant_isolation ON goods_receipt
    USING (branch_id IN (SELECT b.id FROM branch b));

ALTER TABLE goods_receipt_line ENABLE ROW LEVEL SECURITY;
ALTER TABLE goods_receipt_line FORCE ROW LEVEL SECURITY;
CREATE POLICY goods_receipt_line_tenant_isolation ON goods_receipt_line
    USING (EXISTS (SELECT 1 FROM goods_receipt p WHERE p.id = goods_receipt_line.goods_receipt_id));
```

- [ ] **Step 4: Run it and watch it pass**

Run: `cd backend && ./gradlew test --tests 'vn.essvn.erpcafe.common.RlsEverywhereTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/resources/db/migration/V29_001__purchase_packs_and_goods_receipts.sql \
        backend/src/test/java/vn/essvn/erpcafe/common/RlsEverywhereTest.java
git commit -m "[Add] Pack, order kind and goods receipt schema under row-level security"
```

---

### Task 2: Item packs

**Files:**
- Modify: `backend/src/main/java/vn/essvn/erpcafe/inventory/domain/Item.java`
- Modify: `backend/src/main/java/vn/essvn/erpcafe/inventory/application/ItemService.java`
- Modify: `backend/src/main/java/vn/essvn/erpcafe/inventory/persistence/ItemRepository.java`
- Modify: `backend/src/main/java/vn/essvn/erpcafe/inventory/web/ItemDtos.java`
- Modify: `backend/src/main/java/vn/essvn/erpcafe/inventory/web/ItemController.java`
- Test: `backend/src/test/java/vn/essvn/erpcafe/inventory/ItemPackTest.java`

**Interfaces:**
- Consumes: the item pack columns from Task 1.
- Produces:
  - `Item.getPackLabel(): String`, `Item.getPackSize(): BigDecimal`, `Item.hasPack(): boolean`,
    `Item.setPack(String, BigDecimal)`;
  - JSON fields `packLabel` and `packSize` on the item request and response.

- [ ] **Step 1: Write the failing test**

Create `ItemPackTest.java`:

```java
package vn.essvn.erpcafe.inventory;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;

import vn.essvn.erpcafe.support.AbstractIntegrationTest;

/**
 * Class: ItemPackTest
 * Purpose: An item may declare one pack, both halves or neither, and a box and loose pieces are two ways of buying.
 */
class ItemPackTest extends AbstractIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    private String token;
    private String branchId;

    @BeforeEach
    void login() throws Exception {
        token = adminToken();
        branchId = ((List<String>) JsonPath.read(
                mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, authHeader(token)))
                        .andReturn().getResponse().getContentAsString(), "$.branchIds")).get(0);
    }

    private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder b) {
        return b.header(HttpHeaders.AUTHORIZATION, authHeader(token)).header("X-Branch-Id", branchId);
    }

    private ResultActions send(MockHttpServletRequestBuilder b, String body) throws Exception {
        return mockMvc.perform(authed(b).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String id(ResultActions result) throws Exception {
        return JsonPath.read(result.andExpect(status().is2xxSuccessful()).andReturn().getResponse()
                .getContentAsString(), "$.id");
    }

    private String straws() throws Exception {
        return id(send(post("/api/v1/ingredients"),
                "{\"name\":\"PackStraws%d\",\"baseUnit\":\"pc\"}".formatted(SEQ.incrementAndGet())));
    }

    private String supplier() throws Exception {
        return id(send(post("/api/v1/suppliers"), "{\"name\":\"PackShop%d\"}".formatted(SEQ.incrementAndGet())));
    }

    private String itemBody(String supplierId, String ingredientId, String pack) {
        return "{\"name\":\"Ống hút\",\"supplierId\":\"%s\",\"ingredientId\":\"%s\",\"unit\":\"pc\"%s}"
                .formatted(supplierId, ingredientId, pack);
    }

    @Test
    void itemWithAPack_keepsBothHalves() throws Exception {
        send(post("/api/v1/items"), itemBody(supplier(), straws(), ",\"packLabel\":\" hộp \",\"packSize\":100"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.packLabel").value("hộp"))
                .andExpect(jsonPath("$.packSize").value(100.0));
    }

    @Test
    void halfAPack_isRefused() throws Exception {
        String supplierId = supplier();
        String ingredientId = straws();
        for (String pack : List.of(",\"packLabel\":\"hộp\"", ",\"packSize\":100",
                ",\"packLabel\":\"hộp\",\"packSize\":0", ",\"packLabel\":\"  \",\"packSize\":100")) {
            send(post("/api/v1/items"), itemBody(supplierId, ingredientId, pack))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.detail").value(
                            org.hamcrest.Matchers.containsString("both a name and a size above 0")));
        }
    }

    @Test
    void aBoxAndLoosePieces_areTwoWaysOfBuying_butTheSameBoxTwiceIsNot() throws Exception {
        String supplierId = supplier();
        String ingredientId = straws();
        id(send(post("/api/v1/items"), itemBody(supplierId, ingredientId, "")));
        id(send(post("/api/v1/items"), itemBody(supplierId, ingredientId, ",\"packLabel\":\"hộp\",\"packSize\":100")));

        send(post("/api/v1/items"), itemBody(supplierId, ingredientId, ",\"packLabel\":\"HỘP\",\"packSize\":100"))
                .andExpect(status().isConflict());
        send(post("/api/v1/items"), itemBody(supplierId, ingredientId, ""))
                .andExpect(status().isConflict());
    }

    @Test
    void editingAPack_changesIt_andClearingBothHalvesRemovesIt() throws Exception {
        String supplierId = supplier();
        String ingredientId = straws();
        String itemId = id(send(post("/api/v1/items"),
                itemBody(supplierId, ingredientId, ",\"packLabel\":\"hộp\",\"packSize\":100")));

        send(put("/api/v1/items/" + itemId),
                itemBody(supplierId, ingredientId, ",\"packLabel\":\"hộp\",\"packSize\":50,\"version\":0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.packSize").value(50.0));
        send(put("/api/v1/items/" + itemId), itemBody(supplierId, ingredientId, ",\"version\":1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.packLabel").doesNotExist());

        mockMvc.perform(authed(get("/api/v1/items/" + itemId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.packSize").doesNotExist());
    }
}
```

Note: the API omits null fields (`spring.jackson.default-property-inclusion: non_null`), so a cleared
pack has no `packLabel` or `packSize` key. Assert this with `jsonPath(...).doesNotExist()`; never read it
with `JsonPath.read`, which throws on a missing path.

- [ ] **Step 2: Run it and watch it fail**

Run: `cd backend && ./gradlew test --tests 'vn.essvn.erpcafe.inventory.ItemPackTest'`
Expected: FAIL. `packLabel` is absent from responses, and the half-pack bodies return 201.

- [ ] **Step 3: Add the pack to `Item`**

In `Item.java`, add `import java.math.BigDecimal;`. In the class Javadoc, replace the two sentences
"Packaging ("25 kg sack") is deliberately absent: it is a label over a quantity of a real unit, and
adding it later is additive." with:

```java
 * An optional pack ("hộp" of 100 pc) is a label over a quantity of {@code unit} (ADR-0001); orders
 * and receipts count it whole.
```

After the `unit` field, add:

```java
    /** The pack's name ("hộp"), or null when the item is bought loose. */
    @Column(name = "pack_label", length = 32)
    private String packLabel;

    /** How many of {@code unit} one pack holds (100 for a box of 100 pc), or null without a pack. */
    @Column(name = "pack_size", precision = 19, scale = 4)
    private BigDecimal packSize;
```

After `setUnit`, add:

```java
    public String getPackLabel() {
        return packLabel;
    }

    public BigDecimal getPackSize() {
        return packSize;
    }

    public boolean hasPack() {
        return packLabel != null;
    }

    /** Sets both halves of the pack together, or clears both; {@code ItemService} has validated them. */
    public void setPack(String label, BigDecimal size) {
        this.packLabel = label;
        this.packSize = size;
    }
```

- [ ] **Step 4: Swap the uniqueness query in `ItemRepository`**

Replace:

```java
    boolean existsBySupplierIdAndIngredientIdAndUnit(UUID supplierId, UUID ingredientId, String unit);
```

with:

```java
    /** The supplier's live items for an ingredient in one unit; the service compares their packs. */
    List<Item> findBySupplierIdAndIngredientIdAndUnit(UUID supplierId, UUID ingredientId, String unit);
```

- [ ] **Step 5: Validate and store the pack in `ItemService`**

Add `import java.math.BigDecimal;`. Replace `create`:

```java
    public Item create(String sku, String name, UUID supplierId, UUID ingredientId, String unit,
            String packLabel, BigDecimal packSize) {
        requireSupplier(supplierId);
        Ingredient ingredient = requireIngredient(ingredientId);

        String canonicalUnit = compatibleUnit(unit, ingredient).symbol();
        Pack pack = Pack.of(packLabel, packSize);
        String normalizedSku = normalizeSku(sku);
        requireUniqueSku(supplierId, normalizedSku);
        requireUniqueWayOfBuying(supplierId, ingredientId, canonicalUnit, pack, null);

        Item item = new Item(supplierId, ingredientId, name, canonicalUnit);
        item.setSku(normalizedSku);
        item.setPack(pack.label(), pack.size());
        return itemRepository.save(item);
    }
```

In `update`:
- change the signature to
  `update(UUID id, String sku, String name, UUID supplierId, UUID ingredientId, String unit, String packLabel, BigDecimal packSize, boolean active, Long expectedVersion)`;
- replace the block from `String canonicalUnit = compatibleUnit(unit, ingredient).symbol();` through
  the closing brace of `if (!canonicalUnit.equals(item.getUnit())) { ... }` with:

```java
        String canonicalUnit = compatibleUnit(unit, ingredient).symbol();
        Pack pack = Pack.of(packLabel, packSize);
        requireUniqueWayOfBuying(item.getSupplierId(), item.getIngredientId(), canonicalUnit, pack, item.getId());
```

- after `item.setUnit(canonicalUnit);`, add `item.setPack(pack.label(), pack.size());`.

Replace `requireUniqueMapping` with:

```java
    // One row per way of buying a thing: a box of 100 and loose pieces are two ways, the same box twice is one.
    private void requireUniqueWayOfBuying(UUID supplierId, UUID ingredientId, String unit, Pack pack, UUID self) {
        boolean taken = itemRepository.findBySupplierIdAndIngredientIdAndUnit(supplierId, ingredientId, unit).stream()
                .anyMatch(other -> !other.getId().equals(self) && pack.sameAs(other));
        if (taken) {
            throw new ConflictException(pack.label() == null
                    ? "This supplier already sells that ingredient by the " + unit
                    : "This supplier already sells that ingredient in a %s of %s %s".formatted(
                            pack.label(), pack.size().stripTrailingZeros().toPlainString(), unit));
        }
    }

    /**
     * Class: Pack
     * Purpose: An item's optional pack as the service validated it, both halves present or both absent.
     */
    private record Pack(String label, BigDecimal size) {

        static Pack of(String label, BigDecimal size) {
            String trimmed = label == null || label.isBlank() ? null : label.trim();
            if (trimmed == null && size == null) {
                return new Pack(null, null);
            }
            if (trimmed == null || size == null || size.signum() <= 0) {
                throw new BusinessRuleException("A pack needs both a name and a size above 0");
            }
            return new Pack(trimmed, size);
        }

        boolean sameAs(Item item) {
            if (label == null) {
                return !item.hasPack();
            }
            return label.equalsIgnoreCase(item.getPackLabel()) && size.compareTo(item.getPackSize()) == 0;
        }
    }
```

A label that is all spaces is treated as absent. With a size present, it is refused as half a pack.

- [ ] **Step 6: Add the fields to the DTOs and the controller**

In `ItemDtos.ItemRequest`, after `unit` and before `Boolean active`, add:

```java
            // An optional pack: a label over a quantity of `unit` ("hộp", 100). Both or neither.
            @Size(max = 32) String packLabel,
            @Positive BigDecimal packSize,
```

In `ItemResponse`, after `String unit,`, add `String packLabel, BigDecimal packSize,`. Add the
imports `java.math.BigDecimal` and `jakarta.validation.constraints.Positive`. `ItemMapper` maps the
new fields by name; it needs no change.

In `ItemController.create`:

```java
        ItemResponse body = itemMapper.toResponse(itemService.create(request.sku(), request.name(),
                request.supplierId(), request.ingredientId(), request.unit(), request.packLabel(), request.packSize()));
```

In `update`:

```java
        return itemMapper.toResponse(itemService.update(
                id, request.sku(), request.name(), request.supplierId(), request.ingredientId(),
                request.unit(), request.packLabel(), request.packSize(), active, request.version()));
```

- [ ] **Step 7: Run the item tests**

Run: `cd backend && ./gradlew test --tests 'vn.essvn.erpcafe.inventory.ItemPackTest' --tests 'vn.essvn.erpcafe.inventory.ItemFlowIntegrationTest'`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/vn/essvn/erpcafe/inventory backend/src/test/java/vn/essvn/erpcafe/inventory/ItemPackTest.java
git commit -m "[Add] One optional pack per supplier item"
```

---

### Task 3: Purchase lines are supplier items, with the pack copied

**Files:**
- Create: `backend/src/main/java/vn/essvn/erpcafe/inventory/domain/PurchaseOrderKind.java`
- Modify: `backend/src/main/java/vn/essvn/erpcafe/inventory/domain/PurchaseOrderLine.java`
- Modify: `backend/src/main/java/vn/essvn/erpcafe/inventory/domain/PurchaseOrder.java`
- Modify: `backend/src/main/java/vn/essvn/erpcafe/inventory/application/PurchaseOrderService.java`
- Modify: `backend/src/main/java/vn/essvn/erpcafe/inventory/application/PurchaseInputs.java`
- Modify: `backend/src/main/java/vn/essvn/erpcafe/inventory/web/PurchaseOrderDtos.java`
- Modify: `backend/src/main/java/vn/essvn/erpcafe/inventory/web/PurchaseOrderController.java`
- Modify: `backend/src/test/java/vn/essvn/erpcafe/support/AbstractIntegrationTest.java`
- Modify tests:
  - `inventory/PurchaseLineUnitTest.java`, `inventory/InventoryGuardsTest.java`,
    `inventory/InventoryFlowIntegrationTest.java`, `inventory/IngredientIntegrityTest.java`,
    `inventory/ReverseDeductionIntegrationTest.java`, `inventory/ReceivingConversionTest.java`,
    `inventory/InventoryTenantIsolationTest.java`
  - `reporting/ReportFixtures.java`, `sales/OrderPaymentLifecycleTest.java`,
    `sales/SalesFlowIntegrationTest.java`, `common/SchemaHygieneIntegrationTest.java`

  All are under `backend/src/test/java/vn/essvn/erpcafe/`.

**Interfaces:**
- Consumes: `Item.getPackLabel()`, `getPackSize()` and `hasPack()` from Task 2.
- Produces:
  - `PurchaseOrderLine.getPackLabel()`, `getPackSize()`, `hasPack()`,
    `unitsPerOrderingUnit(): BigDecimal` and `growOrdered(BigDecimal)`;
  - `PurchaseOrder.getKind()` and `markRetail()`;
  - `PurchaseOrderService.requireWholePacks(BigDecimal qty, String packLabel)`, package-private and
    static;
  - JSON fields `packLabel`, `packSize` and `kind` on PO responses;
  - test helper `itemFor(token, branchId, supplierId, ingredientId[, unit])`.

- [ ] **Step 1: Write the failing tests**

In `PurchaseLineUnitTest`:
- change the class Javadoc purpose to "A purchase line is a supplier item, counted and priced in the
  item's unit.";
- delete `lineWithoutAUnit_isCountedInTheIngredientsBaseUnit`;
- replace `lineFromAnIngredientAndUnit_behavesTheSame` with:

```java
    @Test
    void ingredientLine_isRefusedWithAClearMessage() throws Exception {
        String ingredientId = ingredient("g");
        mockMvc.perform(authed(post("/api/v1/purchase-orders")).contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"supplierId\":\"%s\",\"lines\":[{\"ingredientId\":\"%s\",\"unit\":\"kg\","
                                + "\"orderedQty\":10,\"unitCost\":210000}]}").formatted(supplier(), ingredientId)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("must be a supplier item")));
    }

    @Test
    void packItemLine_copiesThePack_andRefusesHalfAPack() throws Exception {
        String ingredientId = ingredient("pc");
        String supplierId = supplier();
        String boxId = JsonPath.read(create("/api/v1/items",
                ("{\"name\":\"Straws box\",\"supplierId\":\"%s\",\"ingredientId\":\"%s\",\"unit\":\"pc\","
                        + "\"packLabel\":\"hộp\",\"packSize\":100}").formatted(supplierId, ingredientId)), "$.id");

        String po = order(supplierId, "{\"itemId\":\"%s\",\"orderedQty\":5,\"unitCost\":45000}".formatted(boxId));
        assertThat((String) JsonPath.read(po, "$.lines[0].packLabel")).isEqualTo("hộp");
        assertThat(new BigDecimal(String.valueOf((Object) JsonPath.read(po, "$.lines[0].packSize"))))
                .isEqualByComparingTo("100");
        assertThat((String) JsonPath.read(po, "$.kind")).isEqualTo("ORDER");

        mockMvc.perform(authed(post("/api/v1/purchase-orders")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"supplierId\":\"%s\",\"lines\":[{\"itemId\":\"%s\",\"orderedQty\":2.5,\"unitCost\":1}]}"
                                .formatted(supplierId, boxId)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("Packs are counted whole")));
    }
```

In `receiptInAnotherUnit_countsAgainstTheLinesOutstandingQuantity` and
`costOverride_isPerTheReceiptUnit`, replace the `order(supplier(), "{\"ingredientId\":...` call with:

```java
        String supplierId = supplier();
        String po = order(supplierId, "{\"itemId\":\"%s\",\"orderedQty\":10,\"unitCost\":210000}"
                .formatted(item(supplierId, ingredientId, "kg")));
```

`lineRejectsAUnitOfAnotherDimension_andAmbiguousSources` keeps its body. All four bodies still
answer 422 with a `code`.

- [ ] **Step 2: Run them and watch them fail**

Run: `cd backend && ./gradlew test --tests 'vn.essvn.erpcafe.inventory.PurchaseLineUnitTest'`
Expected: FAIL. `ingredientLine_isRefusedWithAClearMessage` gets 201, and
`packItemLine_copiesThePack_andRefusesHalfAPack` finds no `packLabel`.

- [ ] **Step 3: Add the order kind and the line's pack**

Create `PurchaseOrderKind.java`:

```java
package vn.essvn.erpcafe.inventory.domain;

/**
 * Class: PurchaseOrderKind
 * Purpose: Whether an order was placed with a supplier or recorded as a retail purchase already received.
 */
public enum PurchaseOrderKind {
    ORDER,
    RETAIL
}
```

In `PurchaseOrder.java`, after the `status` field, add:

```java
    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 16)
    private PurchaseOrderKind kind = PurchaseOrderKind.ORDER;
```

After `getStatus()`, add:

```java
    public PurchaseOrderKind getKind() {
        return kind;
    }

    /** A retail purchase: paid and carried in, so it is sent and received in the same save. */
    public void markRetail() {
        this.kind = PurchaseOrderKind.RETAIL;
    }
```

In `PurchaseOrderLine.java`:
- In the class Javadoc, replace "One ingredient line" with "One supplier-item line". Replace "all
  counted in the line's own {@code unit}" with "all counted in the line's ordering unit: its pack
  when it has one, otherwise its own {@code unit}".
- After the `unitCost` field, add:

```java
    /** The item's pack when the order was placed ("hộp"), or null; a copy, so later item edits never reach it. */
    @Column(name = "pack_label", length = 32)
    private String packLabel;

    @Column(name = "pack_size", precision = 19, scale = 4)
    private BigDecimal packSize;
```

- Replace the public constructor with:

```java
    public PurchaseOrderLine(UUID itemId, UUID ingredientId, String unit, BigDecimal orderedQty, Money unitCost,
            String packLabel, BigDecimal packSize) {
        this.itemId = itemId;
        this.ingredientId = ingredientId;
        this.unit = unit;
        this.orderedQty = orderedQty;
        this.unitCost = unitCost;
        this.packLabel = packLabel;
        this.packSize = packSize;
    }
```

- Before `getUnitCost()`, add:

```java
    public String getPackLabel() {
        return packLabel;
    }

    public BigDecimal getPackSize() {
        return packSize;
    }

    public boolean hasPack() {
        return packLabel != null;
    }

    /** How many of {@code unit} one ordering unit holds: the pack's size, or 1 for a line without a pack. */
    public BigDecimal unitsPerOrderingUnit() {
        return hasPack() ? packSize : BigDecimal.ONE;
    }

    /** A charged over-delivery: more arrived than was ordered and it is paid for, so the order grows. */
    public void growOrdered(BigDecimal extra) {
        this.orderedQty = this.orderedQty.add(extra);
    }
```

- [ ] **Step 4: Accept only item lines in `PurchaseOrderService`**

Replace `toLine` (and the comment above it) with:

```java
    // Every line is a supplier item: it fixes the ingredient, the unit and the pack, so the order says
    // exactly what the supplier sends. The old "ingredient + unit" line is refused with a clear message.
    private PurchaseOrderLine toLine(UUID supplierId, LineInput line) {
        if (line.itemId() == null) {
            throw new BusinessRuleException("A purchase line must be a supplier item");
        }
        if (line.ingredientId() != null) {
            throw new BusinessRuleException("A line is a supplier item; leave the ingredient out");
        }
        if (line.unit() != null) {
            throw new BusinessRuleException(
                    "An item line takes its unit from the item; leave the unit out, even if blank");
        }
        // Items are tenant-owned, so another company's item is simply not found.
        Item item = itemRepository.findById(line.itemId())
                .orElseThrow(() -> ResourceNotFoundException.of("Item", line.itemId()));
        if (!item.isActive()) {
            throw new BusinessRuleException("Item " + item.getName() + " is inactive");
        }
        requireActive(requireIngredient(item.getIngredientId()));
        if (!item.getSupplierId().equals(supplierId)) {
            throw new BusinessRuleException("Item " + item.getName() + " is sold by another supplier");
        }
        if (item.hasPack()) {
            requireWholePacks(line.orderedQty(), item.getPackLabel());
        }
        return new PurchaseOrderLine(item.getId(), item.getIngredientId(), item.getUnit(),
                line.orderedQty(), Money.of(line.unitCost()), item.getPackLabel(), item.getPackSize());
    }

    /** Packs are bought and received whole: 5 hộp, never 2.5. */
    static void requireWholePacks(BigDecimal qty, String packLabel) {
        if (qty != null && qty.stripTrailingZeros().scale() > 0) {
            throw new BusinessRuleException("Packs are counted whole: %s %s"
                    .formatted(qty.stripTrailingZeros().toPlainString(), packLabel));
        }
    }
```

In `PurchaseInputs.LineInput`, replace the Javadoc with:

```java
    /**
     * A line to order: a supplier item, plus the quantity and agreed cost in its ordering unit (its pack,
     * or its unit). {@code ingredientId} and {@code unit} exist only so a request carrying them is refused.
     */
```

- [ ] **Step 5: Expose kind and pack in the responses**

In `PurchaseOrderDtos`:
- replace `PoLineResponse` with:

```java
    public record PoLineResponse(
            UUID id,
            UUID itemId,
            UUID ingredientId,
            String unit,
            String packLabel,
            BigDecimal packSize,
            BigDecimal orderedQty,
            BigDecimal receivedQty,
            MoneyDto unitCost) {
    }
```

- in `PurchaseOrderResponse`, add `String kind,` after `UUID supplierId,`.

In `PurchaseOrderController`, update the mappers:

```java
    private static PurchaseOrderResponse toResponse(PurchaseOrder o) {
        List<PoLineResponse> lines = o.getLines().stream()
                .map(PurchaseOrderController::toLine)
                .toList();
        return new PurchaseOrderResponse(o.getId(), o.getBranchId(), o.getSupplierId(), o.getKind().name(),
                o.getStatus().name(), o.getNote(), lines);
    }

    private static PoLineResponse toLine(PurchaseOrderLine l) {
        return new PoLineResponse(l.getId(), l.getItemId(), l.getIngredientId(), l.getUnit(), l.getPackLabel(),
                l.getPackSize(), l.getOrderedQty(), l.getReceivedQty(), MoneyDto.from(l.getUnitCost()));
    }
```

- [ ] **Step 6: Add the shared test helper**

In `AbstractIntegrationTest`, add these imports:
- `import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;`
- `import java.util.List;`
- `import java.util.Map;`
- `import org.springframework.http.HttpHeaders;`

Then add:

```java
    /**
     * The supplier's loose item for an ingredient in {@code unit} (the ingredient's base unit when null),
     * created on first use: purchase lines are supplier items, so a test that buys stock needs one.
     */
    protected String itemFor(String token, String branchId, String supplierId, String ingredientId, String unit)
            throws Exception {
        String buyIn = unit;
        if (buyIn == null) {
            List<Map<String, Object>> ingredients = JsonPath.read(mockMvc.perform(get("/api/v1/ingredients")
                            .header(HttpHeaders.AUTHORIZATION, authHeader(token)).header("X-Branch-Id", branchId))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$");
            buyIn = ingredients.stream().filter(i -> ingredientId.equals(i.get("id")))
                    .map(i -> (String) i.get("baseUnit")).findFirst().orElseThrow();
        }
        List<Map<String, Object>> items = JsonPath.read(mockMvc.perform(get("/api/v1/items")
                        .param("supplierId", supplierId)
                        .header(HttpHeaders.AUTHORIZATION, authHeader(token)).header("X-Branch-Id", branchId))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$");
        for (Map<String, Object> item : items) {
            if (ingredientId.equals(item.get("ingredientId")) && buyIn.equals(item.get("unit"))
                    && item.get("packLabel") == null) {
                return (String) item.get("id");
            }
        }
        String created = mockMvc.perform(post("/api/v1/items")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(token)).header("X-Branch-Id", branchId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Test item\",\"supplierId\":\"%s\",\"ingredientId\":\"%s\",\"unit\":\"%s\"}"
                                .formatted(supplierId, ingredientId, buyIn)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(created, "$.id");
    }

    protected String itemFor(String token, String branchId, String supplierId, String ingredientId) throws Exception {
        return itemFor(token, branchId, supplierId, ingredientId, null);
    }
```

- [ ] **Step 7: Move the other ten tests to item lines**

Make each change below exactly. In every case, the JSON line `{"ingredientId":"%s",…}` becomes
`{"itemId":"%s",…}`, and the formatted argument becomes the item id.

`reporting/ReportFixtures.java` (`sellableProduct`):

```java
        String itemId = itemFor(token, branchIds.get(0), supplierId, ingredientId);
        String poJson = create("/api/v1/purchase-orders",
                "{\"supplierId\":\"%s\",\"lines\":[{\"itemId\":\"%s\",\"orderedQty\":1000,\"unitCost\":100}]}"
                        .formatted(supplierId, itemId));
```

`sales/OrderPaymentLifecycleTest.java` and `sales/SalesFlowIntegrationTest.java` (`setup`):

```java
        String itemId = itemFor(token, branchId, supplierId, ingredientId);
        String poJson = create("/api/v1/purchase-orders",
                "{\"supplierId\":\"%s\",\"lines\":[{\"itemId\":\"%s\",\"orderedQty\":1000,\"unitCost\":0.20}]}"
                        .formatted(supplierId, itemId));
```

`common/SchemaHygieneIntegrationTest.java` (`purchaseOrderList_defaultsToNewestFirst`):

```java
        String itemId = itemFor(token, branchId, supplierId, ingredientId);
        String lines = "\"lines\":[{\"itemId\":\"%s\",\"orderedQty\":10,\"unitCost\":1}]".formatted(itemId);
```

`inventory/InventoryFlowIntegrationTest.java`:
- in `receivePurchase`:

```java
        String itemId = itemFor(token, branchId, supplierId, ingredientId);
        String poJson = create("/api/v1/purchase-orders",
                "{\"supplierId\":\"%s\",\"lines\":[{\"itemId\":\"%s\",\"orderedQty\":%s,\"unitCost\":%s}]}"
                        .formatted(supplierId, itemId, qty.toPlainString(), unitCost));
```

- in `purchaseReceipts_blendMovingAverageCost`, for the second order:

```java
        String poJson = create("/api/v1/purchase-orders",
                "{\"supplierId\":\"%s\",\"lines\":[{\"itemId\":\"%s\",\"orderedQty\":1000,\"unitCost\":0.24}]}"
                        .formatted(supplierId, itemFor(token, branchId, supplierId, ingredientId)));
```

`inventory/IngredientIntegrityTest.java` (`sentPurchaseOrder`):

```java
    private String[] sentPurchaseOrder(String ingredientId, String qty, String cost) throws Exception {
        String supplierId = supplier();
        String poJson = create("/api/v1/purchase-orders",
                "{\"supplierId\":\"%s\",\"lines\":[{\"itemId\":\"%s\",\"orderedQty\":%s,\"unitCost\":%s}]}"
                        .formatted(supplierId, itemFor(token, branchId, supplierId, ingredientId), qty, cost));
        String poId = JsonPath.read(poJson, "$.id");
        mockMvc.perform(authed(post("/api/v1/purchase-orders/" + poId + "/send"))).andExpect(status().isOk());
        return new String[] {poId, JsonPath.read(poJson, "$.lines[0].id")};
    }
```

`inventory/ReverseDeductionIntegrationTest.java` (`receive`):

```java
        String poJson = create("/api/v1/purchase-orders",
                "{\"supplierId\":\"%s\",\"lines\":[{\"itemId\":\"%s\",\"orderedQty\":1000,\"unitCost\":%s}]}"
                        .formatted(supplierId, itemFor(token, branchId, supplierId, ingredientId), cost));
```

`inventory/ReceivingConversionTest.java`: change both tests the same way. The ingredients are in
grams, so the loose item is in `g`, and the receipt in `kg` or `l` still exercises ADR-0006's
any-unit receipt:

```java
        String poJson = create("/api/v1/purchase-orders",
                "{\"supplierId\":\"%s\",\"lines\":[{\"itemId\":\"%s\",\"orderedQty\":2000,\"unitCost\":0.20}]}"
                        .formatted(supplierId, itemFor(token, branchId, supplierId, ingredientId)));
```

The second test uses `"orderedQty":1000,"unitCost":0.10`.

`inventory/InventoryTenantIsolationTest.java`:
- in `purchaseOrderEndpoints_areBranchIsolated`:

```java
        String poId = JsonPath.read(createJson(admin, branchA, "/api/v1/purchase-orders",
                "{\"supplierId\":\"%s\",\"lines\":[{\"itemId\":\"%s\",\"orderedQty\":10,\"unitCost\":1}]}"
                        .formatted(supplierId, itemFor(admin, branchA, supplierId, ingredientId))), "$.id");
```

- in `stockAndPurchaseOrders_answer404ForAnotherCompanysIds`, change the company-B POST body to an
  item line. Company A's supplier is checked first, so the answer stays 404:

```java
                        .content("{\"supplierId\":\"%s\",\"lines\":[{\"itemId\":\"%s\",\"orderedQty\":1,\"unitCost\":1}]}"
                                .formatted(supplierId, itemFor(admin, branchA, supplierId, ingredientId))))
```

`inventory/InventoryGuardsTest.java`:
- replace the helper `ingredientLine(String ingredientId)` with:

```java
    private String itemLine(String itemId) {
        return "{\"itemId\":\"%s\",\"orderedQty\":10,\"unitCost\":1}".formatted(itemId);
    }
```

- `newOrder_forAnInactiveSupplier_isRefused` becomes:

```java
    @Test
    void newOrder_forAnInactiveSupplier_isRefused() throws Exception {
        String ingredientId = ingredient();
        String supplierId = supplier();
        String itemId = item(supplierId, ingredientId);
        deactivateSupplier(supplierId);

        expectRefused(orderBody(supplierId, itemLine(itemId)), "inactive");
    }
```

- delete `newOrder_lineOfAnInactiveIngredient_isRefused`.
  `newOrder_itemWhoseIngredientIsInactive_isRefusedAtCreate` already covers it.
- in both `receipt_onAnOrderWhoseSupplierWentInactive_isRefusedAndStockUnchanged` and
  `receipt_ofAnIngredientThatWentInactive_isRefusedAndStockUnchanged`, replace
  `orderBody(supplierId, ingredientLine(ingredientId))` with
  `orderBody(supplierId, itemLine(item(supplierId, ingredientId)))`.

- [ ] **Step 8: Run the touched tests**

Run:

```bash
cd backend && ./gradlew test --tests 'vn.essvn.erpcafe.inventory.*' --tests 'vn.essvn.erpcafe.sales.*' \
  --tests 'vn.essvn.erpcafe.reporting.*' --tests 'vn.essvn.erpcafe.common.SchemaHygieneIntegrationTest'
```

Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add backend/src
git commit -m "[Change] Purchase lines are supplier items, ordered in whole packs"
```

---

### Task 4: `ReceiptSplit`, the receiving rules as a pure value

**Files:**
- Create: `backend/src/main/java/vn/essvn/erpcafe/inventory/domain/OverDeliveryKind.java`
- Create: `backend/src/main/java/vn/essvn/erpcafe/inventory/domain/ReceiptSplit.java`
- Test: `backend/src/test/java/vn/essvn/erpcafe/inventory/domain/ReceiptSplitTest.java`

**Interfaces:**
- Produces:
  - `enum OverDeliveryKind { CHARGED, FREE }`;
  - `ReceiptSplit.of(BigDecimal delivered, BigDecimal outstanding, BigDecimal unitsPerOrderingUnit, OverDeliveryKind kind, BigDecimal damaged): ReceiptSplit`,
    with accessors `countedQty()`, `growOrderedBy()`, `overDeliveredQty()`, `chargedIntoStock()`,
    `freeIntoStock()` and `lossQty()`.
- Quantity units:
  - `delivered`, `outstanding`, `countedQty`, `growOrderedBy` and `overDeliveredQty` are in the
    ordering unit;
  - `damaged`, `chargedIntoStock`, `freeIntoStock` and `lossQty` are in the line's unit.

- [ ] **Step 1: Write the failing test**

```java
package vn.essvn.erpcafe.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import vn.essvn.erpcafe.common.exception.BusinessRuleException;

/**
 * Class: ReceiptSplitTest
 * Purpose: Pins how one delivered line splits into what the order counts, stock paid and free, and loss.
 */
class ReceiptSplitTest {

    private static final BigDecimal BOX = new BigDecimal("100");

    private static BigDecimal n(String v) {
        return new BigDecimal(v);
    }

    private static void expect(ReceiptSplit s, String counted, String grow, String over, String charged,
            String free, String loss) {
        assertThat(s.countedQty()).isEqualByComparingTo(counted);
        assertThat(s.growOrderedBy()).isEqualByComparingTo(grow);
        assertThat(s.overDeliveredQty()).isEqualByComparingTo(over);
        assertThat(s.chargedIntoStock()).isEqualByComparingTo(charged);
        assertThat(s.freeIntoStock()).isEqualByComparingTo(free);
        assertThat(s.lossQty()).isEqualByComparingTo(loss);
    }

    @Test
    void fullDelivery_entersEveryUnitAtCost() {
        expect(ReceiptSplit.of(n("5"), n("5"), BOX, null, n("0")), "5", "0", "0", "500", "0", "0");
    }

    @Test
    void damagedUnits_countTowardTheOrder_butNotStock() {
        expect(ReceiptSplit.of(n("5"), n("5"), BOX, null, n("40")), "5", "0", "0", "460", "0", "40");
    }

    @Test
    void shortDelivery_countsWhatArrived() {
        expect(ReceiptSplit.of(n("4"), n("5"), BOX, null, n("0")), "4", "0", "0", "400", "0", "0");
    }

    @Test
    void chargedExtra_growsTheOrder() {
        expect(ReceiptSplit.of(n("6"), n("5"), BOX, OverDeliveryKind.CHARGED, n("0")), "6", "1", "1", "600", "0", "0");
    }

    @Test
    void freeExtra_entersFree_andTheOrderStays() {
        expect(ReceiptSplit.of(n("6"), n("5"), BOX, OverDeliveryKind.FREE, n("0")), "5", "0", "1", "500", "100", "0");
    }

    @Test
    void damage_countsAgainstThePaidUnitsFirst() {
        expect(ReceiptSplit.of(n("6"), n("5"), BOX, OverDeliveryKind.FREE, n("150")), "5", "0", "1", "350", "100", "150");
        expect(ReceiptSplit.of(n("6"), n("5"), BOX, OverDeliveryKind.FREE, n("550")), "5", "0", "1", "0", "50", "500");
    }

    @Test
    void wholeBoxRuined_entersNothing_butCountsTheBox() {
        expect(ReceiptSplit.of(n("1"), n("1"), BOX, null, n("100")), "1", "0", "0", "0", "0", "100");
    }

    @Test
    void lineWithoutAPack_countsInItsOwnUnit() {
        expect(ReceiptSplit.of(n("2.5"), n("10"), BigDecimal.ONE, null, n("0.5")), "2.5", "0", "0", "2.0", "0", "0.5");
    }

    @Test
    void extraWithoutAChoice_orAChoiceWithoutExtra_isRefused() {
        assertThatThrownBy(() -> ReceiptSplit.of(n("6"), n("5"), BOX, null, n("0")))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("choose charged or free gift");
        assertThatThrownBy(() -> ReceiptSplit.of(n("5"), n("5"), BOX, OverDeliveryKind.FREE, n("0")))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("Nothing arrived beyond");
    }

    @Test
    void damageBeyondTheDelivery_orNegative_isRefused() {
        assertThatThrownBy(() -> ReceiptSplit.of(n("5"), n("5"), BOX, null, n("501")))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("cannot exceed what was delivered");
        assertThatThrownBy(() -> ReceiptSplit.of(n("5"), n("5"), BOX, null, n("-1")))
                .isInstanceOf(BusinessRuleException.class);
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `cd backend && ./gradlew test --tests 'vn.essvn.erpcafe.inventory.domain.ReceiptSplitTest'`
Expected: FAIL at compilation, because `ReceiptSplit` and `OverDeliveryKind` do not exist.

- [ ] **Step 3: Implement**

`OverDeliveryKind.java`:

```java
package vn.essvn.erpcafe.inventory.domain;

/**
 * Class: OverDeliveryKind
 * Purpose: How units delivered beyond the order are taken: paid for, or accepted as a free gift.
 */
public enum OverDeliveryKind {
    CHARGED,
    FREE
}
```

`ReceiptSplit.java`:

```java
package vn.essvn.erpcafe.inventory.domain;

import java.math.BigDecimal;

import vn.essvn.erpcafe.common.exception.BusinessRuleException;

/**
 * Class: ReceiptSplit
 * Purpose: Splits one delivered purchase line into what the order counts, what enters stock paid and free, and what was lost.
 */
public record ReceiptSplit(BigDecimal countedQty, BigDecimal growOrderedBy, BigDecimal overDeliveredQty,
        BigDecimal chargedIntoStock, BigDecimal freeIntoStock, BigDecimal lossQty) {

    /**
     * Splits a delivery. {@code delivered} and {@code outstanding} are in the line's ordering unit (packs,
     * or its unit); {@code damaged} is in the line's unit; {@code kind} is required exactly when more
     * arrived than was outstanding.
     */
    public static ReceiptSplit of(BigDecimal delivered, BigDecimal outstanding, BigDecimal unitsPerOrderingUnit,
            OverDeliveryKind kind, BigDecimal damaged) {
        BigDecimal over = delivered.subtract(outstanding).max(BigDecimal.ZERO);
        if (over.signum() > 0 && kind == null) {
            throw new BusinessRuleException("%s more than outstanding arrived: choose charged or free gift"
                    .formatted(over.stripTrailingZeros().toPlainString()));
        }
        if (over.signum() == 0 && kind != null) {
            throw new BusinessRuleException(
                    "Nothing arrived beyond what was ordered, so there is no extra to charge or take free");
        }
        BigDecimal freeOrdering = kind == OverDeliveryKind.FREE ? over : BigDecimal.ZERO;
        BigDecimal chargedUnits = delivered.subtract(freeOrdering).multiply(unitsPerOrderingUnit);
        BigDecimal freeUnits = freeOrdering.multiply(unitsPerOrderingUnit);
        if (damaged.signum() < 0 || damaged.compareTo(chargedUnits.add(freeUnits)) > 0) {
            throw new BusinessRuleException("Damaged cannot exceed what was delivered");
        }
        // Damage is counted against what was paid for first, so the loss is never understated.
        BigDecimal damagedCharged = damaged.min(chargedUnits);
        BigDecimal damagedFree = damaged.subtract(damagedCharged);
        boolean charged = kind == OverDeliveryKind.CHARGED;
        return new ReceiptSplit(
                charged ? delivered : delivered.subtract(freeOrdering),
                charged ? over : BigDecimal.ZERO,
                over,
                chargedUnits.subtract(damagedCharged),
                freeUnits.subtract(damagedFree),
                damagedCharged);
    }
}
```

- [ ] **Step 4: Run it and watch it pass**

Run: `cd backend && ./gradlew test --tests 'vn.essvn.erpcafe.inventory.domain.ReceiptSplitTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/vn/essvn/erpcafe/inventory/domain/OverDeliveryKind.java \
        backend/src/main/java/vn/essvn/erpcafe/inventory/domain/ReceiptSplit.java \
        backend/src/test/java/vn/essvn/erpcafe/inventory/domain/ReceiptSplitTest.java
git commit -m "[Add] Receipt split rules for damage and over-delivery"
```

---

### Task 5: Goods receipts on receiving

**Files:**
- Create: `inventory/domain/GoodsReceiptKind.java`, `inventory/domain/GoodsReceipt.java`,
  `inventory/domain/GoodsReceiptLine.java`
- Create: `inventory/persistence/GoodsReceiptRepository.java`,
  `inventory/application/GoodsReceivingService.java`
- Modify: `inventory/application/PurchaseInputs.java`, `PurchaseOrderService.java`
- Modify: `inventory/web/PurchaseOrderDtos.java`, `PurchaseOrderController.java`
- Test: `backend/src/test/java/vn/essvn/erpcafe/inventory/PackReceivingTest.java`

The source paths are under `backend/src/main/java/vn/essvn/erpcafe/`.

**Interfaces:**
- Consumes:
  - `ReceiptSplit.of(...)` and `OverDeliveryKind` from Task 4;
  - `PurchaseOrderLine.hasPack()`, `getPackLabel()`, `unitsPerOrderingUnit()` and
    `growOrdered(...)`, and `PurchaseOrderService.requireWholePacks(...)`, from Task 3;
  - `StockLedgerService.receive(UUID, UUID, BigDecimal, String, Money, String, UUID)` and
    `toBaseRate(UUID, UUID, BigDecimal, String)`.
- Produces:
  - `ReceiptInput(UUID lineId, BigDecimal receivedQty, String unit, BigDecimal unitCostOverride, BigDecimal damagedQty, OverDeliveryKind overDeliveryKind, String note)`;
  - `GoodsReceivingService.receive(PurchaseOrder, List<ReceiptInput>, String note): GoodsReceipt`;
  - `GoodsReceivingService.REF_TYPE = "GOODS_RECEIPT"`;
  - `GoodsReceiptRepository`;
  - `PurchaseOrderService.receive(UUID, List<ReceiptInput>, String note)`,
    `receiptsOf(List<UUID>): Map<UUID, List<GoodsReceipt>>` and `getByReceipt(UUID)`;
  - `GoodsReceiptLine.getLossQty()`, `getCompensatedQty()`, `getCostPerUnit()`,
    `compensate(BigDecimal)`, `lossAmount(): Money` and the factory `replacement(...)`, used in
    Task 6;
  - endpoint `GET /api/v1/purchase-orders/by-receipt/{receiptId}`;
  - JSON: `receipts[]` on every PO response.

- [ ] **Step 1: Write the failing test**

Create `PackReceivingTest.java`:

```java
package vn.essvn.erpcafe.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;

import vn.essvn.erpcafe.support.AbstractIntegrationTest;

/**
 * Class: PackReceivingTest
 * Purpose: Receiving in packs puts usable units into stock at the price paid and records damage, extras and the loss.
 */
class PackReceivingTest extends AbstractIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    @Qualifier("ownerDataSource")
    private DataSource ownerDataSource;

    private String token;
    private String branchId;
    private String straws;
    private String shop;
    private String box;

    @BeforeEach
    void setUp() throws Exception {
        token = adminToken();
        branchId = ((List<String>) JsonPath.read(
                mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, authHeader(token)))
                        .andReturn().getResponse().getContentAsString(), "$.branchIds")).get(0);
        int n = SEQ.incrementAndGet();
        straws = id(send(post("/api/v1/ingredients"), "{\"name\":\"RecvStraws%d\",\"baseUnit\":\"pc\"}".formatted(n)));
        shop = id(send(post("/api/v1/suppliers"), "{\"name\":\"RecvShop%d\"}".formatted(n)));
        box = id(send(post("/api/v1/items"), ("{\"name\":\"Ống hút hộp 100\",\"supplierId\":\"%s\",\"ingredientId\":\"%s\","
                + "\"unit\":\"pc\",\"packLabel\":\"hộp\",\"packSize\":100}").formatted(shop, straws)));
    }

    private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder b) {
        return b.header(HttpHeaders.AUTHORIZATION, authHeader(token)).header("X-Branch-Id", branchId);
    }

    private ResultActions send(MockHttpServletRequestBuilder b, String body) throws Exception {
        return mockMvc.perform(authed(b).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static String body(ResultActions r) throws Exception {
        return r.andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString();
    }

    private static String id(ResultActions r) throws Exception {
        return JsonPath.read(body(r), "$.id");
    }

    /** A sent order for {@code qty} of the item at {@code cost} per ordering unit; returns the PO json. */
    private String sentOrder(String itemId, String qty, String cost) throws Exception {
        String po = body(send(post("/api/v1/purchase-orders"),
                "{\"supplierId\":\"%s\",\"lines\":[{\"itemId\":\"%s\",\"orderedQty\":%s,\"unitCost\":%s}]}"
                        .formatted(shop, itemId, qty, cost)));
        mockMvc.perform(authed(post("/api/v1/purchase-orders/" + JsonPath.read(po, "$.id") + "/send")))
                .andExpect(status().isOk());
        return po;
    }

    private ResultActions receive(String po, String receiptFields) throws Exception {
        return send(post("/api/v1/purchase-orders/" + JsonPath.read(po, "$.id") + "/receive"),
                "{\"receipts\":[{\"lineId\":\"%s\",%s}]}".formatted((String) JsonPath.read(po, "$.lines[0].id"), receiptFields));
    }

    private BigDecimal stock(String field) throws Exception {
        String json = body(mockMvc.perform(authed(get("/api/v1/stock"))));
        List<Object> vals = JsonPath.read(json, "$[?(@.ingredientId=='%s')].%s".formatted(straws, field));
        return vals.isEmpty() ? null : new BigDecimal(String.valueOf(vals.get(0)));
    }

    private static BigDecimal num(String json, String path) {
        return new BigDecimal(String.valueOf((Object) JsonPath.read(json, path)));
    }

    /** 200 pc already on hand at 400 each, bought loose from the same shop. */
    private void twoHundredOnHandAt400() throws Exception {
        receive(sentOrder(itemFor(token, branchId, shop, straws, "pc"), "200", "400"), "\"receivedQty\":200")
                .andExpect(status().isOk());
    }

    @Test
    void fiveBoxes_enterFiveHundredAtTheBoxPriceDividedByItsSize() throws Exception {
        String after = body(receive(sentOrder(box, "5", "45000"), "\"receivedQty\":5"));

        assertThat(stock("quantityOnHand")).isEqualByComparingTo("500");
        assertThat(stock("avgUnitCost.amount")).isEqualByComparingTo("450");
        assertThat((String) JsonPath.read(after, "$.status")).isEqualTo("RECEIVED");
        assertThat((String) JsonPath.read(after, "$.receipts[0].kind")).isEqualTo("DELIVERY");
        assertThat(num(after, "$.receipts[0].lines[0].deliveredQty")).isEqualByComparingTo("5");
        assertThat((String) JsonPath.read(after, "$.receipts[0].receivedBy")).isNotBlank();
    }

    @Test
    void damagedUnits_neverEnterStock_andTheLossIsAtThePricePaid() throws Exception {
        twoHundredOnHandAt400();

        String after = body(receive(sentOrder(box, "5", "45000"),
                "\"receivedQty\":5,\"damagedQty\":40,\"note\":\"one box torn\""));

        assertThat(stock("quantityOnHand")).isEqualByComparingTo("660");
        assertThat(stock("avgUnitCost.amount")).isEqualByComparingTo("434.8485");
        assertThat((String) JsonPath.read(after, "$.status")).isEqualTo("RECEIVED");
        assertThat(num(after, "$.receipts[0].lines[0].lossQty")).isEqualByComparingTo("40");
        assertThat(num(after, "$.receipts[0].lines[0].lossAmount.amount")).isEqualByComparingTo("18000");
        assertThat((String) JsonPath.read(after, "$.receipts[0].lines[0].note")).isEqualTo("one box torn");
    }

    @Test
    void shortDelivery_leavesTheLineOutstanding() throws Exception {
        String after = body(receive(sentOrder(box, "5", "45000"), "\"receivedQty\":4"));

        assertThat((String) JsonPath.read(after, "$.status")).isEqualTo("PARTIALLY_RECEIVED");
        assertThat(num(after, "$.lines[0].receivedQty")).isEqualByComparingTo("4");
        assertThat(stock("quantityOnHand")).isEqualByComparingTo("400");
    }

    @Test
    void chargedOverDelivery_growsTheOrder() throws Exception {
        String after = body(receive(sentOrder(box, "5", "45000"), "\"receivedQty\":6,\"overDeliveryKind\":\"CHARGED\""));

        assertThat(num(after, "$.lines[0].orderedQty")).isEqualByComparingTo("6");
        assertThat((String) JsonPath.read(after, "$.status")).isEqualTo("RECEIVED");
        assertThat(stock("quantityOnHand")).isEqualByComparingTo("600");
        assertThat(stock("avgUnitCost.amount")).isEqualByComparingTo("450");
    }

    @Test
    void freeOverDelivery_entersAtZero_andTheOrderStays() throws Exception {
        String after = body(receive(sentOrder(box, "5", "45000"), "\"receivedQty\":6,\"overDeliveryKind\":\"FREE\""));

        assertThat(num(after, "$.lines[0].orderedQty")).isEqualByComparingTo("5");
        assertThat(stock("quantityOnHand")).isEqualByComparingTo("600");
        assertThat(stock("avgUnitCost.amount")).isEqualByComparingTo("375");
        assertThat((String) JsonPath.read(after, "$.receipts[0].lines[0].overDeliveryKind")).isEqualTo("FREE");
    }

    @Test
    void freeAndDamaged_damageCountsAgainstThePaidUnitsFirst() throws Exception {
        String after = body(receive(sentOrder(box, "5", "45000"),
                "\"receivedQty\":6,\"overDeliveryKind\":\"FREE\",\"damagedQty\":150"));

        assertThat(stock("quantityOnHand")).isEqualByComparingTo("450");
        assertThat(num(after, "$.receipts[0].lines[0].lossAmount.amount")).isEqualByComparingTo("67500");
    }

    @Test
    void wholeBoxDamaged_entersNothing_butCountsTheBox() throws Exception {
        String after = body(receive(sentOrder(box, "1", "45000"), "\"receivedQty\":1,\"damagedQty\":100"));

        assertThat(stock("quantityOnHand")).isNull();
        assertThat((String) JsonPath.read(after, "$.status")).isEqualTo("RECEIVED");
        assertThat(num(after, "$.receipts[0].lines[0].lossAmount.amount")).isEqualByComparingTo("45000");
    }

    @Test
    void actualPricePerPack_setsTheStockCost() throws Exception {
        receive(sentOrder(box, "5", "45000"), "\"receivedQty\":5,\"unitCostOverride\":50000").andExpect(status().isOk());

        assertThat(stock("avgUnitCost.amount")).isEqualByComparingTo("500");
    }

    @Test
    void refusals_leaveStockAndTheOrderUntouched() throws Exception {
        String po = sentOrder(box, "5", "45000");
        for (String fields : List.of("\"receivedQty\":6", "\"receivedQty\":5,\"overDeliveryKind\":\"FREE\"",
                "\"receivedQty\":5,\"damagedQty\":501", "\"receivedQty\":2.5", "\"receivedQty\":5,\"unit\":\"pc\"")) {
            receive(po, fields).andExpect(status().isUnprocessableEntity());
        }
        String now = body(mockMvc.perform(authed(get("/api/v1/purchase-orders/" + JsonPath.read(po, "$.id")))));
        assertThat((String) JsonPath.read(now, "$.status")).isEqualTo("SENT");
        assertThat((List<?>) JsonPath.read(now, "$.receipts")).isEmpty();
        assertThat(stock("quantityOnHand")).isNull();
    }

    @Test
    void secondReceipt_onAFullyReceivedOrder_isRefused() throws Exception {
        String po = sentOrder(box, "5", "45000");
        receive(po, "\"receivedQty\":5").andExpect(status().isOk());

        receive(po, "\"receivedQty\":5").andExpect(status().isUnprocessableEntity());
        assertThat(stock("quantityOnHand")).isEqualByComparingTo("500");
    }

    @Test
    void packEditedAfterOrdering_receivesWithTheOrdersPack() throws Exception {
        String po = sentOrder(box, "5", "45000");
        send(put("/api/v1/items/" + box), ("{\"name\":\"Ống hút hộp 50\",\"supplierId\":\"%s\",\"ingredientId\":\"%s\","
                + "\"unit\":\"pc\",\"packLabel\":\"hộp\",\"packSize\":50,\"version\":0}").formatted(shop, straws))
                .andExpect(status().isOk());

        receive(po, "\"receivedQty\":5").andExpect(status().isOk());

        assertThat(stock("quantityOnHand")).isEqualByComparingTo("500");
    }

    @Test
    void legacyIngredientLine_stillReceives() throws Exception {
        String po = sentOrder(itemFor(token, branchId, shop, straws, "pc"), "10", "400");
        new JdbcTemplate(ownerDataSource).update("UPDATE purchase_order_line SET item_id = NULL WHERE id = ?::uuid",
                (String) JsonPath.read(po, "$.lines[0].id"));

        receive(po, "\"receivedQty\":10").andExpect(status().isOk());

        assertThat(stock("quantityOnHand")).isEqualByComparingTo("10");
    }

    @Test
    void movement_pointsAtTheReceipt_andTheReceiptLeadsBackToTheOrder() throws Exception {
        String po = sentOrder(box, "5", "45000");
        String after = body(receive(po, "\"receivedQty\":5"));
        String receiptId = JsonPath.read(after, "$.receipts[0].id");

        String movements = body(mockMvc.perform(authed(get("/api/v1/stock/movements").param("ingredientId", straws))));
        assertThat((String) JsonPath.read(movements, "$.content[0].refType")).isEqualTo("GOODS_RECEIPT");
        assertThat((String) JsonPath.read(movements, "$.content[0].refId")).isEqualTo(receiptId);

        mockMvc.perform(authed(get("/api/v1/purchase-orders/by-receipt/" + receiptId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value((String) JsonPath.read(po, "$.id")));
        mockMvc.perform(authed(get("/api/v1/purchase-orders/by-receipt/" + java.util.UUID.randomUUID())))
                .andExpect(status().isNotFound());
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `cd backend && ./gradlew test --tests 'vn.essvn.erpcafe.inventory.PackReceivingTest'`
Expected: FAIL. `receipts` is missing from the responses, and over-delivery fields are unknown,
among other failures.

- [ ] **Step 3: Add the receipt entities and repository**

`GoodsReceiptKind.java`:

```java
package vn.essvn.erpcafe.inventory.domain;

/**
 * Class: GoodsReceiptKind
 * Purpose: Whether a goods receipt records a delivery or a supplier's replacement for lost units.
 */
public enum GoodsReceiptKind {
    DELIVERY,
    COMPENSATION
}
```

`GoodsReceipt.java`:

```java
package vn.essvn.erpcafe.inventory.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import vn.essvn.erpcafe.common.domain.BranchScopedEntity;

/**
 * Class: GoodsReceipt
 * Purpose: One delivery or replacement received against a purchase order, with who received it and when.
 */
@Entity
@Table(name = "goods_receipt")
public class GoodsReceipt extends BranchScopedEntity {

    @Column(name = "purchase_order_id", nullable = false)
    private UUID purchaseOrderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 16)
    private GoodsReceiptKind kind;

    @Column(name = "note")
    private String note;

    // not null: the key goes into the child's own INSERT, which row-level security admits only under a visible parent
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "goods_receipt_id", nullable = false)
    private List<GoodsReceiptLine> lines = new ArrayList<>();

    protected GoodsReceipt() {
    }

    public GoodsReceipt(UUID purchaseOrderId, UUID branchId, GoodsReceiptKind kind, String note) {
        this.purchaseOrderId = purchaseOrderId;
        setBranchId(branchId);
        this.kind = kind;
        this.note = note;
    }

    public UUID getPurchaseOrderId() {
        return purchaseOrderId;
    }

    public GoodsReceiptKind getKind() {
        return kind;
    }

    public String getNote() {
        return note;
    }

    public List<GoodsReceiptLine> getLines() {
        return lines;
    }

    public void addLine(GoodsReceiptLine line) {
        lines.add(line);
    }
}
```

`GoodsReceiptLine.java`:

```java
package vn.essvn.erpcafe.inventory.domain;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import vn.essvn.erpcafe.common.domain.BaseEntity;
import vn.essvn.erpcafe.common.domain.Money;
import vn.essvn.erpcafe.common.exception.BusinessRuleException;

/**
 * Class: GoodsReceiptLine
 * Purpose: What one purchase line received: delivered, damaged and lost, any extra and how it was taken, and the price paid.
 */
@Entity
@Table(name = "goods_receipt_line")
public class GoodsReceiptLine extends BaseEntity {

    @Column(name = "purchase_order_line_id", nullable = false)
    private UUID purchaseOrderLineId;

    /** In the line's ordering unit (packs, or its unit). Zero on a replacement. */
    @Column(name = "delivered_qty", nullable = false, precision = 19, scale = 4)
    private BigDecimal deliveredQty = BigDecimal.ZERO;

    /** In the line's unit. */
    @Column(name = "damaged_qty", nullable = false, precision = 19, scale = 4)
    private BigDecimal damagedQty = BigDecimal.ZERO;

    /** The damaged units that were paid for, in the line's unit: what the loss and a replacement count. */
    @Column(name = "loss_qty", nullable = false, precision = 19, scale = 4)
    private BigDecimal lossQty = BigDecimal.ZERO;

    @Column(name = "over_delivered_qty", nullable = false, precision = 19, scale = 4)
    private BigDecimal overDeliveredQty = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(name = "over_delivery_kind", length = 16)
    private OverDeliveryKind overDeliveryKind;

    /** The price paid per ordering unit. */
    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "amount", column = @Column(name = "unit_cost", precision = 19, scale = 4)),
            @AttributeOverride(name = "currency", column = @Column(name = "currency", length = 3))
    })
    private Money unitCost;

    /** The price paid per unit of the line, in {@code unitCost}'s currency: what stock and the loss use. */
    @Column(name = "cost_per_unit", nullable = false, precision = 19, scale = 4)
    private BigDecimal costPerUnit;

    @Column(name = "compensated_qty", nullable = false, precision = 19, scale = 4)
    private BigDecimal compensatedQty = BigDecimal.ZERO;

    /** On a replacement, the units it brought in, in the line's unit. */
    @Column(name = "replacement_qty", nullable = false, precision = 19, scale = 4)
    private BigDecimal replacementQty = BigDecimal.ZERO;

    @Column(name = "compensates_line_id")
    private UUID compensatesLineId;

    @Column(name = "note")
    private String note;

    protected GoodsReceiptLine() {
    }

    /** A delivered line, as {@code split} divided it. */
    public static GoodsReceiptLine delivered(UUID purchaseOrderLineId, BigDecimal delivered, BigDecimal damaged,
            ReceiptSplit split, OverDeliveryKind kind, Money unitCost, BigDecimal costPerUnit, String note) {
        GoodsReceiptLine line = new GoodsReceiptLine();
        line.purchaseOrderLineId = purchaseOrderLineId;
        line.deliveredQty = delivered;
        line.damagedQty = damaged;
        line.lossQty = split.lossQty();
        line.overDeliveredQty = split.overDeliveredQty();
        line.overDeliveryKind = kind;
        line.unitCost = unitCost;
        line.costPerUnit = costPerUnit;
        line.note = note;
        return line;
    }

    /** A replacement for {@code qty} units {@code lost} lost, valued as those units were. */
    public static GoodsReceiptLine replacement(GoodsReceiptLine lost, BigDecimal qty, String note) {
        GoodsReceiptLine line = new GoodsReceiptLine();
        line.purchaseOrderLineId = lost.purchaseOrderLineId;
        line.replacementQty = qty;
        line.unitCost = lost.unitCost;
        line.costPerUnit = lost.costPerUnit;
        line.compensatesLineId = lost.getId();
        line.note = note;
        return line;
    }

    /** Lost units a replacement may still cover. */
    public BigDecimal uncompensatedLoss() {
        return lossQty.subtract(compensatedQty);
    }

    /** Records {@code qty} replaced units; never more than are still lost. */
    public void compensate(BigDecimal qty) {
        if (qty == null || qty.signum() <= 0) {
            throw new BusinessRuleException("A replacement quantity must be positive");
        }
        if (qty.compareTo(uncompensatedLoss()) > 0) {
            throw new BusinessRuleException("A replacement can cover at most the %s still lost"
                    .formatted(uncompensatedLoss().stripTrailingZeros().toPlainString()));
        }
        compensatedQty = compensatedQty.add(qty);
    }

    /** What the loss still costs: lost units not yet replaced, at the price paid per unit. */
    public Money lossAmount() {
        return Money.of(uncompensatedLoss().multiply(costPerUnit), unitCost.getCurrency());
    }

    public UUID getPurchaseOrderLineId() {
        return purchaseOrderLineId;
    }

    public BigDecimal getDeliveredQty() {
        return deliveredQty;
    }

    public BigDecimal getDamagedQty() {
        return damagedQty;
    }

    public BigDecimal getLossQty() {
        return lossQty;
    }

    public BigDecimal getOverDeliveredQty() {
        return overDeliveredQty;
    }

    public OverDeliveryKind getOverDeliveryKind() {
        return overDeliveryKind;
    }

    public Money getUnitCost() {
        return unitCost;
    }

    public BigDecimal getCostPerUnit() {
        return costPerUnit;
    }

    public BigDecimal getCompensatedQty() {
        return compensatedQty;
    }

    public BigDecimal getReplacementQty() {
        return replacementQty;
    }

    public UUID getCompensatesLineId() {
        return compensatesLineId;
    }

    public String getNote() {
        return note;
    }
}
```

`GoodsReceiptRepository.java`:

```java
package vn.essvn.erpcafe.inventory.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import vn.essvn.erpcafe.inventory.domain.GoodsReceipt;

/**
 * Class: GoodsReceiptRepository
 * Purpose: Reads the goods receipts of purchase orders, oldest first, within the branches row-level security shows.
 */
@Transactional(readOnly = true)
public interface GoodsReceiptRepository extends JpaRepository<GoodsReceipt, UUID> {

    List<GoodsReceipt> findByPurchaseOrderIdOrderByCreatedAtAsc(UUID purchaseOrderId);

    List<GoodsReceipt> findByPurchaseOrderIdInOrderByCreatedAtAsc(Collection<UUID> purchaseOrderIds);

    /** Branch-scoped lookup: empty (→ 404) for a receipt outside the active branch. */
    Optional<GoodsReceipt> findByIdAndBranchId(UUID id, UUID branchId);
}
```

- [ ] **Step 4: Extend `ReceiptInput`**

In `PurchaseInputs`, replace `ReceiptInput` and its Javadoc with:

```java
    /**
     * A delivery against a PO line. {@code receivedQty} is what was delivered: whole packs on a pack line,
     * otherwise in {@code unit} (any unit of the line's dimension, ADR-0006; blank = the line's unit).
     * {@code unitCostOverride} is the invoice price per pack, or per {@code unit}. {@code damagedQty} is in
     * the line's unit on a pack line, otherwise in {@code unit}. {@code overDeliveryKind} says how units
     * beyond the outstanding quantity are taken.
     */
    public record ReceiptInput(UUID lineId, BigDecimal receivedQty, String unit, BigDecimal unitCostOverride,
            BigDecimal damagedQty, OverDeliveryKind overDeliveryKind, String note) {
    }
```

Add `import vn.essvn.erpcafe.inventory.domain.OverDeliveryKind;`.

- [ ] **Step 5: Write `GoodsReceivingService`**

```java
package vn.essvn.erpcafe.inventory.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.essvn.erpcafe.common.domain.Money;
import vn.essvn.erpcafe.common.domain.Unit;
import vn.essvn.erpcafe.common.exception.BusinessRuleException;
import vn.essvn.erpcafe.common.exception.ResourceNotFoundException;
import vn.essvn.erpcafe.inventory.application.PurchaseInputs.ReceiptInput;
import vn.essvn.erpcafe.inventory.domain.GoodsReceipt;
import vn.essvn.erpcafe.inventory.domain.GoodsReceiptKind;
import vn.essvn.erpcafe.inventory.domain.GoodsReceiptLine;
import vn.essvn.erpcafe.inventory.domain.Ingredient;
import vn.essvn.erpcafe.inventory.domain.PurchaseOrder;
import vn.essvn.erpcafe.inventory.domain.PurchaseOrderLine;
import vn.essvn.erpcafe.inventory.domain.PurchaseOrderStatus;
import vn.essvn.erpcafe.inventory.domain.ReceiptSplit;
import vn.essvn.erpcafe.inventory.persistence.GoodsReceiptRepository;
import vn.essvn.erpcafe.inventory.persistence.IngredientRepository;

/**
 * Class: GoodsReceivingService
 * Purpose: Turns a delivery or a replacement into a goods receipt, the purchase lines' progress, and stock at the price paid.
 */
@Service
@Transactional
public class GoodsReceivingService {

    public static final String REF_TYPE = "GOODS_RECEIPT";

    private final GoodsReceiptRepository receiptRepository;
    private final IngredientRepository ingredientRepository;
    private final StockLedgerService ledger;

    public GoodsReceivingService(GoodsReceiptRepository receiptRepository, IngredientRepository ingredientRepository,
            StockLedgerService ledger) {
        this.receiptRepository = receiptRepository;
        this.ingredientRepository = ingredientRepository;
        this.ledger = ledger;
    }

    /** Receives a delivery against a sent order: each line's split decides the order's progress, stock and loss. */
    public GoodsReceipt receive(PurchaseOrder order, List<ReceiptInput> inputs, String note) {
        if (order.getStatus() != PurchaseOrderStatus.SENT
                && order.getStatus() != PurchaseOrderStatus.PARTIALLY_RECEIVED) {
            throw new BusinessRuleException("Order must be SENT to receive (was %s)".formatted(order.getStatus()));
        }
        if (inputs == null || inputs.isEmpty()) {
            throw new BusinessRuleException("A receipt needs at least one line");
        }
        GoodsReceipt receipt = receiptRepository.save(
                new GoodsReceipt(order.getId(), order.getBranchId(), GoodsReceiptKind.DELIVERY, note));
        // Process in ingredientId order: stock rows must be locked in one global order on every path, or a
        // receipt and an order completion touching the same two ingredients can deadlock.
        record Resolved(ReceiptInput input, PurchaseOrderLine line) {
        }
        List<Resolved> resolved = new ArrayList<>();
        for (ReceiptInput input : inputs) {
            resolved.add(new Resolved(input, lineOf(order, input.lineId())));
        }
        resolved.sort(Comparator.comparing(r -> r.line().getIngredientId()));
        for (Resolved r : resolved) {
            receipt.addLine(receiveLine(order.getBranchId(), receipt.getId(), r.line(), r.input()));
        }
        order.refreshReceiptStatus();
        return receipt;
    }

    private GoodsReceiptLine receiveLine(UUID branchId, UUID receiptId, PurchaseOrderLine line, ReceiptInput input) {
        requireActive(requireIngredient(line.getIngredientId()));
        BigDecimal delivered = input.receivedQty();
        if (delivered == null || delivered.signum() <= 0) {
            throw new BusinessRuleException("Received quantity must be positive");
        }
        Unit lineUnit = Unit.parse(line.getUnit());
        Unit countedIn = lineUnit;
        BigDecimal deliveredOrdering;
        if (line.hasPack()) {
            if (input.unit() != null && !input.unit().isBlank()) {
                throw new BusinessRuleException("A %s line is received in whole %s; leave the unit out"
                        .formatted(line.getPackLabel(), line.getPackLabel()));
            }
            PurchaseOrderService.requireWholePacks(delivered, line.getPackLabel());
            deliveredOrdering = delivered;
        } else {
            // Without a pack a delivery may be counted in any unit of the line's dimension (ADR-0006).
            countedIn = input.unit() == null || input.unit().isBlank() ? lineUnit : Unit.parse(input.unit());
            deliveredOrdering = Unit.convert(delivered, countedIn, lineUnit);
        }
        BigDecimal damaged = input.damagedQty() == null ? BigDecimal.ZERO
                : Unit.convert(input.damagedQty(), countedIn, lineUnit);
        ReceiptSplit split = ReceiptSplit.of(deliveredOrdering, line.outstandingQty(), line.unitsPerOrderingUnit(),
                input.overDeliveryKind(), damaged);

        // The price per ordering unit is the order's, or the invoice's when it differs (per pack, or per the
        // unit the delivery was counted in); stock and the loss then use it per unit of the line.
        BigDecimal costPerOrdering = input.unitCostOverride() == null ? line.getUnitCost().getAmount()
                : line.hasPack() ? input.unitCostOverride()
                : Unit.convertRate(input.unitCostOverride(), countedIn, lineUnit);
        BigDecimal costPerUnit = costPerOrdering.divide(line.unitsPerOrderingUnit(), Unit.CALC_SCALE,
                RoundingMode.HALF_EVEN);

        line.growOrdered(split.growOrderedBy());
        line.receive(split.countedQty());
        // Damaged units never enter stock: the loss is valued at the price paid, not the blended average.
        BigDecimal rate = ledger.toBaseRate(branchId, line.getIngredientId(), costPerUnit, lineUnit.symbol());
        if (split.chargedIntoStock().signum() > 0) {
            ledger.receive(branchId, line.getIngredientId(), split.chargedIntoStock(), lineUnit.symbol(),
                    Money.of(rate), REF_TYPE, receiptId);
        }
        if (split.freeIntoStock().signum() > 0) {
            ledger.receive(branchId, line.getIngredientId(), split.freeIntoStock(), lineUnit.symbol(),
                    Money.zero(), REF_TYPE, receiptId);
        }
        return GoodsReceiptLine.delivered(line.getId(), deliveredOrdering, damaged, split, input.overDeliveryKind(),
                Money.of(costPerOrdering, line.getUnitCost().getCurrency()), costPerUnit, input.note());
    }

    static PurchaseOrderLine lineOf(PurchaseOrder order, UUID lineId) {
        return order.getLines().stream()
                .filter(l -> l.getId().equals(lineId))
                .findFirst()
                .orElseThrow(() -> ResourceNotFoundException.of("PurchaseOrderLine", lineId));
    }

    Ingredient requireIngredient(UUID ingredientId) {
        return ingredientRepository.findById(ingredientId)
                .orElseThrow(() -> ResourceNotFoundException.of("Ingredient", ingredientId));
    }

    static void requireActive(Ingredient ingredient) {
        if (!ingredient.isActive()) {
            throw new BusinessRuleException("Ingredient " + ingredient.getName() + " is inactive");
        }
    }
}
```

- [ ] **Step 6: Delegate from `PurchaseOrderService`**

In `PurchaseOrderService`:
- Inject `GoodsReceivingService receiving` and `GoodsReceiptRepository receiptRepository` through
  the constructor. Append them as the last two parameters and assign them.
- Replace the whole `receive(UUID id, List<ReceiptInput> receipts)` method with:

```java
    /** Receives a delivery: the goods receipt records it, and the order's lines and status move on. */
    public PurchaseOrder receive(UUID id, List<ReceiptInput> receipts, String note) {
        PurchaseOrder order = get(id);
        requireActiveSupplier(order.getSupplierId());
        receiving.receive(order, receipts, note);
        return order;
    }

    /** The goods receipts of each order, oldest first; orders with none are absent from the map. */
    @Transactional(readOnly = true)
    public Map<UUID, List<GoodsReceipt>> receiptsOf(List<UUID> orderIds) {
        if (orderIds.isEmpty()) {
            return Map.of();
        }
        return receiptRepository.findByPurchaseOrderIdInOrderByCreatedAtAsc(orderIds).stream()
                .collect(Collectors.groupingBy(GoodsReceipt::getPurchaseOrderId, LinkedHashMap::new, Collectors.toList()));
    }

    /** The order a goods receipt belongs to, so a stock movement can link to it; scoped to the active branch. */
    @Transactional(readOnly = true)
    public PurchaseOrder getByReceipt(UUID receiptId) {
        GoodsReceipt receipt = receiptRepository.findByIdAndBranchId(receiptId, BranchContext.require())
                .orElseThrow(() -> ResourceNotFoundException.of("GoodsReceipt", receiptId));
        return get(receipt.getPurchaseOrderId());
    }
```

- Remove `REF_TYPE` and the imports that no longer have a user (`Comparator`, `ArrayList`, `Unit`,
  `PurchaseOrderStatus`). Add these imports: `java.util.LinkedHashMap`, `java.util.Map`,
  `java.util.stream.Collectors`, `vn.essvn.erpcafe.inventory.domain.GoodsReceipt` and
  `vn.essvn.erpcafe.inventory.persistence.GoodsReceiptRepository`.
- Replace the class Javadoc sentence "Receiving raises stock and updates moving-average cost via
  {@link StockLedgerService}." with "Receiving goes through {@link GoodsReceivingService}."

- [ ] **Step 7: Extend the DTOs and the controller**

In `PurchaseOrderDtos`, replace `ReceiptRequest` and `ReceiveRequest` with:

```java
    public record ReceiptRequest(
            @NotNull UUID lineId,
            // What was delivered: whole packs on a pack line, otherwise in `unit` (blank = the line's unit).
            @NotNull @Positive BigDecimal receivedQty,
            @Size(max = 16) String unit,
            // The invoice price when it differs from the order: per pack, or per `unit`.
            @PositiveOrZero BigDecimal unitCostOverride,
            @PositiveOrZero BigDecimal damagedQty,
            OverDeliveryKind overDeliveryKind,
            @Size(max = 255) String note) {
    }

    public record ReceiveRequest(
            @Size(max = 255) String note,
            @NotEmpty @Valid List<ReceiptRequest> receipts) {
    }

    public record GoodsReceiptLineResponse(
            UUID id,
            UUID purchaseOrderLineId,
            BigDecimal deliveredQty,
            BigDecimal damagedQty,
            BigDecimal lossQty,
            BigDecimal overDeliveredQty,
            String overDeliveryKind,
            MoneyDto unitCost,
            BigDecimal costPerUnit,
            BigDecimal compensatedQty,
            BigDecimal replacementQty,
            UUID compensatesLineId,
            MoneyDto lossAmount,
            String note) {
    }

    public record GoodsReceiptResponse(
            UUID id,
            String kind,
            String note,
            Instant receivedAt,
            String receivedBy,
            List<GoodsReceiptLineResponse> lines) {
    }
```

Add `List<GoodsReceiptResponse> receipts` as the last component of `PurchaseOrderResponse`. Add the
imports `java.time.Instant` and `vn.essvn.erpcafe.inventory.domain.OverDeliveryKind`.

In `PurchaseOrderController`, replace the mapping helpers and every `toResponse(...)` call. Each
endpoint returns `respond(order)`; the list endpoint uses the batch form:

```java
    @GetMapping
    @PreAuthorize("hasAuthority('purchasing:read')")
    public PageResponse<PurchaseOrderResponse> list(
            @PageableDefault(sort = "createdAt", direction = Direction.DESC) Pageable pageable) {
        Page<PurchaseOrder> page = purchaseOrderService.list(pageable);
        Map<UUID, List<GoodsReceipt>> receipts = purchaseOrderService.receiptsOf(
                page.getContent().stream().map(PurchaseOrder::getId).toList());
        return PageResponse.from(page.map(o -> toResponse(o, receipts.getOrDefault(o.getId(), List.of()))));
    }

    @GetMapping("/by-receipt/{receiptId}")
    @PreAuthorize("hasAuthority('purchasing:read')")
    public PurchaseOrderResponse byReceipt(@PathVariable UUID receiptId) {
        return respond(purchaseOrderService.getByReceipt(receiptId));
    }

    @PostMapping("/{id}/receive")
    @PreAuthorize("hasAuthority('purchasing:write')")
    public PurchaseOrderResponse receive(@PathVariable UUID id, @Valid @RequestBody ReceiveRequest request) {
        List<ReceiptInput> receipts = request.receipts().stream()
                .map(r -> new ReceiptInput(r.lineId(), r.receivedQty(), r.unit(), r.unitCostOverride(),
                        r.damagedQty(), r.overDeliveryKind(), r.note()))
                .toList();
        return respond(purchaseOrderService.receive(id, receipts, request.note()));
    }

    private PurchaseOrderResponse respond(PurchaseOrder o) {
        return toResponse(o, purchaseOrderService.receiptsOf(List.of(o.getId())).getOrDefault(o.getId(), List.of()));
    }

    private static PurchaseOrderResponse toResponse(PurchaseOrder o, List<GoodsReceipt> receipts) {
        List<PoLineResponse> lines = o.getLines().stream().map(PurchaseOrderController::toLine).toList();
        return new PurchaseOrderResponse(o.getId(), o.getBranchId(), o.getSupplierId(), o.getKind().name(),
                o.getStatus().name(), o.getNote(), lines,
                receipts.stream().map(PurchaseOrderController::toReceipt).toList());
    }

    private static GoodsReceiptResponse toReceipt(GoodsReceipt r) {
        return new GoodsReceiptResponse(r.getId(), r.getKind().name(), r.getNote(), r.getCreatedAt(),
                r.getCreatedBy(), r.getLines().stream().map(PurchaseOrderController::toReceiptLine).toList());
    }

    private static GoodsReceiptLineResponse toReceiptLine(GoodsReceiptLine l) {
        return new GoodsReceiptLineResponse(l.getId(), l.getPurchaseOrderLineId(), l.getDeliveredQty(),
                l.getDamagedQty(), l.getLossQty(), l.getOverDeliveredQty(),
                l.getOverDeliveryKind() == null ? null : l.getOverDeliveryKind().name(),
                MoneyDto.from(l.getUnitCost()), l.getCostPerUnit(), l.getCompensatedQty(), l.getReplacementQty(),
                l.getCompensatesLineId(), MoneyDto.from(l.lossAmount()), l.getNote());
    }
```

`get`, `create`, `send` and `cancel` now return `respond(...)` of the service result (`create` keeps
its `ResponseEntity.created(...)` wrapper around `respond(...)`). Keep `toLine` from Task 3. Add these
imports: `org.springframework.data.domain.Page`, `java.util.Map`,
`vn.essvn.erpcafe.inventory.domain.GoodsReceipt`, `vn.essvn.erpcafe.inventory.domain.GoodsReceiptLine`,
and the new DTO records.

- [ ] **Step 8: Run the receiving tests and the earlier ones**

Run:

```bash
cd backend && ./gradlew test --tests 'vn.essvn.erpcafe.inventory.*' --tests 'vn.essvn.erpcafe.common.*' \
  --tests 'vn.essvn.erpcafe.ArchitectureTest'
```

Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add backend/src
git commit -m "[Add] Goods receipts with damage, over-delivery and receipt links"
```

---

### Task 6: Replacements for lost units

**Files:**
- Modify: `backend/src/main/java/vn/essvn/erpcafe/inventory/application/PurchaseInputs.java`
- Modify: `backend/src/main/java/vn/essvn/erpcafe/inventory/application/GoodsReceivingService.java`
- Modify: `backend/src/main/java/vn/essvn/erpcafe/inventory/application/PurchaseOrderService.java`
- Modify: `backend/src/main/java/vn/essvn/erpcafe/inventory/web/PurchaseOrderDtos.java`
- Modify: `backend/src/main/java/vn/essvn/erpcafe/inventory/web/PurchaseOrderController.java`
- Test: `backend/src/test/java/vn/essvn/erpcafe/inventory/ReceivingCompensationTest.java`

**Interfaces:**
- Consumes: `GoodsReceiptLine.compensate`, `replacement`, `getCostPerUnit` and
  `getPurchaseOrderLineId`, and `GoodsReceivingService.lineOf`, `requireIngredient` and
  `requireActive`, all from Task 5.
- Produces:
  - `CompensationInput(UUID receiptLineId, BigDecimal quantity)`;
  - `GoodsReceivingService.compensate(PurchaseOrder, List<CompensationInput>, String): GoodsReceipt`;
  - `PurchaseOrderService.compensate(UUID, List<CompensationInput>, String): PurchaseOrder`;
  - endpoint `POST /api/v1/purchase-orders/{id}/compensations`.

- [ ] **Step 1: Write the failing test**

```java
package vn.essvn.erpcafe.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;

import vn.essvn.erpcafe.support.AbstractIntegrationTest;

/**
 * Class: ReceivingCompensationTest
 * Purpose: A supplier's replacement enters at the lost units' price and clears the loss, never beyond what was lost.
 */
class ReceivingCompensationTest extends AbstractIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    private String token;
    private String branchId;
    private String straws;
    private String shop;
    private String box;

    @BeforeEach
    void setUp() throws Exception {
        token = adminToken();
        branchId = ((List<String>) JsonPath.read(
                mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, authHeader(token)))
                        .andReturn().getResponse().getContentAsString(), "$.branchIds")).get(0);
        int n = SEQ.incrementAndGet();
        straws = id(send(post("/api/v1/ingredients"), "{\"name\":\"CompStraws%d\",\"baseUnit\":\"pc\"}".formatted(n)));
        shop = id(send(post("/api/v1/suppliers"), "{\"name\":\"CompShop%d\"}".formatted(n)));
        box = id(send(post("/api/v1/items"), ("{\"name\":\"Box\",\"supplierId\":\"%s\",\"ingredientId\":\"%s\","
                + "\"unit\":\"pc\",\"packLabel\":\"hộp\",\"packSize\":100}").formatted(shop, straws)));
    }

    private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder b) {
        return b.header(HttpHeaders.AUTHORIZATION, authHeader(token)).header("X-Branch-Id", branchId);
    }

    private ResultActions send(MockHttpServletRequestBuilder b, String body) throws Exception {
        return mockMvc.perform(authed(b).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static String body(ResultActions r) throws Exception {
        return r.andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString();
    }

    private static String id(ResultActions r) throws Exception {
        return JsonPath.read(body(r), "$.id");
    }

    private String sentOrder(String itemId, String qty, String cost) throws Exception {
        String po = body(send(post("/api/v1/purchase-orders"),
                "{\"supplierId\":\"%s\",\"lines\":[{\"itemId\":\"%s\",\"orderedQty\":%s,\"unitCost\":%s}]}"
                        .formatted(shop, itemId, qty, cost)));
        mockMvc.perform(authed(post("/api/v1/purchase-orders/" + JsonPath.read(po, "$.id") + "/send")))
                .andExpect(status().isOk());
        return po;
    }

    private String receive(String po, String fields) throws Exception {
        return body(send(post("/api/v1/purchase-orders/" + JsonPath.read(po, "$.id") + "/receive"),
                "{\"receipts\":[{\"lineId\":\"%s\",%s}]}".formatted((String) JsonPath.read(po, "$.lines[0].id"), fields)));
    }

    private ResultActions compensate(String po, String lines) throws Exception {
        return send(post("/api/v1/purchase-orders/" + JsonPath.read(po, "$.id") + "/compensations"),
                "{\"note\":\"supplier sent 40\",\"lines\":[%s]}".formatted(lines));
    }

    private BigDecimal stock(String field) throws Exception {
        String json = body(mockMvc.perform(authed(get("/api/v1/stock"))));
        List<Object> vals = JsonPath.read(json, "$[?(@.ingredientId=='%s')].%s".formatted(straws, field));
        return vals.isEmpty() ? null : new BigDecimal(String.valueOf(vals.get(0)));
    }

    private static BigDecimal num(String json, String path) {
        return new BigDecimal(String.valueOf((Object) JsonPath.read(json, path)));
    }

    /** The worked example: 200 on hand at 400, then 5 boxes at 45,000 with 40 pieces damaged. */
    private String damagedDelivery() throws Exception {
        String loose = itemFor(token, branchId, shop, straws, "pc");
        receive(sentOrder(loose, "200", "400"), "\"receivedQty\":200");
        String po = sentOrder(box, "5", "45000");
        receive(po, "\"receivedQty\":5,\"damagedQty\":40");
        return po;
    }

    private static String lostLine(String poJson) {
        return JsonPath.read(poJson, "$.receipts[0].lines[0].id");
    }

    @Test
    void replacement_entersAtTheLostUnitsPrice_andClearsTheLoss() throws Exception {
        String po = damagedDelivery();
        String lost = lostLine(body(mockMvc.perform(authed(get("/api/v1/purchase-orders/" + JsonPath.read(po, "$.id"))))));

        String after = body(compensate(po, "{\"receiptLineId\":\"%s\",\"quantity\":40}".formatted(lost)));

        assertThat(stock("quantityOnHand")).isEqualByComparingTo("700");
        assertThat(stock("avgUnitCost.amount")).isEqualByComparingTo("435.7143");
        assertThat(num(after, "$.receipts[0].lines[0].lossAmount.amount")).isEqualByComparingTo("0");
        assertThat(num(after, "$.receipts[0].lines[0].compensatedQty")).isEqualByComparingTo("40");
        assertThat((String) JsonPath.read(after, "$.receipts[1].kind")).isEqualTo("COMPENSATION");
        assertThat((String) JsonPath.read(after, "$.receipts[1].lines[0].compensatesLineId")).isEqualTo(lost);
    }

    @Test
    void replacement_cannotExceedTheRemainingLoss() throws Exception {
        String po = damagedDelivery();
        String lost = lostLine(body(mockMvc.perform(authed(get("/api/v1/purchase-orders/" + JsonPath.read(po, "$.id"))))));

        compensate(po, "{\"receiptLineId\":\"%s\",\"quantity\":41}".formatted(lost)).andExpect(status().isUnprocessableEntity());
        compensate(po, "{\"receiptLineId\":\"%s\",\"quantity\":30}".formatted(lost)).andExpect(status().isOk());
        compensate(po, "{\"receiptLineId\":\"%s\",\"quantity\":11}".formatted(lost)).andExpect(status().isUnprocessableEntity());

        assertThat(stock("quantityOnHand")).isEqualByComparingTo("690");
    }

    @Test
    void twoReplacementsForOneLine_areCappedTogether_andNothingEnters() throws Exception {
        String po = damagedDelivery();
        String lost = lostLine(body(mockMvc.perform(authed(get("/api/v1/purchase-orders/" + JsonPath.read(po, "$.id"))))));

        compensate(po, "{\"receiptLineId\":\"%s\",\"quantity\":30},{\"receiptLineId\":\"%s\",\"quantity\":30}"
                .formatted(lost, lost)).andExpect(status().isUnprocessableEntity());

        assertThat(stock("quantityOnHand")).isEqualByComparingTo("660");
    }

    @Test
    void replacement_onACancelledOrder_isRefused() throws Exception {
        String po = sentOrder(box, "5", "45000");
        receive(po, "\"receivedQty\":4,\"damagedQty\":40");
        mockMvc.perform(authed(post("/api/v1/purchase-orders/" + JsonPath.read(po, "$.id") + "/cancel")))
                .andExpect(status().isOk());
        String lost = lostLine(body(mockMvc.perform(authed(get("/api/v1/purchase-orders/" + JsonPath.read(po, "$.id"))))));

        compensate(po, "{\"receiptLineId\":\"%s\",\"quantity\":40}".formatted(lost)).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void unknownReceiptLine_isNotFound_andAFreeGiftsDamageIsNotReplaced() throws Exception {
        String po = sentOrder(box, "5", "45000");
        String after = receive(po, "\"receivedQty\":6,\"overDeliveryKind\":\"FREE\",\"damagedQty\":0");

        compensate(po, "{\"receiptLineId\":\"%s\",\"quantity\":1}".formatted(UUID.randomUUID())).andExpect(status().isNotFound());
        compensate(po, "{\"receiptLineId\":\"%s\",\"quantity\":1}".formatted(lostLine(after))).andExpect(status().isUnprocessableEntity());
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `cd backend && ./gradlew test --tests 'vn.essvn.erpcafe.inventory.ReceivingCompensationTest'`
Expected: FAIL. `/compensations` is not mapped and answers 404, or 405.

- [ ] **Step 3: Implement**

In `PurchaseInputs`, add:

```java
    /** Units a supplier sent to replace units a receipt line lost, in the line's unit. */
    public record CompensationInput(UUID receiptLineId, BigDecimal quantity) {
    }
```

In `GoodsReceivingService`, add these imports: `java.util.Map`, `java.util.function.Function`,
`java.util.stream.Collectors`,
`vn.essvn.erpcafe.inventory.application.PurchaseInputs.CompensationInput`. Then add:

```java
    /** Receives replacements for units a delivery lost, at the price those units cost; the loss shrinks by as much. */
    public GoodsReceipt compensate(PurchaseOrder order, List<CompensationInput> inputs, String note) {
        if (order.getStatus() == PurchaseOrderStatus.CANCELLED) {
            throw new BusinessRuleException("Cannot receive replacements on a cancelled order");
        }
        if (inputs == null || inputs.isEmpty()) {
            throw new BusinessRuleException("A replacement needs at least one line");
        }
        Map<UUID, GoodsReceiptLine> received = receiptRepository.findByPurchaseOrderIdOrderByCreatedAtAsc(order.getId())
                .stream().flatMap(r -> r.getLines().stream())
                .collect(Collectors.toMap(GoodsReceiptLine::getId, Function.identity()));
        record Resolved(CompensationInput input, GoodsReceiptLine lost, PurchaseOrderLine line) {
        }
        List<Resolved> resolved = new ArrayList<>();
        for (CompensationInput input : inputs) {
            GoodsReceiptLine lost = received.get(input.receiptLineId());
            if (lost == null) {
                throw ResourceNotFoundException.of("GoodsReceiptLine", input.receiptLineId());
            }
            resolved.add(new Resolved(input, lost, lineOf(order, lost.getPurchaseOrderLineId())));
        }
        resolved.sort(Comparator.comparing(r -> r.line().getIngredientId()));
        GoodsReceipt receipt = receiptRepository.save(
                new GoodsReceipt(order.getId(), order.getBranchId(), GoodsReceiptKind.COMPENSATION, note));
        for (Resolved r : resolved) {
            requireActive(requireIngredient(r.line().getIngredientId()));
            r.lost().compensate(r.input().quantity());
            Unit lineUnit = Unit.parse(r.line().getUnit());
            BigDecimal rate = ledger.toBaseRate(order.getBranchId(), r.line().getIngredientId(),
                    r.lost().getCostPerUnit(), lineUnit.symbol());
            ledger.receive(order.getBranchId(), r.line().getIngredientId(), r.input().quantity(), lineUnit.symbol(),
                    Money.of(rate), REF_TYPE, receipt.getId());
            receipt.addLine(GoodsReceiptLine.replacement(r.lost(), r.input().quantity(), null));
        }
        return receipt;
    }
```

The class is `@Transactional`, so a refusal on the second line rolls back the first line's ledger
receipt and its `compensate`. `twoReplacementsForOneLine_areCappedTogether_andNothingEnters` checks
this.

In `PurchaseOrderService`, add:

```java
    /** Receives a supplier's replacement for lost units against the order's receipts. */
    public PurchaseOrder compensate(UUID id, List<CompensationInput> lines, String note) {
        PurchaseOrder order = get(id);
        receiving.compensate(order, lines, note);
        return order;
    }
```

In `PurchaseOrderDtos`, add:

```java
    public record CompensationLineRequest(
            @NotNull UUID receiptLineId,
            @NotNull @Positive BigDecimal quantity) {
    }

    public record CompensationRequest(
            @Size(max = 255) String note,
            @NotEmpty @Valid List<CompensationLineRequest> lines) {
    }
```

In `PurchaseOrderController`, add:

```java
    @PostMapping("/{id}/compensations")
    @PreAuthorize("hasAuthority('purchasing:write')")
    public PurchaseOrderResponse compensate(@PathVariable UUID id, @Valid @RequestBody CompensationRequest request) {
        List<CompensationInput> lines = request.lines().stream()
                .map(l -> new CompensationInput(l.receiptLineId(), l.quantity()))
                .toList();
        return respond(purchaseOrderService.compensate(id, lines, request.note()));
    }
```

- [ ] **Step 4: Run it and watch it pass**

Run: `cd backend && ./gradlew test --tests 'vn.essvn.erpcafe.inventory.ReceivingCompensationTest' --tests 'vn.essvn.erpcafe.ArchitectureTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "[Add] Receive a supplier's replacement for lost units"
```

---

### Task 7: Retail purchase and the `purchasing:retail` permission

**Files:**
- Modify: `backend/src/main/java/vn/essvn/erpcafe/identity/domain/Permissions.java`
- Modify: `backend/src/main/java/vn/essvn/erpcafe/identity/domain/DefaultRole.java`
- Modify: `backend/src/main/java/vn/essvn/erpcafe/inventory/application/PurchaseOrderService.java`
- Modify: `backend/src/main/java/vn/essvn/erpcafe/inventory/web/PurchaseOrderDtos.java`
- Modify: `backend/src/main/java/vn/essvn/erpcafe/inventory/web/PurchaseOrderController.java`
- Modify: `backend/src/test/java/vn/essvn/erpcafe/identity/DefaultRolePermissionSyncTest.java`
- Test: `backend/src/test/java/vn/essvn/erpcafe/inventory/RetailPurchaseTest.java`

**Interfaces:**
- Consumes: `PurchaseOrderService.create`, `PurchaseOrder.markRetail()` and `send()`, and
  `GoodsReceivingService.receive(...)`.
- Produces:
  - `Permissions.PURCHASING_RETAIL = "purchasing:retail"`, with `CATALOG_VERSION` raised to 2;
  - `PurchaseOrderService.retail(UUID supplierId, String note, List<LineInput> lines): PurchaseOrder`;
  - endpoint `POST /api/v1/purchase-orders/retail`, body
    `{ supplierId, note, lines: [{ itemId, quantity, unitCost }] }`.

- [ ] **Step 1: Write the failing tests**

Add to `DefaultRolePermissionSyncTest`:

```java
    @Test
    void managerHoldsRetailPurchasing_cashierDoesNotByDefault() {
        assertThat(names(role(DefaultRole.MANAGER.roleName()))).contains(Permissions.PURCHASING_RETAIL);
        assertThat(names(role(DefaultRole.CASHIER.roleName()))).doesNotContain(Permissions.PURCHASING_RETAIL);
    }

    @Test
    void managerSyncedBeforeRetailPurchasing_receivesItOnStartup() {
        Role manager = role(DefaultRole.MANAGER.roleName());
        manager.getPermissions().removeIf(p -> Permissions.PURCHASING_RETAIL.equals(p.getName()));
        manager.setSyncedCatalogVersion(1);
        save(manager);

        dataInitializer.run(null);

        Role synced = role(DefaultRole.MANAGER.roleName());
        assertThat(names(synced)).contains(Permissions.PURCHASING_RETAIL);
        assertThat(synced.getSyncedCatalogVersion()).isEqualTo(Permissions.CATALOG_VERSION);
    }
```

Create `RetailPurchaseTest.java`:

```java
package vn.essvn.erpcafe.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;

import vn.essvn.erpcafe.identity.persistence.RoleRepository;
import vn.essvn.erpcafe.support.AbstractIntegrationTest;

/**
 * Class: RetailPurchaseTest
 * Purpose: A retail purchase saves a received order and its stock in one step, all or nothing, under its own permission.
 */
class RetailPurchaseTest extends AbstractIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    private RoleRepository roles;

    private final List<String> createdBranches = new ArrayList<>();
    private final List<UUID> createdRoles = new ArrayList<>();
    private String token;
    private String branchId;
    private String coffee;
    private String shop;
    private String bag;

    @BeforeEach
    void setUp() throws Exception {
        token = adminToken();
        branchId = ((List<String>) JsonPath.read(
                mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, authHeader(token)))
                        .andReturn().getResponse().getContentAsString(), "$.branchIds")).get(0);
        int n = SEQ.incrementAndGet();
        coffee = id(send(post("/api/v1/ingredients"), token, branchId,
                "{\"name\":\"RetailCoffee%d\",\"baseUnit\":\"g\"}".formatted(n)));
        shop = id(send(post("/api/v1/suppliers"), token, branchId, "{\"name\":\"Tạp hóa Cô Ba %d\"}".formatted(n)));
        bag = id(send(post("/api/v1/items"), token, branchId, ("{\"name\":\"Cà phê bịch 500g\",\"supplierId\":\"%s\","
                + "\"ingredientId\":\"%s\",\"unit\":\"g\",\"packLabel\":\"bịch\",\"packSize\":500}").formatted(shop, coffee)));
    }

    /** Deleting a branch ends the owner's token, so each deletion takes a fresh one. */
    @AfterEach
    void cleanUp() throws Exception {
        for (String id : createdBranches) {
            send(delete("/api/v1/branches/" + id), adminToken(), null, null).andExpect(status().isNoContent());
        }
        createdRoles.forEach(roles::deleteById);
    }

    private ResultActions send(MockHttpServletRequestBuilder b, String tok, String branch, String body) throws Exception {
        b.header(HttpHeaders.AUTHORIZATION, authHeader(tok));
        if (branch != null) {
            b.header("X-Branch-Id", branch);
        }
        if (body != null) {
            b.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mockMvc.perform(b);
    }

    private static String body(ResultActions r) throws Exception {
        return r.andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString();
    }

    private static String id(ResultActions r) throws Exception {
        return JsonPath.read(body(r), "$.id");
    }

    private BigDecimal stock(String tok, String branch, String field) throws Exception {
        String json = body(send(get("/api/v1/stock"), tok, branch, null));
        List<Object> vals = JsonPath.read(json, "$[?(@.ingredientId=='%s')].%s".formatted(coffee, field));
        return vals.isEmpty() ? null : new BigDecimal(String.valueOf(vals.get(0)));
    }

    private int orderCount() throws Exception {
        return ((List<?>) JsonPath.read(body(send(get("/api/v1/purchase-orders"), token, branchId, null)),
                "$.content")).size();
    }

    @Test
    void retailPurchase_savesAReceivedRetailOrder_andStock() throws Exception {
        String json = body(send(post("/api/v1/purchase-orders/retail"), token, branchId,
                ("{\"supplierId\":\"%s\",\"note\":\"ran out at 9pm\",\"lines\":[{\"itemId\":\"%s\",\"quantity\":1,"
                        + "\"unitCost\":95000}]}").formatted(shop, bag)));

        assertThat((String) JsonPath.read(json, "$.kind")).isEqualTo("RETAIL");
        assertThat((String) JsonPath.read(json, "$.status")).isEqualTo("RECEIVED");
        assertThat((List<?>) JsonPath.read(json, "$.receipts")).hasSize(1);
        assertThat(stock(token, branchId, "quantityOnHand")).isEqualByComparingTo("500");
        assertThat(stock(token, branchId, "avgUnitCost.amount")).isEqualByComparingTo("190");
    }

    @Test
    void aFailingLine_savesNothing() throws Exception {
        String otherShop = id(send(post("/api/v1/suppliers"), token, branchId,
                "{\"name\":\"OtherShop%d\"}".formatted(SEQ.incrementAndGet())));
        String foreignItem = itemFor(token, branchId, otherShop, coffee, "g");
        int before = orderCount();

        send(post("/api/v1/purchase-orders/retail"), token, branchId,
                ("{\"supplierId\":\"%s\",\"lines\":[{\"itemId\":\"%s\",\"quantity\":1,\"unitCost\":95000},"
                        + "{\"itemId\":\"%s\",\"quantity\":1,\"unitCost\":1}]}").formatted(shop, bag, foreignItem))
                .andExpect(status().isUnprocessableEntity());

        assertThat(orderCount()).isEqualTo(before);
        assertThat(stock(token, branchId, "quantityOnHand")).isNull();
    }

    @Test
    void retailOnly_recordsARetailPurchase_butCannotOrderOrDeclareItems() throws Exception {
        int n = SEQ.incrementAndGet();
        String branch = id(send(post("/api/v1/branches"), token, null,
                "{\"name\":\"Retail %d\",\"code\":\"RT%d\"}".formatted(n, n)));
        createdBranches.add(branch);
        String roleId = id(send(post("/api/v1/roles"), token, null,
                "{\"name\":\"RETAIL_ONLY_%d\",\"description\":\"d\",\"permissions\":[\"inventory:read\",\"purchasing:retail\"]}"
                        .formatted(n)));
        createdRoles.add(UUID.fromString(roleId));
        String email = "retail%d@t.test".formatted(n);
        String userId = id(send(post("/api/v1/users"), token, null,
                "{\"email\":\"%s\",\"password\":\"password123\",\"fullName\":\"Retail Only\"}".formatted(email)));
        body(send(post("/api/v1/users/" + userId + "/access"), token, null,
                "{\"branchId\":\"%s\",\"roleId\":\"%s\"}".formatted(branch, roleId)));
        String cashier = accessToken(email, "password123");

        send(post("/api/v1/purchase-orders/retail"), cashier, branch,
                "{\"supplierId\":\"%s\",\"lines\":[{\"itemId\":\"%s\",\"quantity\":2,\"unitCost\":95000}]}"
                        .formatted(shop, bag))
                .andExpect(status().isCreated());
        send(post("/api/v1/purchase-orders"), cashier, branch,
                "{\"supplierId\":\"%s\",\"lines\":[{\"itemId\":\"%s\",\"orderedQty\":1,\"unitCost\":1}]}"
                        .formatted(shop, bag))
                .andExpect(status().isForbidden());
        send(post("/api/v1/items"), cashier, branch,
                "{\"name\":\"x\",\"supplierId\":\"%s\",\"ingredientId\":\"%s\",\"unit\":\"g\"}".formatted(shop, coffee))
                .andExpect(status().isForbidden());

        assertThat(stock(cashier, branch, "quantityOnHand")).isEqualByComparingTo("1000");
    }
}
```

- [ ] **Step 2: Run them and watch them fail**

Run:

```bash
cd backend && ./gradlew test --tests 'vn.essvn.erpcafe.inventory.RetailPurchaseTest' \
  --tests 'vn.essvn.erpcafe.identity.DefaultRolePermissionSyncTest'
```

Expected: FAIL at compilation, because `Permissions.PURCHASING_RETAIL` does not exist.

- [ ] **Step 3: Add the permission**

In `Permissions.java`:
- after `PURCHASING_WRITE`, add
  `public static final String PURCHASING_RETAIL = "purchasing:retail";`;
- in `CATALOG`, after the `PURCHASING_WRITE` entry, add
  `Map.entry(PURCHASING_RETAIL, "Record retail purchases"),`;
- set `public static final int CATALOG_VERSION = 2;`;
- in `SINCE`, after `Map.entry(PURCHASING_WRITE, 1),`, add `Map.entry(PURCHASING_RETAIL, 2),`.

In `DefaultRole.MANAGER`, after `Permissions.PURCHASING_WRITE,`, add
`Permissions.PURCHASING_RETAIL,`. OWNER already holds `ALL`. CASHIER is unchanged.

- [ ] **Step 4: Add `retail` to `PurchaseOrderService`**

```java
    /** Records a purchase already paid and carried in: an order of kind RETAIL, received in full in the same save. */
    public PurchaseOrder retail(UUID supplierId, String note, List<LineInput> lines) {
        PurchaseOrder order = create(supplierId, note, lines);
        order.markRetail();
        order.send();
        List<ReceiptInput> everything = order.getLines().stream()
                .map(l -> new ReceiptInput(l.getId(), l.getOrderedQty(), null, null, null, null, null))
                .toList();
        receiving.receive(order, everything, note);
        return order;
    }
```

`create` saves the order, so each line has its id. The class-level `@Transactional` makes the whole
save atomic.

- [ ] **Step 5: Add the DTOs and the endpoint**

In `PurchaseOrderDtos`:

```java
    public record RetailLineRequest(
            @NotNull UUID itemId,
            @NotNull @Positive BigDecimal quantity,
            @NotNull @PositiveOrZero BigDecimal unitCost) {
    }

    public record RetailPurchaseRequest(
            @NotNull UUID supplierId,
            @Size(max = 255) String note,
            @NotEmpty @Valid List<RetailLineRequest> lines) {
    }
```

In `PurchaseOrderController`:

```java
    /** A purchase paid and carried in: saved as an order already received (requires {@code X-Branch-Id}). */
    @PostMapping("/retail")
    @PreAuthorize("hasAuthority('purchasing:retail')")
    public ResponseEntity<PurchaseOrderResponse> retail(@Valid @RequestBody RetailPurchaseRequest request) {
        List<LineInput> lines = request.lines().stream()
                .map(l -> new LineInput(l.itemId(), null, null, l.quantity(), l.unitCost()))
                .toList();
        PurchaseOrderResponse body = respond(purchaseOrderService.retail(request.supplierId(), request.note(), lines));
        return ResponseEntity.created(URI.create(ApiVersions.V1 + "/purchase-orders/" + body.id())).body(body);
    }
```

- [ ] **Step 6: Run the full backend suite**

Run: `cd backend && ./gradlew test --rerun`
Expected: PASS, with every test green, including `ArchitectureTest`, `AccessPolicyTest`,
`RlsEverywhereTest` and the stock-guard tests.

- [ ] **Step 7: Commit**

```bash
git add backend/src
git commit -m "[Add] Retail purchase under its own permission"
```

---

### Task 8: Frontend data layer and purchase maths

**Files:**
- Modify: `frontend/src/app/core/units.ts`, `frontend/src/app/core/models.ts`,
  `frontend/src/app/core/api.services.ts`
- Create: `frontend/src/app/features/inventory/purchase-math.ts`
- Test: `frontend/src/app/features/inventory/purchase-math.spec.ts`

**Interfaces:**
- Produces:
  - from `units.ts`: `FACTORS`, `convertQty(qty, from, to)` and `convertRate(rate, from, to)`;
  - models `GoodsReceiptResponse`, `GoodsReceiptLineResponse`, `OverDeliveryKind`,
    `CompensationRequest` and `RetailPurchaseRequest`, plus pack fields on items and PO lines;
  - API methods `compensatePurchaseOrder`, `retailPurchase` and `purchaseOrderByReceipt`;
  - maths functions `orderingUnit`, `perOrdering`, `packSentence`, `conversionLine`,
    `receiptSummary`, `fmtQty` and `fmtRate`, and the type `OverKind`.

- [ ] **Step 1: Write the failing spec**

`purchase-math.spec.ts`:

```ts
import { conversionLine, orderingUnit, packSentence, perOrdering, receiptSummary } from './purchase-math';

const box = { unit: 'pc', packLabel: 'hộp', packSize: '100' };
const sack = { unit: 'kg', packLabel: 'bao', packSize: '25' };

describe('purchase maths', () => {
  it('counts a line in its pack, or in its unit without one', () => {
    expect(orderingUnit(box)).toBe('hộp');
    expect(orderingUnit({ unit: 'kg' })).toBe('kg');
    expect(perOrdering(box)).toBe(100);
    expect(perOrdering({ unit: 'kg' })).toBe(1);
  });

  it('states a pack, and its stock size when the stock unit differs', () => {
    expect(packSentence(box, 'pc')).toBe('1 hộp = 100 pc');
    expect(packSentence(sack, 'g')).toBe('1 bao = 25 kg = 25.000 g');
    expect(packSentence({ unit: 'kg' }, 'g')).toBe('');
  });

  it('shows what a line puts into stock', () => {
    expect(conversionLine(5, 45000, box, 'pc', 'Ống hút giấy')).toBe('= 500 pc Ống hút giấy · 450 ₫/pc');
    expect(conversionLine(2, 480000, sack, 'g', 'Bột mì')).toBe('= 50 kg = 50.000 g Bột mì · 19,2 ₫/g');
    expect(conversionLine(0, 45000, box, 'pc', 'Ống hút giấy')).toBe('');
  });

  it('mirrors the server split: damage leaves stock and is a loss at the price paid', () => {
    const s = receiptSummary({ delivered: 5, outstanding: 5, damaged: 40, overKind: null, costPerOrdering: 45000, perOrdering: 100 });
    expect(s).toMatchObject({ over: 0, chargedIntoStock: 460, freeIntoStock: 0, lossQty: 40, lossAmount: 18000, costPerUnit: 450, error: '' });
  });

  it('counts damage against the paid units first, and free extras at zero', () => {
    const s = receiptSummary({ delivered: 6, outstanding: 5, damaged: 150, overKind: 'FREE', costPerOrdering: 45000, perOrdering: 100 });
    expect(s).toMatchObject({ over: 1, chargedIntoStock: 350, freeIntoStock: 100, lossQty: 150, lossAmount: 67500 });
  });

  it('asks for a choice on an extra, and refuses damage beyond the delivery', () => {
    expect(receiptSummary({ delivered: 6, outstanding: 5, damaged: 0, overKind: null, costPerOrdering: 1, perOrdering: 100 }).error)
      .toContain('choose charged or free gift');
    expect(receiptSummary({ delivered: 5, outstanding: 5, damaged: 501, overKind: null, costPerOrdering: 1, perOrdering: 100 }).error)
      .toContain('cannot exceed');
    expect(receiptSummary({ delivered: 2.5, outstanding: 5, damaged: 0, overKind: null, costPerOrdering: 1, perOrdering: 100 }).error)
      .toContain('whole');
  });
});
```

- [ ] **Step 2: Run it and watch it fail**

Run: `cd frontend && npx ng test --watch=false --include src/app/features/inventory/purchase-math.spec.ts`
Expected: FAIL. `./purchase-math` cannot be resolved.

- [ ] **Step 3: Add the unit factors**

Append to `core/units.ts`:

```ts
/** Each unit's size in its dimension's canonical unit (g, ml, pc), mirroring the backend `Unit` factors. */
export const FACTORS: Record<string, number> = { mg: 0.001, g: 1, kg: 1000, ml: 1, cl: 10, l: 1000, pc: 1 };

/** Converts a quantity within one dimension; callers pair only units `unitsFor` allows together. */
export function convertQty(qty: number, from: string, to: string): number {
  return (qty * (FACTORS[from] ?? 1)) / (FACTORS[to] ?? 1);
}

/** Converts a per-unit rate, which scales inversely: 210.000 ₫/kg is 210 ₫/g. */
export function convertRate(rate: number, from: string, to: string): number {
  return (rate * (FACTORS[to] ?? 1)) / (FACTORS[from] ?? 1);
}
```

- [ ] **Step 4: Write `purchase-math.ts`**

```ts
import { convertQty, convertRate } from '../../core/units';

/** What a pack-aware line needs: its own unit and its optional pack. */
export interface PackInfo { unit: string; packLabel?: string | null; packSize?: string | number | null; }

export type OverKind = 'CHARGED' | 'FREE';

const qtyFormat = new Intl.NumberFormat('vi-VN', { maximumFractionDigits: 3 });
const rateFormat = new Intl.NumberFormat('vi-VN', { maximumFractionDigits: 4 });
export const fmtQty = (n: number) => qtyFormat.format(n);
export const fmtRate = (n: number) => rateFormat.format(n);

/** The unit a purchase line is counted in: its pack when it has one, otherwise its unit. */
export function orderingUnit(p: PackInfo): string {
  return p.packLabel ? p.packLabel : p.unit;
}

/** How many of the line's unit one ordering unit holds: the pack size, or 1. */
export function perOrdering(p: PackInfo): number {
  return p.packLabel && p.packSize ? Number(p.packSize) : 1;
}

/** "1 hộp = 100 pc", or "1 bao = 25 kg = 25.000 g" when stock counts another unit; '' without a pack. */
export function packSentence(p: PackInfo, baseUnit?: string): string {
  if (!p.packLabel || !p.packSize) return '';
  const size = Number(p.packSize);
  let s = `1 ${p.packLabel} = ${fmtQty(size)} ${p.unit}`;
  if (baseUnit && baseUnit !== p.unit) s += ` = ${fmtQty(convertQty(size, p.unit, baseUnit))} ${baseUnit}`;
  return s;
}

/** "= 500 pc Ống hút giấy · 450 ₫/pc": the stock a line will create; '' until it has a quantity. */
export function conversionLine(qty: number, costPerOrdering: number, p: PackInfo, baseUnit: string, ingredient: string): string {
  if (!(qty > 0) || !baseUnit) return '';
  const units = qty * perOrdering(p);
  const quantity = convertQty(units, p.unit, baseUnit);
  let s = '= ' + (p.unit !== baseUnit ? `${fmtQty(units)} ${p.unit} = ` : '') + `${fmtQty(quantity)} ${baseUnit} ${ingredient}`;
  if (costPerOrdering > 0) s += ` · ${fmtRate(convertRate(costPerOrdering / perOrdering(p), p.unit, baseUnit))} ₫/${baseUnit}`;
  return s;
}

export interface ReceiptDraft {
  delivered: number; outstanding: number; damaged: number; overKind: OverKind | null;
  costPerOrdering: number; perOrdering: number;
}
export interface ReceiptSummary {
  over: number; chargedIntoStock: number; freeIntoStock: number; lossQty: number; lossAmount: number;
  costPerUnit: number; error: string;
}

/** Mirrors the server's ReceiptSplit, so the drawer shows what confirming will do before it is pressed. */
export function receiptSummary(d: ReceiptDraft): ReceiptSummary {
  const over = Math.max(0, d.delivered - d.outstanding);
  const freeOrdering = d.overKind === 'FREE' ? over : 0;
  const chargedUnits = Math.max(0, d.delivered - freeOrdering) * d.perOrdering;
  const freeUnits = freeOrdering * d.perOrdering;
  const costPerUnit = d.perOrdering > 0 ? d.costPerOrdering / d.perOrdering : 0;
  let error = '';
  if (!(d.delivered > 0)) error = 'Delivered must be above 0';
  else if (d.perOrdering !== 1 && !Number.isInteger(d.delivered)) error = 'Packs are counted whole';
  else if (over > 0 && !d.overKind) error = `${fmtQty(over)} more than outstanding: choose charged or free gift`;
  else if (d.damaged < 0 || d.damaged > chargedUnits + freeUnits) error = 'Damaged cannot exceed what was delivered';
  const damagedCharged = Math.min(Math.max(d.damaged, 0), chargedUnits);
  const damagedFree = Math.max(0, d.damaged - damagedCharged);
  return {
    over, chargedIntoStock: chargedUnits - damagedCharged, freeIntoStock: Math.max(0, freeUnits - damagedFree),
    lossQty: damagedCharged, lossAmount: damagedCharged * costPerUnit, costPerUnit, error,
  };
}
```

A pack of size 1 counts as "no pack" for the whole-number rule. The server still refuses fractions on
a pack line, so a box of 1 is enforced there.

- [ ] **Step 5: Update the models and the API service**

In `core/models.ts`:
- replace `ItemResponse` and `ItemRequest` with:

```ts
export interface ItemResponse {
  id: UUID; companyId: UUID; sku?: string; name: string; supplierId: UUID; ingredientId: UUID;
  unit: string; packLabel?: string | null; packSize?: string | null; active: boolean; version: number;
}
// supplierId/ingredientId are fixed after create; the server ignores them on update. A pack is both halves or neither.
export interface ItemRequest {
  sku?: string; name: string; supplierId: UUID; ingredientId: UUID; unit: string;
  packLabel?: string | null; packSize?: string | null; active?: boolean; version?: number;
}
```

- replace everything from `export interface PoLineResponse` through `export interface ReceiveRequest`
  with:

```ts
export interface PoLineResponse {
  id: UUID; itemId?: UUID; ingredientId: UUID; unit: string; packLabel?: string | null; packSize?: string | null;
  orderedQty: string; receivedQty: string; unitCost: Money;
}
export type OverDeliveryKind = 'CHARGED' | 'FREE';
export interface GoodsReceiptLineResponse {
  id: UUID; purchaseOrderLineId: UUID; deliveredQty: string; damagedQty: string; lossQty: string;
  overDeliveredQty: string; overDeliveryKind?: OverDeliveryKind | null; unitCost: Money; costPerUnit: string;
  compensatedQty: string; replacementQty: string; compensatesLineId?: UUID | null; lossAmount: Money; note?: string | null;
}
export interface GoodsReceiptResponse {
  id: UUID; kind: 'DELIVERY' | 'COMPENSATION'; note?: string | null; receivedAt: string; receivedBy?: string | null;
  lines: GoodsReceiptLineResponse[];
}
export interface PurchaseOrderResponse {
  id: UUID; branchId: UUID; supplierId: UUID; kind: 'ORDER' | 'RETAIL'; status: string; note?: string;
  lines: PoLineResponse[]; receipts: GoodsReceiptResponse[];
}
// A line is an item (unit comes from it) or an ingredient + unit — never both. Qty and cost are per `unit`.
export interface PoLineRequest { itemId?: UUID; ingredientId?: UUID; unit?: string; orderedQty: string; unitCost: string; }
export interface CreatePurchaseOrderRequest { supplierId: UUID; note?: string; lines: PoLineRequest[]; }
export interface ReceiptRequest {
  lineId: UUID; receivedQty: string; unitCostOverride?: string | null; damagedQty?: string | null;
  overDeliveryKind?: OverDeliveryKind | null; note?: string | null;
}
export interface ReceiveRequest { note?: string | null; receipts: ReceiptRequest[]; }
export interface CompensationRequest { note?: string | null; lines: { receiptLineId: UUID; quantity: string }[]; }
export interface RetailPurchaseRequest { supplierId: UUID; note?: string | null; lines: { itemId: UUID; quantity: string; unitCost: string }[]; }
```

In `core/api.services.ts`, after `cancelPurchaseOrder`, add:

```ts
  compensatePurchaseOrder(id: string, b: M.CompensationRequest) { return this.http.post<M.PurchaseOrderResponse>(`${V1}/purchase-orders/${id}/compensations`, b); }
  retailPurchase(b: M.RetailPurchaseRequest) { return this.http.post<M.PurchaseOrderResponse>(`${V1}/purchase-orders/retail`, b); }
  purchaseOrderByReceipt(receiptId: string) { return this.http.get<M.PurchaseOrderResponse>(`${V1}/purchase-orders/by-receipt/${receiptId}`); }
```

- [ ] **Step 6: Run the spec**

Run: `cd frontend && npx ng test --watch=false --include src/app/features/inventory/purchase-math.spec.ts`
Expected: PASS. `PoLineRequest` deliberately keeps its old shape until Task 10, so the rest of the
app still compiles: `npx ng build` succeeds.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/app/core frontend/src/app/features/inventory/purchase-math.ts frontend/src/app/features/inventory/purchase-math.spec.ts
git commit -m "[Add] Pack-aware purchase maths and receipt models"
```

---

### Task 9: Shared item form with packs, and the Items screen

**Files:**
- Create: `frontend/src/app/features/inventory/item-form.ts`
- Test: `frontend/src/app/features/inventory/item-form.spec.ts`
- Modify: `frontend/src/app/features/inventory/items.ts`

**Interfaces:**
- Consumes: `packSentence` from `purchase-math.ts`, `unitsFor` from `core/units`, and
  `InventoryService.createItem` / `updateItem`.
- Produces: `<app-item-form [suppliers] [ingredients] [item] [supplierId] (saved) (closed)>`, where
  `saved` emits the saved `ItemResponse`. Exports `PACK_SUGGESTIONS`.

- [ ] **Step 1: Write the failing spec**

```ts
import { stubLocalStorage } from '../../core/test-storage';
import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ItemFormComponent } from './item-form';

const ingredients = [{ id: 'straw', name: 'Ống hút giấy', baseUnit: 'pc' }, { id: 'flour', name: 'Bột mì', baseUnit: 'g' }];

describe('ItemFormComponent', () => {
  let ctrl: HttpTestingController;
  const create = () => {
    const f = TestBed.createComponent(ItemFormComponent);
    f.componentRef.setInput('suppliers', [{ id: 's1', name: 'Tiệm bánh' }]);
    f.componentRef.setInput('ingredients', ingredients);
    f.componentRef.setInput('supplierId', 's1');
    f.detectChanges();
    return f.componentInstance;
  };

  beforeEach(() => {
    stubLocalStorage();
    TestBed.configureTestingModule({ providers: [provideZonelessChangeDetection(), provideHttpClient(), provideHttpClientTesting()] });
    ctrl = TestBed.inject(HttpTestingController);
  });
  afterEach(() => ctrl.verify());

  it('offersOnlyTheIngredientsDimension_afterPickingIt', () => {
    const form = create();
    expect(form.availableUnits().length).toBe(7);
    form.onIngredientChange('straw');
    expect(form.availableUnits().map((u) => u.value)).toEqual(['pc']);
    expect(form.form.unit).toBe('pc');
  });

  it('states the pack in words, with the stock size when stock counts another unit', () => {
    const form = create();
    form.onIngredientChange('flour');
    form.onUnitChange('kg');
    form.form.packLabel = 'bao';
    form.form.packSize = '25';
    expect(form.packHint()).toBe('Ordered in bao · 1 bao = 25 kg = 25.000 g · stock counts g');
  });

  it('asks for the pack size again when the unit changes', () => {
    const form = create();
    form.onIngredientChange('flour');
    form.form.packLabel = 'bao';
    form.form.packSize = '25';
    form.onUnitChange('kg');
    expect(form.form.packSize).toBe('');
    expect(form.packNeedsSize).toBe(true);
  });

  it('refuses half a pack without calling the server, and sends both halves when complete', () => {
    const form = create();
    form.onIngredientChange('straw');
    form.form.name = 'Ống hút hộp 100';
    form.form.packLabel = 'hộp';
    form.save();
    ctrl.expectNone('/api/v1/items');

    let saved: unknown;
    form.saved.subscribe((i) => (saved = i));
    form.form.packSize = '100';
    form.save();
    const req = ctrl.expectOne('/api/v1/items');
    expect(req.request.body).toMatchObject({ unit: 'pc', packLabel: 'hộp', packSize: '100', supplierId: 's1' });
    req.flush({ id: 'i1' });
    expect(saved).toEqual({ id: 'i1' });
  });
});
```

- [ ] **Step 2: Run it and watch it fail**

Run: `cd frontend && npx ng test --watch=false --include src/app/features/inventory/item-form.spec.ts`
Expected: FAIL. `./item-form` cannot be resolved.

- [ ] **Step 3: Write `item-form.ts`**

```ts
import { Component, OnInit, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { InventoryService } from '../../core/api.services';
import { IngredientResponse, ItemResponse, SupplierResponse } from '../../core/models';
import { ToastService } from '../../core/toast.service';
import { afterSave } from '../../core/save-handler';
import { unitsFor } from '../../core/units';
import { ModalComponent } from '../../shared/modal';
import { packSentence } from './purchase-math';

export const PACK_SUGGESTIONS = ['hộp', 'thùng', 'bao', 'bịch', 'chai', 'lốc', 'gói'];

interface ItemDraft {
  id?: string; name: string; sku: string; supplierId: string; ingredientId: string; unit: string;
  packLabel: string; packSize: string; active: boolean; version?: number;
}

const blank = (): ItemDraft => ({ name: '', sku: '', supplierId: '', ingredientId: '', unit: '', packLabel: '', packSize: '', active: true });

/**
 * Item form: what a supplier sells, the unit it is bought in, and an optional pack, in one drawer.
 * The Items screen and the purchase forms' "+ New item" both open it.
 */
@Component({
  selector: 'app-item-form',
  standalone: true,
  imports: [FormsModule, ModalComponent],
  template: `
    <app-modal variant="drawer" [title]="form.id ? 'Edit item' : 'New item'" (close)="closed.emit()">
      <div class="two-col">
        <div class="field">
          <label for="item-supplier">Supplier <span class="req" aria-hidden="true">*</span></label>
          <select id="item-supplier" class="select" [(ngModel)]="form.supplierId" [disabled]="!!form.id || !!supplierId()" required>
            <option value="" disabled>Choose a supplier</option>
            @for (s of suppliers(); track s.id) { <option [value]="s.id">{{ s.name }}</option> }
          </select>
        </div>
        <div class="field">
          <label for="item-ingredient">Ingredient <span class="req" aria-hidden="true">*</span></label>
          <select id="item-ingredient" class="select" [ngModel]="form.ingredientId" (ngModelChange)="onIngredientChange($event)" [disabled]="!!form.id" required>
            <option value="" disabled>Choose an ingredient</option>
            @for (i of ingredients(); track i.id) { <option [value]="i.id">{{ i.name }} ({{ i.baseUnit }})</option> }
          </select>
        </div>
      </div>
      @if (form.id) { <div class="hint pin">Supplier and ingredient are fixed: they define what this item is. Create a second item to point elsewhere.</div> }

      <div class="field"><label for="item-name">Name <span class="req" aria-hidden="true">*</span></label><input id="item-name" class="input" [(ngModel)]="form.name" placeholder="e.g. Ống hút giấy hộp 100" required /></div>

      <div class="two-col">
        <div class="field">
          <label for="item-unit">Bought in <span class="req" aria-hidden="true">*</span></label>
          <select id="item-unit" class="select" [ngModel]="form.unit" (ngModelChange)="onUnitChange($event)" [disabled]="!form.ingredientId" required>
            <option value="" disabled>Choose a unit</option>
            @for (u of availableUnits(); track u.value) { <option [value]="u.value">{{ u.label }}</option> }
          </select>
          @if (conversionHint()) { <div class="hint">{{ conversionHint() }}</div> }
        </div>
        <div class="field"><label for="item-sku">Supplier SKU</label><input id="item-sku" class="input" [(ngModel)]="form.sku" placeholder="Optional" maxlength="64" /></div>
      </div>

      <fieldset class="pack">
        <legend>Pack <span class="soft">(optional)</span></legend>
        <div class="two-col">
          <div class="field">
            <label for="item-pack-label">Pack name</label>
            <input id="item-pack-label" class="input" list="pack-suggestions" [(ngModel)]="form.packLabel" maxlength="32" placeholder="e.g. hộp" />
            <datalist id="pack-suggestions">@for (s of suggestions; track s) { <option [value]="s"></option> }</datalist>
          </div>
          <div class="field">
            <label for="item-pack-size">Each {{ form.packLabel.trim() || 'pack' }} holds</label>
            <div class="with-unit">
              <input id="item-pack-size" class="input" type="number" min="0" step="any" [(ngModel)]="form.packSize" [class.needs]="packNeedsSize" (ngModelChange)="packNeedsSize = false" />
              <span class="unit">{{ form.unit }}</span>
            </div>
            @if (packNeedsSize) { <div class="hint warn">The unit changed: enter the pack size again in {{ form.unit }}.</div> }
          </div>
        </div>
        @if (packHint()) { <div class="hint">→ {{ packHint() }}</div> }
        @if (form.id) { <div class="hint soft">Applies to new orders. Existing orders keep their pack.</div> }
      </fieldset>

      <label class="checkbox"><input type="checkbox" [(ngModel)]="form.active" /> Active</label>
      <div footer>
        <button class="btn btn-outline" (click)="closed.emit()">Cancel</button>
        <button class="btn btn-primary" (click)="save()" [disabled]="saving()">@if (saving()) { <span class="spinner"></span> } Save</button>
      </div>
    </app-modal>
  `,
  styles: [`
    .hint.pin { margin: -.4rem 0 .8rem; font-size: 12px; color: var(--text-muted); }
    .pack { border: 1px solid var(--border); border-radius: var(--radius); padding: .6rem .9rem .3rem; margin: 0 0 1rem; }
    .pack legend { padding: 0 .3rem; font-size: 13px; font-weight: 600; }
    .with-unit { display: flex; align-items: center; gap: .4rem; }
    .unit { color: var(--text-muted); font-size: 12px; white-space: nowrap; }
    .needs { border-color: var(--danger); }
    .warn { color: var(--danger); }
  `],
})
export class ItemFormComponent implements OnInit {
  suppliers = input.required<SupplierResponse[]>();
  ingredients = input.required<IngredientResponse[]>();
  /** The item to edit, or null to create one. */
  item = input<ItemResponse | null>(null);
  /** On create, the supplier the new item belongs to; the purchase forms pass theirs, and it is kept. */
  supplierId = input<string>('');
  saved = output<ItemResponse>();
  closed = output<void>();

  private api = inject(InventoryService);
  private toast = inject(ToastService);
  readonly suggestions = PACK_SUGGESTIONS;
  saving = signal(false);
  packNeedsSize = false;
  form: ItemDraft = blank();

  ngOnInit() {
    const i = this.item();
    this.form = i
      ? { id: i.id, name: i.name, sku: i.sku ?? '', supplierId: i.supplierId, ingredientId: i.ingredientId, unit: i.unit,
          packLabel: i.packLabel ?? '', packSize: i.packSize != null ? String(i.packSize) : '', active: i.active, version: i.version }
      : { ...blank(), supplierId: this.supplierId() };
  }

  baseUnitOf(id: string | undefined) { return this.ingredients().find((i) => i.id === id)?.baseUnit; }

  /** Only units of the ingredient's own dimension: kg for grams, never litres. A plain method, because `form` is not a signal. */
  availableUnits() { return unitsFor(this.baseUnitOf(this.form.ingredientId)); }

  onIngredientChange(ingredientId: string) {
    this.form.ingredientId = ingredientId;
    // The ingredient's own unit is always valid, and one click from the unit most things are bought in.
    this.onUnitChange(this.baseUnitOf(ingredientId) ?? '');
  }

  /** A pack size is counted in the unit, so a new unit makes the old number wrong: ask for it again. */
  onUnitChange(unit: string) {
    if (unit !== this.form.unit && this.form.packSize) {
      this.form.packSize = '';
      this.packNeedsSize = true;
    }
    this.form.unit = unit;
  }

  /** States the conversion in words, so a wrong pick is visible before it is saved. */
  conversionHint() {
    const base = this.baseUnitOf(this.form.ingredientId);
    if (!base || !this.form.unit) return '';
    return this.form.unit === base ? `Bought and counted in ${base}, no conversion.` : `Ordered in ${this.form.unit}, counted in ${base}.`;
  }

  packHint() {
    const base = this.baseUnitOf(this.form.ingredientId);
    const label = this.form.packLabel.trim();
    if (!base || !this.form.unit || !label || !(Number(this.form.packSize) > 0)) return '';
    return `Ordered in ${label} · ${packSentence({ unit: this.form.unit, packLabel: label, packSize: this.form.packSize }, base)} · stock counts ${base}`;
  }

  save() {
    const f = this.form;
    if (!f.supplierId || !f.ingredientId || !f.name.trim() || !f.unit) {
      this.toast.error('Supplier, ingredient, name and unit are required');
      return;
    }
    const label = f.packLabel.trim();
    const size = String(f.packSize ?? '').trim();
    if (!!label !== !!size || (size && !(Number(size) > 0))) {
      this.toast.error('A pack needs both a name and a size above 0');
      return;
    }
    this.saving.set(true);
    const body = { name: f.name.trim(), sku: f.sku, supplierId: f.supplierId, ingredientId: f.ingredientId, unit: f.unit,
      packLabel: label || null, packSize: label ? size : null, active: f.active, version: f.version };
    const req = f.id ? this.api.updateItem(f.id, body) : this.api.createItem(body);
    req.subscribe(afterSave(this.saving, (item: ItemResponse) => { this.toast.success('Item saved'); this.saved.emit(item); }));
  }
}
```

- [ ] **Step 4: Make the Items screen use it, and show packs**

In `items.ts`:
- Imports: remove `computed`'s use for `availableUnits`, `ItemRequest` and `unitsFor`. Add
  `ItemFormComponent` from `./item-form` and `QtyPipe` from `../../core/util`. Remove the now unused `ModalComponent` import. Set `imports` to
  `[FormsModule, IconComponent, StatusBadgeComponent, ItemFormComponent, QtyPipe]`.
- Replace the whole `@if (editing()) { <app-modal …> … </app-modal> }` block with:

```html
    @if (editing(); as e) {
      <app-item-form [suppliers]="suppliers()" [ingredients]="ingredients()" [item]="e.item" [supplierId]="e.item ? '' : supplierFilter()"
                     (saved)="editing.set(null); load()" (closed)="editing.set(null)" />
    }
```

- Replace the "Bought in" cell `<td><span class="unit-chip">{{ i.unit }}</span></td>` with:

```html
                    <td>
                      @if (i.packLabel) { <span class="unit-chip">{{ i.packLabel }}</span> <span class="soft">({{ i.packSize | qty }} {{ i.unit }})</span> }
                      @else { <span class="unit-chip">{{ i.unit }}</span> }
                    </td>
```

- In the "Becomes" cell, change the first ghost chip to `{{ i.packLabel || i.unit }}`.
- In the class:
  - delete `saving`, `form`, `availableUnits`, `conversionHint`, `onIngredientChange` and `save`;
  - change `editing` to `editing = signal<{ item: ItemResponse | null } | null>(null);`;
  - `openNew()` becomes `openNew() { this.editing.set({ item: null }); }`;
  - `openEdit(i)` becomes `openEdit(i: ItemResponse) { if (this.canWrite) this.editing.set({ item: i }); }`.
- Remove `.hint.pin` from the styles; it moved into the item form.

- [ ] **Step 5: Run the spec and the build**

Run: `cd frontend && npx ng test --watch=false --include src/app/features/inventory/item-form.spec.ts`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/app/features/inventory/item-form.ts frontend/src/app/features/inventory/item-form.spec.ts frontend/src/app/features/inventory/items.ts
git commit -m "[Add] Shared item form with an optional pack, and fix its unit list"
```

---

### Task 10: Purchase line editor and the New PO form

**Files:**
- Create: `frontend/src/app/features/inventory/purchase-lines.ts`
- Test: `frontend/src/app/features/inventory/purchase-lines.spec.ts`
- Modify: `frontend/src/app/features/inventory/purchase-orders.ts`
- Modify: `frontend/src/app/features/inventory/purchase-orders.spec.ts`

**Interfaces:**
- Consumes: `orderingUnit`, `conversionLine` and `perOrdering` from `purchase-math.ts`, and
  `ItemFormComponent` from Task 9.
- Produces:
  - `PurchaseLine { itemId: string; quantity: string; unitCost: string }`, `newPurchaseLine()` and
    `NEW_ITEM`;
  - `purchaseLinesError(lines, items): string`;
  - `<app-purchase-lines [lines] [items] [ingredients] [disabled] [canCreateItem] (newItem)>`, where
    `newItem` emits the line index.

- [ ] **Step 1: Write the failing spec**

`purchase-lines.spec.ts`:

```ts
import { purchaseLinesError } from './purchase-lines';

const items = [
  { id: 'box', unit: 'pc', packLabel: 'hộp', packSize: '100' },
  { id: 'flour', unit: 'kg' },
] as never[];

describe('purchaseLinesError', () => {
  it('passes complete lines, and whole packs', () => {
    expect(purchaseLinesError([{ itemId: 'box', quantity: '5', unitCost: '45000' }, { itemId: 'flour', quantity: '2.5', unitCost: '19000' }], items)).toBe('');
  });

  it('names what is missing, and refuses half a pack', () => {
    expect(purchaseLinesError([{ itemId: '', quantity: '5', unitCost: '1' }], items)).toBe('Every line needs an item');
    expect(purchaseLinesError([{ itemId: 'box', quantity: '', unitCost: '1' }], items)).toBe('Every line needs a quantity above 0 and a cost');
    expect(purchaseLinesError([{ itemId: 'box', quantity: '2.5', unitCost: '1' }], items)).toBe('Packs are counted whole: 2.5 hộp');
  });
});
```

Update `purchase-orders.spec.ts`. Replace the test
`'sends an item line as itemId, and an ingredient line with its chosen unit'` with:

```ts
  it('sends item lines only, with the quantity in packs', () => {
    orders.allItems.set([{ id: 'i1', supplierId: 's1', ingredientId: 'g1', unit: 'pc', packLabel: 'hộp', packSize: '100', active: true } as never]);
    orders.openNew();
    orders.onSupplierChange('s1');
    orders.form.lines[0] = { itemId: 'i1', quantity: '5', unitCost: '45000' };

    orders.save();
    const req = ctrl.expectOne(url('/api/v1/purchase-orders'));
    expect(req.request.body.lines).toEqual([{ itemId: 'i1', orderedQty: '5', unitCost: '45000' }]);
    req.flush(po('p1', 'b1'));
    ctrl.expectOne('/api/v1/ingredients').flush([]);
    ctrl.expectOne('/api/v1/suppliers').flush([]);
    ctrl.expectOne('/api/v1/items').flush([]);
    ctrl.expectOne(url('/api/v1/purchase-orders')).flush(page([]));
  });

  it('does not send half a pack', () => {
    orders.allItems.set([{ id: 'i1', supplierId: 's1', ingredientId: 'g1', unit: 'pc', packLabel: 'hộp', packSize: '100', active: true } as never]);
    orders.openNew();
    orders.onSupplierChange('s1');
    orders.form.lines[0] = { itemId: 'i1', quantity: '2.5', unitCost: '45000' };
    orders.save();
    ctrl.expectNone(url('/api/v1/purchase-orders'));
  });
```

Also change the `po(...)` fixture to
`({ id, branchId, supplierId: 's1', kind: 'ORDER', status: 'DRAFT', lines: [], receipts: [] })`.

- [ ] **Step 2: Run them and watch them fail**

Run: `cd frontend && npx ng test --watch=false --include src/app/features/inventory/purchase-lines.spec.ts --include src/app/features/inventory/purchase-orders.spec.ts`
Expected: FAIL. `./purchase-lines` cannot be resolved, and `form.lines[0]` has the old shape.

- [ ] **Step 3: Write `purchase-lines.ts`**

```ts
import { Component, input, output } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { IngredientResponse, ItemResponse } from '../../core/models';
import { MoneyPipe } from '../../core/util';
import { IconComponent } from '../../shared/icon';
import { conversionLine, orderingUnit } from './purchase-math';

/** A line as edited: a supplier item, the quantity in its ordering unit, and the price per that unit. */
export interface PurchaseLine { itemId: string; quantity: string; unitCost: string; }
export const newPurchaseLine = (): PurchaseLine => ({ itemId: '', quantity: '', unitCost: '' });
/** The picker's "+ New item" value; never sent to the server. */
export const NEW_ITEM = '__new__';

/** Why the lines cannot be saved yet, or '' when they can. */
export function purchaseLinesError(lines: PurchaseLine[], items: ItemResponse[]): string {
  if (lines.some((l) => !l.itemId)) return 'Every line needs an item';
  if (lines.some((l) => !(Number(l.quantity) > 0) || l.unitCost === '' || Number(l.unitCost) < 0)) return 'Every line needs a quantity above 0 and a cost';
  for (const l of lines) {
    const item = items.find((i) => i.id === l.itemId);
    if (item?.packLabel && !Number.isInteger(Number(l.quantity))) return `Packs are counted whole: ${l.quantity} ${item.packLabel}`;
  }
  return '';
}

/**
 * Purchase lines: pick the supplier's items, enter quantities in their packs, and see what each line puts
 * into stock. Shared by the New purchase order form and the Retail purchase page.
 */
@Component({
  selector: 'app-purchase-lines',
  standalone: true,
  imports: [FormsModule, MoneyPipe, IconComponent],
  template: `
    <div class="line-editor">
      @if (!disabled() && !items().length) {
        <div class="empty-lines soft">This supplier has no items yet.
          @if (canCreateItem()) { <button type="button" class="btn btn-sm btn-ghost" (click)="newItem.emit(0)">+ New item</button> }
        </div>
      }
      @for (line of lines(); track $index; let idx = $index) {
        <div class="po-line">
          <select class="select" [ngModel]="line.itemId" (ngModelChange)="pick(idx, $event)" [disabled]="disabled()" aria-label="Item">
            <option value="">{{ disabled() ? 'Choose a supplier first' : 'Choose an item' }}</option>
            @for (it of items(); track it.id) { <option [value]="it.id">{{ it.name }}</option> }
            @if (canCreateItem() && !disabled()) { <option [value]="newItemValue">+ New item</option> }
          </select>
          <div class="with-unit">
            <input class="input" type="number" min="0" [step]="itemOf(line)?.packLabel ? 1 : 0.001" [(ngModel)]="line.quantity" placeholder="Qty" aria-label="Quantity" [disabled]="disabled()" />
            <span class="unit">{{ unitLabel(line) }}</span>
          </div>
          <div class="with-unit">
            <input class="input" type="number" min="0" step="1" [(ngModel)]="line.unitCost" placeholder="Price" aria-label="Price" [disabled]="disabled()" />
            @if (unitLabel(line); as u) { <span class="unit">/ {{ u }}</span> }
          </div>
          <button type="button" class="btn btn-ghost btn-icon" (click)="remove(idx)" aria-label="Remove line"><app-icon name="close" [size]="16" /></button>
          @if (conversion(line); as c) { <div class="conv soft">{{ c }}</div> }
        </div>
      }
      <div class="foot">
        <button type="button" class="btn btn-outline" (click)="add()" [disabled]="disabled()"><app-icon name="plus" [size]="15" /> Add line</button>
        <span class="total"><span class="soft">Total</span> <b class="num">{{ total() | money }}</b></span>
      </div>
    </div>
  `,
  styles: [`
    .line-editor { display: grid; gap: .65rem; }
    .po-line { display: grid; grid-template-columns: minmax(180px, 1fr) 140px 170px 40px; gap: .5rem; align-items: center; }
    .conv { grid-column: 1 / -1; font-size: 12px; margin-top: -.3rem; }
    .with-unit { display: flex; align-items: center; gap: .4rem; min-width: 0; }
    .with-unit .input { min-width: 0; }
    .unit { color: var(--text-muted); font-size: 12px; white-space: nowrap; }
    .foot { display: flex; justify-content: space-between; align-items: center; }
    .total { display: flex; gap: .6rem; align-items: baseline; }
    .empty-lines { display: flex; align-items: center; gap: .6rem; font-size: 13px; }
    @media (max-width: 980px) { .po-line { grid-template-columns: 1fr; } }
  `],
})
export class PurchaseLinesComponent {
  lines = input.required<PurchaseLine[]>();
  /** The chosen supplier's active items. */
  items = input.required<ItemResponse[]>();
  ingredients = input.required<IngredientResponse[]>();
  /** True until a supplier is chosen. */
  disabled = input(false);
  canCreateItem = input(false);
  /** Asks the parent to open the item form for this line; the parent sets the line's itemId once it is saved. */
  newItem = output<number>();
  readonly newItemValue = NEW_ITEM;

  itemOf(line: PurchaseLine) { return this.items().find((i) => i.id === line.itemId); }

  pick(idx: number, value: string) {
    if (value === NEW_ITEM) {
      this.lines()[idx].itemId = '';
      this.newItem.emit(idx);
      return;
    }
    this.lines()[idx].itemId = value;
  }

  unitLabel(line: PurchaseLine) { const it = this.itemOf(line); return it ? orderingUnit(it) : ''; }

  conversion(line: PurchaseLine) {
    const it = this.itemOf(line);
    if (!it) return '';
    const ing = this.ingredients().find((g) => g.id === it.ingredientId);
    return conversionLine(Number(line.quantity), Number(line.unitCost), it, ing?.baseUnit ?? '', ing?.name ?? '');
  }

  total() { return this.lines().reduce((s, l) => s + (Number(l.quantity) || 0) * (Number(l.unitCost) || 0), 0); }
  add() { this.lines().push(newPurchaseLine()); }
  remove(idx: number) { if (this.lines().length > 1) this.lines().splice(idx, 1); }
}
```

- [ ] **Step 4: Use it in the New PO form**

In `purchase-orders.ts`:
- In `core/models.ts`, replace `PoLineRequest` and its comment with:

```ts
// Every line is a supplier item; quantity and cost are in its ordering unit (its pack, or its unit).
export interface PoLineRequest { itemId: UUID; orderedQty: string; unitCost: string; }
```

- Replace the `LineForm`, `PoForm`, `newLine` and `newForm` declarations with:

```ts
interface PoForm { supplierId: string; note: string; lines: PurchaseLine[]; }
const newForm = (): PoForm => ({ supplierId: '', note: '', lines: [newPurchaseLine()] });
```

- Add these imports: `PurchaseLine`, `PurchaseLinesComponent`, `newPurchaseLine` and
  `purchaseLinesError` from `./purchase-lines`; `ItemFormComponent` from `./item-form`. Remove the
  `unitsFor` import. Add `PurchaseLinesComponent` and `ItemFormComponent` to `imports`.
- In the creating drawer, replace the supplier field and the whole `<div class="line-editor">…</div>`
  with:

```html
        <div class="field">
          <label for="po-supplier">Supplier <span class="req" aria-hidden="true">*</span></label>
          <div class="supplier-row">
            <select id="po-supplier" class="select" [ngModel]="form.supplierId" (ngModelChange)="onSupplierChange($event)" required>
              <option value="">Select supplier</option>
              @for (s of suppliers(); track s.id) { <option [value]="s.id">{{ s.name }}</option> }
            </select>
            @if (auth.can('inventory:write')) { <button type="button" class="btn btn-sm btn-ghost" (click)="addingSupplier.set(!addingSupplier())">+ New supplier</button> }
          </div>
          @if (addingSupplier()) {
            <div class="supplier-row">
              <input class="input" [(ngModel)]="newSupplierName" placeholder="Supplier name" aria-label="New supplier name" />
              <button type="button" class="btn btn-sm btn-primary" (click)="createSupplier()" [disabled]="!newSupplierName.trim()">Add</button>
            </div>
          }
        </div>
        <div class="field"><label for="po-note">Note</label><input id="po-note" class="input" [(ngModel)]="form.note" /></div>
        <app-purchase-lines [lines]="form.lines" [items]="supplierItems()" [ingredients]="ingredients()" [disabled]="!form.supplierId"
                            [canCreateItem]="auth.can('inventory:write')" (newItem)="itemFormFor.set($event)" />
```

- After the creating drawer's `@if` block, add:

```html
    @if (itemFormFor() !== null) {
      <app-item-form [suppliers]="suppliers()" [ingredients]="ingredients()" [supplierId]="form.supplierId"
                     (saved)="onItemCreated($event)" (closed)="itemFormFor.set(null)" />
    }
```

- Add `.supplier-row { display: flex; gap: .5rem; align-items: center; margin-top: .4rem; }` to the
  styles. Remove `.source` and `.po-line` (not `.po-line.receive`, which Task 11 replaces), plus
  `.line-editor`, `.with-unit` and `.unit` if Task 11's drawer no longer needs them. Keep `.unit` and
  `.with-unit` for now; Task 11 removes the receive drawer markup.
- In the class:
  - delete `pickSource`, `editorUnit`, `unitChoices` and `unitOf`;
  - remove `addLine` and `removeLine`; the line editor does that now;
  - add:

```ts
  itemFormFor = signal<number | null>(null);
  addingSupplier = signal(false);
  newSupplierName = '';

  onItemCreated(item: ItemResponse) {
    this.allItems.update((all) => [...all, item]);
    const idx = this.itemFormFor();
    if (idx !== null && this.form.lines[idx]) this.form.lines[idx].itemId = item.id;
    this.itemFormFor.set(null);
  }

  createSupplier() {
    const name = this.newSupplierName.trim();
    if (!name) return;
    this.api.createSupplier({ name }).subscribe({ next: (s) => {
      this.suppliers.update((all) => [...all, s]);
      this.newSupplierName = '';
      this.addingSupplier.set(false);
      this.onSupplierChange(s.id);
    } });
  }
```

  - change `onSupplierChange` so it resets item lines with the new shape:

```ts
  onSupplierChange(id: string) {
    this.form.supplierId = id;
    this.formSupplier.set(id);
    // an item belongs to one supplier, so lines picked from the previous one no longer apply
    this.form.lines = this.form.lines.map(() => newPurchaseLine());
  }
```

  - replace `save()` with:

```ts
  save() {
    const problem = !this.form.supplierId ? 'Choose a supplier' : purchaseLinesError(this.form.lines, this.supplierItems());
    if (problem) { this.toast.error(problem); return; }
    this.saving.set(true);
    const lines: PoLineRequest[] = this.form.lines.map((l) => ({ itemId: l.itemId, orderedQty: l.quantity, unitCost: l.unitCost }));
    this.api.createPurchaseOrder({ supplierId: this.form.supplierId, note: this.form.note, lines })
      .subscribe(afterSave(this.saving, (po) => { this.creating.set(false); this.toast.success('Purchase order created'); this.load(); this.selected.set(po); }));
  }
```

- [ ] **Step 5: Run the specs**

Run: `cd frontend && npx ng test --watch=false --include src/app/features/inventory/purchase-lines.spec.ts --include src/app/features/inventory/purchase-orders.spec.ts`
Expected: PASS. The receive drawer still compiles against the old fields until Task 11. If the
compiler stops on `lineIngredientName` or `receiveLines`, leave those members in place; Task 11
replaces them.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/app/features/inventory/purchase-lines.ts frontend/src/app/features/inventory/purchase-lines.spec.ts \
        frontend/src/app/features/inventory/purchase-orders.ts frontend/src/app/features/inventory/purchase-orders.spec.ts
git commit -m "[Change] Purchase order lines are supplier items in packs"
```

---

### Task 11: Receive drawer, receipt history and replacements

**Files:**
- Create: `frontend/src/app/features/inventory/receive-drawer.ts`
- Test: `frontend/src/app/features/inventory/receive-drawer.spec.ts`
- Create: `frontend/src/app/features/inventory/compensation-dialog.ts`
- Modify: `frontend/src/app/features/inventory/purchase-orders.ts`

**Interfaces:**
- Consumes: `receiptSummary`, `orderingUnit`, `perOrdering`, `fmtQty` and `fmtRate` from
  `purchase-math.ts`; `convertQty` and `convertRate` from `core/units`; and
  `InventoryService.receivePurchaseOrder` and `compensatePurchaseOrder`.
- Produces:
  - `<app-receive-drawer [po] [items] [ingredients] (received) (closed)>`;
  - `<app-compensation-dialog [po] [items] [ingredients] (done) (closed)>`.

  Both emit the updated `PurchaseOrderResponse`.

- [ ] **Step 1: Write the failing spec**

```ts
import { stubLocalStorage } from '../../core/test-storage';
import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ReceiveDrawerComponent } from './receive-drawer';

const po = {
  id: 'p1', branchId: 'b1', supplierId: 's1', kind: 'ORDER', status: 'SENT', receipts: [],
  lines: [{ id: 'l1', itemId: 'box', ingredientId: 'straw', unit: 'pc', packLabel: 'hộp', packSize: '100', orderedQty: '5', receivedQty: '0', unitCost: { amount: '45000', currency: 'VND' } }],
};

describe('ReceiveDrawerComponent', () => {
  let ctrl: HttpTestingController;
  let drawer: ReceiveDrawerComponent;

  beforeEach(() => {
    stubLocalStorage();
    TestBed.configureTestingModule({ providers: [provideZonelessChangeDetection(), provideHttpClient(), provideHttpClientTesting()] });
    ctrl = TestBed.inject(HttpTestingController);
    const f = TestBed.createComponent(ReceiveDrawerComponent);
    f.componentRef.setInput('po', po);
    f.componentRef.setInput('items', [{ id: 'box', name: 'Ống hút hộp 100' }]);
    f.componentRef.setInput('ingredients', [{ id: 'straw', name: 'Ống hút giấy', baseUnit: 'pc' }]);
    f.detectChanges();
    drawer = f.componentInstance;
  });
  afterEach(() => ctrl.verify());

  it('defaults delivered to what is outstanding, and states stock and loss', () => {
    const row = drawer.rows[0];
    expect(row.delivered).toBe('5');
    row.damaged = '40';
    expect(drawer.intoStockText(row)).toBe('Into stock: 460 pc · 450 ₫/pc');
    expect(drawer.lossText(row)).toBe('Damaged on arrival: 40 × 450 = 18.000 ₫');
  });

  it('requires a choice for an extra, then sends it with the damage', () => {
    const row = drawer.rows[0];
    row.delivered = '6';
    drawer.confirm();
    ctrl.expectNone('/api/v1/purchase-orders/p1/receive');

    row.overKind = 'FREE';
    row.damaged = '40';
    drawer.confirm();
    const req = ctrl.expectOne('/api/v1/purchase-orders/p1/receive');
    expect(req.request.body.receipts).toEqual([
      { lineId: 'l1', receivedQty: '6', damagedQty: '40', unitCostOverride: null, overDeliveryKind: 'FREE', note: null },
    ]);
    req.flush(po);
  });
});
```

- [ ] **Step 2: Run it and watch it fail**

Run: `cd frontend && npx ng test --watch=false --include src/app/features/inventory/receive-drawer.spec.ts`
Expected: FAIL. `./receive-drawer` cannot be resolved.

- [ ] **Step 3: Write `receive-drawer.ts`**

```ts
import { Component, OnInit, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { InventoryService } from '../../core/api.services';
import { IngredientResponse, ItemResponse, PoLineResponse, PurchaseOrderResponse } from '../../core/models';
import { ToastService } from '../../core/toast.service';
import { afterSave } from '../../core/save-handler';
import { convertQty, convertRate } from '../../core/units';
import { formatMoney, ShortIdPipe } from '../../core/util';
import { ModalComponent } from '../../shared/modal';
import { OverKind, fmtQty, fmtRate, orderingUnit, perOrdering, receiptSummary } from './purchase-math';

interface ReceiveRow { lineId: string; delivered: string; damaged: string; actualPrice: string; overKind: OverKind | null; note: string; }

/**
 * Receive drawer: count what arrived in packs, note the damaged units, decide on any extra, and see what
 * enters stock and what is lost before confirming. One confirmation makes one goods receipt.
 */
@Component({
  selector: 'app-receive-drawer',
  standalone: true,
  imports: [FormsModule, ModalComponent, ShortIdPipe],
  template: `
    <app-modal variant="drawer" [width]="760" [title]="'Receive ' + (po().id | shortId)" (close)="closed.emit()">
      <div class="rows">
        @for (r of rows; track r.lineId) {
          @if (line(r); as l) {
            <div class="row">
              <div class="row-head"><b>{{ name(l) }}</b><span class="soft">Outstanding: {{ outstanding(l) }} {{ ordering(l) }}</span></div>
              <div class="grid">
                <label class="with-unit">Delivered <input class="input" type="number" min="0" [step]="l.packLabel ? 1 : 0.001" [(ngModel)]="r.delivered" /><span class="unit">{{ ordering(l) }}</span></label>
                <label class="with-unit">Damaged <input class="input" type="number" min="0" step="any" [(ngModel)]="r.damaged" /><span class="unit">{{ l.unit }}</span></label>
                <label class="with-unit">Actual price <input class="input" type="number" min="0" step="1" [(ngModel)]="r.actualPrice" placeholder="Order price" /><span class="unit">/ {{ ordering(l) }}</span></label>
                <label class="with-unit">Note <input class="input" [(ngModel)]="r.note" maxlength="255" /></label>
              </div>
              @if (summary(r).over > 0) {
                <div class="extra" role="radiogroup" [attr.aria-label]="'Extra ' + ordering(l)">
                  <span>{{ fmt(summary(r).over) }} {{ ordering(l) }} extra:</span>
                  <label><input type="radio" [name]="'over-' + r.lineId" [checked]="r.overKind === 'CHARGED'" (change)="r.overKind = 'CHARGED'" /> Charged</label>
                  <label><input type="radio" [name]="'over-' + r.lineId" [checked]="r.overKind === 'FREE'" (change)="r.overKind = 'FREE'" /> Free gift (0 ₫)</label>
                </div>
              }
              @if (summary(r).error) { <div class="err">{{ summary(r).error }}</div> }
              @else {
                <div class="soft">→ {{ intoStockText(r) }}</div>
                @if (lossText(r); as loss) { <div class="loss">→ {{ loss }}</div> }
              }
            </div>
          }
        }
        <div class="field"><label for="receipt-note">Receipt note</label><input id="receipt-note" class="input" [(ngModel)]="note" maxlength="255" /></div>
      </div>
      <div footer>
        <button class="btn btn-outline" (click)="closed.emit()">Cancel</button>
        <button class="btn btn-primary" (click)="confirm()" [disabled]="saving()">@if (saving()) { <span class="spinner"></span> } Confirm receipt</button>
      </div>
    </app-modal>
  `,
  styles: [`
    .rows { display: grid; gap: 1rem; }
    .row { display: grid; gap: .45rem; padding-bottom: .8rem; border-bottom: 1px solid var(--border); }
    .row-head { display: flex; justify-content: space-between; gap: 1rem; }
    .grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: .5rem; }
    .with-unit { display: flex; align-items: center; gap: .4rem; font-size: 12.5px; }
    .with-unit .input { min-width: 0; }
    .unit { color: var(--text-muted); font-size: 12px; white-space: nowrap; }
    .extra { display: flex; gap: .9rem; align-items: center; font-size: 13px; }
    .err { color: var(--danger); font-size: 12.5px; }
    .loss { color: var(--warning, var(--danger)); font-size: 12.5px; }
    @media (max-width: 760px) { .grid { grid-template-columns: 1fr; } }
  `],
})
export class ReceiveDrawerComponent implements OnInit {
  po = input.required<PurchaseOrderResponse>();
  items = input.required<ItemResponse[]>();
  ingredients = input.required<IngredientResponse[]>();
  received = output<PurchaseOrderResponse>();
  closed = output<void>();

  private api = inject(InventoryService);
  private toast = inject(ToastService);
  saving = signal(false);
  rows: ReceiveRow[] = [];
  note = '';
  readonly fmt = fmtQty;

  ngOnInit() {
    this.rows = this.po().lines.map((l) => ({ lineId: l.id, delivered: String(Math.max(0, Number(l.orderedQty) - Number(l.receivedQty))), damaged: '0', actualPrice: '', overKind: null, note: '' }));
  }

  line(r: ReceiveRow) { return this.po().lines.find((l) => l.id === r.lineId); }
  ordering(l: PoLineResponse) { return orderingUnit(l); }
  outstanding(l: PoLineResponse) { return fmtQty(Math.max(0, Number(l.orderedQty) - Number(l.receivedQty))); }
  name(l: PoLineResponse) {
    return this.items().find((i) => i.id === l.itemId)?.name ?? this.ingredients().find((g) => g.id === l.ingredientId)?.name ?? l.ingredientId.slice(0, 8);
  }
  private baseOf(l: PoLineResponse) { return this.ingredients().find((g) => g.id === l.ingredientId)?.baseUnit ?? l.unit; }

  summary(r: ReceiveRow) {
    const l = this.line(r)!;
    return receiptSummary({
      delivered: Number(r.delivered), outstanding: Number(l.orderedQty) - Number(l.receivedQty), damaged: Number(r.damaged) || 0,
      overKind: r.overKind, costPerOrdering: r.actualPrice !== '' ? Number(r.actualPrice) : Number(l.unitCost.amount), perOrdering: perOrdering(l),
    });
  }

  intoStockText(r: ReceiveRow) {
    const l = this.line(r)!;
    const s = this.summary(r);
    const base = this.baseOf(l);
    let text = `Into stock: ${fmtQty(convertQty(s.chargedIntoStock, l.unit, base))} ${base} · ${fmtRate(convertRate(s.costPerUnit, l.unit, base))} ₫/${base}`;
    if (s.freeIntoStock > 0) text += ` + ${fmtQty(convertQty(s.freeIntoStock, l.unit, base))} ${base} free`;
    return text;
  }

  lossText(r: ReceiveRow) {
    const s = this.summary(r);
    if (!(s.lossQty > 0)) return '';
    return `Damaged on arrival: ${fmtQty(s.lossQty)} × ${fmtRate(s.costPerUnit)} = ${formatMoney(s.lossAmount)}`;
  }

  confirm() {
    const active = this.rows.filter((r) => Number(r.delivered) > 0);
    if (!active.length) { this.toast.error('Enter what was delivered'); return; }
    const problem = active.map((r) => this.summary(r).error).find((e) => !!e);
    if (problem) { this.toast.error(problem); return; }
    this.saving.set(true);
    const receipts = active.map((r) => ({
      lineId: r.lineId, receivedQty: r.delivered, damagedQty: Number(r.damaged) > 0 ? r.damaged : null,
      unitCostOverride: r.actualPrice !== '' ? r.actualPrice : null,
      overDeliveryKind: this.summary(r).over > 0 ? r.overKind : null, note: r.note.trim() || null,
    }));
    this.api.receivePurchaseOrder(this.po().id, { note: this.note.trim() || null, receipts })
      .subscribe(afterSave(this.saving, (updated) => { this.toast.success('Receipt saved, stock updated'); this.received.emit(updated); }));
  }
}
```

`formatMoney(18000)` produces `18.000 ₫`, with a non-breaking space in the vi-VN currency format. If
the spec's string comparison fails on that space, change the expectation to
`toContain('40 × 450 = 18.000')`. Do not change the formatter.

- [ ] **Step 4: Write `compensation-dialog.ts`**

```ts
import { Component, OnInit, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { InventoryService } from '../../core/api.services';
import { GoodsReceiptLineResponse, IngredientResponse, ItemResponse, PurchaseOrderResponse } from '../../core/models';
import { ToastService } from '../../core/toast.service';
import { afterSave } from '../../core/save-handler';
import { ModalComponent } from '../../shared/modal';
import { fmtQty } from './purchase-math';

interface ReplacementRow { receiptLineId: string; name: string; unit: string; remaining: number; quantity: string; }

/** Receive replacement: bring in what a supplier sent for lost units, at those units' price, clearing the loss. */
@Component({
  selector: 'app-compensation-dialog',
  standalone: true,
  imports: [FormsModule, ModalComponent],
  template: `
    <app-modal title="Receive replacement" (close)="closed.emit()">
      @for (r of rows; track r.receiptLineId) {
        <div class="row">
          <span><b>{{ r.name }}</b> <span class="soft">· {{ fmt(r.remaining) }} {{ r.unit }} still lost</span></span>
          <label class="with-unit"><input class="input" type="number" min="0" [max]="r.remaining" step="any" [(ngModel)]="r.quantity" [attr.aria-label]="'Replacement for ' + r.name" /><span class="unit">{{ r.unit }}</span></label>
        </div>
      }
      <div class="field"><label for="comp-note">Note</label><input id="comp-note" class="input" [(ngModel)]="note" maxlength="255" /></div>
      <div footer>
        <button class="btn btn-outline" (click)="closed.emit()">Cancel</button>
        <button class="btn btn-primary" (click)="confirm()" [disabled]="saving()">Receive</button>
      </div>
    </app-modal>
  `,
  styles: [`
    .row { display: flex; justify-content: space-between; align-items: center; gap: 1rem; margin-bottom: .6rem; }
    .with-unit { display: flex; align-items: center; gap: .4rem; max-width: 160px; }
    .unit { color: var(--text-muted); font-size: 12px; }
  `],
})
export class CompensationDialogComponent implements OnInit {
  po = input.required<PurchaseOrderResponse>();
  items = input.required<ItemResponse[]>();
  ingredients = input.required<IngredientResponse[]>();
  done = output<PurchaseOrderResponse>();
  closed = output<void>();

  private api = inject(InventoryService);
  private toast = inject(ToastService);
  saving = signal(false);
  rows: ReplacementRow[] = [];
  note = '';
  readonly fmt = fmtQty;

  ngOnInit() {
    const lost: GoodsReceiptLineResponse[] = this.po().receipts.flatMap((r) => r.lines)
      .filter((l) => Number(l.lossQty) - Number(l.compensatedQty) > 0);
    this.rows = lost.map((l) => {
      const poLine = this.po().lines.find((pl) => pl.id === l.purchaseOrderLineId);
      const remaining = Number(l.lossQty) - Number(l.compensatedQty);
      const name = this.items().find((i) => i.id === poLine?.itemId)?.name
        ?? this.ingredients().find((g) => g.id === poLine?.ingredientId)?.name ?? '—';
      return { receiptLineId: l.id, name, unit: poLine?.unit ?? '', remaining, quantity: String(remaining) };
    });
  }

  confirm() {
    const chosen = this.rows.filter((r) => Number(r.quantity) > 0);
    if (!chosen.length) { this.toast.error('Enter what the supplier sent'); return; }
    const over = chosen.find((r) => Number(r.quantity) > r.remaining);
    if (over) { this.toast.error(`At most ${fmtQty(over.remaining)} ${over.unit} of ${over.name} is still lost`); return; }
    this.saving.set(true);
    this.api.compensatePurchaseOrder(this.po().id, { note: this.note.trim() || null, lines: chosen.map((r) => ({ receiptLineId: r.receiptLineId, quantity: r.quantity })) })
      .subscribe(afterSave(this.saving, (updated) => { this.toast.success('Replacement received'); this.done.emit(updated); }));
  }
}
```

- [ ] **Step 5: Wire both into the Purchase orders screen, and show packs and history**

In `purchase-orders.ts`:
- Add these imports: `ReceiveDrawerComponent` from `./receive-drawer`,
  `CompensationDialogComponent` from `./compensation-dialog`, `QtyPipe` and `formatMoney` from
  `../../core/util`, and `orderingUnit` and `conversionLine` from `./purchase-math`. Add
  `ReceiveDrawerComponent`, `CompensationDialogComponent` and `QtyPipe` to `imports`.
- Replace the whole `@if (receiving(); as po) { … }` block with:

```html
    @if (receiving(); as po) {
      <app-receive-drawer [po]="po" [items]="allItems()" [ingredients]="ingredients()"
                          (received)="afterChange($event); receiving.set(null)" (closed)="receiving.set(null)" />
    }
    @if (replacing(); as po) {
      <app-compensation-dialog [po]="po" [items]="allItems()" [ingredients]="ingredients()"
                               (done)="afterChange($event); replacing.set(null)" (closed)="replacing.set(null)" />
    }
```

- In the detail panel, replace the lines table body row with:

```html
                    <tr>
                      <td>{{ lineName(l) }}@if (lineConversion(l); as c) { <div class="soft mini">{{ c }}</div> }</td>
                      <td class="num">{{ l.orderedQty | qty }} <span class="unit">{{ ordering(l) }}</span></td>
                      <td class="num">{{ l.receivedQty | qty }} <span class="unit">{{ ordering(l) }}</span></td>
                      <td class="num">{{ l.unitCost | money }} <span class="unit">/ {{ ordering(l) }}</span></td>
                    </tr>
```

  Rename the first header from `Ingredient` to `Item`.
- After `<div class="card-pad po-total">…</div>`, add the receipt history:

```html
            @if (po.receipts.length) {
              <div class="history">
                <div class="history-head">
                  <b>Receipt history</b>
                  @if (auth.can('purchasing:write') && po.status !== 'CANCELLED' && hasLoss(po)) {
                    <button class="btn btn-sm btn-ghost" (click)="replacing.set(po)">Receive replacement</button>
                  }
                </div>
                @for (r of po.receipts; track r.id) {
                  <div class="receipt" [class.highlight]="highlightReceipt() === r.id">
                    <div class="soft mini">{{ r.receivedAt | date:'medium' }} · {{ r.receivedBy || '—' }} · {{ r.kind === 'COMPENSATION' ? 'Replacement' : 'Delivery' }}</div>
                    @for (rl of r.lines; track rl.id) { <div class="mini">{{ receiptLineText(po, rl) }}</div> }
                    @if (r.note) { <div class="soft mini">“{{ r.note }}”</div> }
                  </div>
                }
              </div>
            }
```

- Add `DatePipe` from `@angular/common` to `imports`.
- In the list, after the status badge cell content, add
  `@if (po.kind === 'RETAIL') { <span class="badge badge-gray">Retail</span> }` inside the same
  `<td>`.
- In the class:
  - delete `receiveLines`, `lineUnit`, `lineIngredientName`, `remaining`, `receive()`, and the body
    of `openReceive` that built `receiveLines`;
  - add:

```ts
  replacing = signal<PurchaseOrderResponse | null>(null);
  highlightReceipt = signal<string | null>(null);

  openReceive(po: PurchaseOrderResponse) { this.receiving.set(po); }

  afterChange(updated: PurchaseOrderResponse) { this.selected.set(updated); this.load(); }

  ordering(l: PoLineResponse) { return orderingUnit(l); }
  lineName(l: PoLineResponse) { return this.allItems().find((i) => i.id === l.itemId)?.name ?? this.ingredientName(l.ingredientId); }
  lineConversion(l: PoLineResponse) {
    const ing = this.ingredients().find((g) => g.id === l.ingredientId);
    return l.packLabel ? conversionLine(Number(l.orderedQty), Number(l.unitCost.amount), l, ing?.baseUnit ?? '', ing?.name ?? '') : '';
  }
  hasLoss(po: PurchaseOrderResponse) {
    return po.receipts.some((r) => r.lines.some((l) => Number(l.lossQty) - Number(l.compensatedQty) > 0));
  }
  receiptLineText(po: PurchaseOrderResponse, rl: GoodsReceiptLineResponse) {
    const l = po.lines.find((x) => x.id === rl.purchaseOrderLineId);
    if (!l) return '';
    if (Number(rl.replacementQty) > 0) return `${this.lineName(l)}: replaced ${rl.replacementQty} ${l.unit}`;
    let s = `${this.lineName(l)}: delivered ${rl.deliveredQty} ${orderingUnit(l)}`;
    if (Number(rl.overDeliveredQty) > 0) s += ` (${rl.overDeliveredQty} extra, ${rl.overDeliveryKind === 'FREE' ? 'free' : 'charged'})`;
    if (Number(rl.damagedQty) > 0) s += ` · damaged ${rl.damagedQty} ${l.unit}`;
    if (Number(rl.lossQty) > 0) s += ` · loss ${formatMoney(rl.lossAmount)}${Number(rl.compensatedQty) > 0 ? ` (replaced ${rl.compensatedQty})` : ''}`;
    return s;
  }
```

  - add `GoodsReceiptLineResponse` and `PoLineResponse` to the models import.
- Add these styles:

```css
    .history { border-top: 1px solid var(--border); padding: .7rem 1.35rem; display: grid; gap: .55rem; }
    .history-head { display: flex; justify-content: space-between; align-items: center; }
    .receipt { display: grid; gap: .15rem; padding: .35rem .5rem; border-radius: var(--radius); }
    .receipt.highlight { background: var(--accent-soft); }
    .mini { font-size: 12px; }
```

  Remove `.po-line.receive` and its media-query mention.

- [ ] **Step 6: Run the specs**

Run: `cd frontend && npx ng test --watch=false --include src/app/features/inventory/receive-drawer.spec.ts --include src/app/features/inventory/purchase-orders.spec.ts`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/app/features/inventory
git commit -m "[Add] Receive drawer with damage and extras, receipt history and replacements"
```

---

### Task 12: Retail purchase page

**Files:**
- Create: `frontend/src/app/features/inventory/retail-purchase.ts`
- Test: `frontend/src/app/features/inventory/retail-purchase.spec.ts`
- Modify: `frontend/src/app/app.routes.ts`, `frontend/src/app/layout/nav.ts`,
  `frontend/src/app/layout/command-palette.ts`,
  `frontend/src/app/features/inventory/purchase-orders.ts`

**Interfaces:**
- Consumes: `PurchaseLinesComponent`, `purchaseLinesError` and `newPurchaseLine` from Task 10;
  `ItemFormComponent` from Task 9; and `InventoryService.retailPurchase`.
- Produces: the route `/inventory/retail-purchase`, guarded by `permissionGuard('purchasing:retail')`.

- [ ] **Step 1: Write the failing spec**

```ts
import { stubLocalStorage } from '../../core/test-storage';
import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { AuthService } from '../../core/auth.service';
import { RetailPurchaseComponent } from './retail-purchase';

describe('RetailPurchaseComponent', () => {
  let ctrl: HttpTestingController;
  let page: RetailPurchaseComponent;
  const boot = (perms: string[]) => {
    stubLocalStorage();
    TestBed.configureTestingModule({ providers: [provideZonelessChangeDetection(), provideHttpClient(), provideHttpClientTesting()] });
    const auth = TestBed.inject(AuthService);
    auth.me.set({ userId: 'u', email: 'a@b.c', companyId: 'co', branchIds: ['b1'], roles: [], permissions: perms, permissionsByBranch: { b1: perms } });
    auth.setActiveBranch('b1');
    ctrl = TestBed.inject(HttpTestingController);
    page = TestBed.createComponent(RetailPurchaseComponent).componentInstance;
    ctrl.expectOne('/api/v1/suppliers').flush([{ id: 's1', name: 'Tạp hóa Cô Ba' }]);
    ctrl.expectOne('/api/v1/ingredients').flush([{ id: 'coffee', name: 'Cà phê', baseUnit: 'g' }]);
    ctrl.expectOne('/api/v1/items').flush([{ id: 'bag', supplierId: 's1', ingredientId: 'coffee', unit: 'g', packLabel: 'bịch', packSize: '500', active: true, name: 'Cà phê bịch 500g' }]);
  };
  afterEach(() => ctrl.verify());

  it('saves the purchase in one call and starts a fresh form', () => {
    boot(['purchasing:retail', 'inventory:read']);
    page.onSupplierChange('s1');
    page.form.lines[0] = { itemId: 'bag', quantity: '1', unitCost: '95000' };
    page.save();
    const req = ctrl.expectOne('/api/v1/purchase-orders/retail');
    expect(req.request.body).toEqual({ supplierId: 's1', note: null, lines: [{ itemId: 'bag', quantity: '1', unitCost: '95000' }] });
    req.flush({ id: 'p1' });
    expect(page.form.lines).toEqual([{ itemId: '', quantity: '', unitCost: '' }]);
  });

  it('hides the shortcuts that declare suppliers and items without inventory:write', () => {
    boot(['purchasing:retail', 'inventory:read']);
    expect(page.canDeclare()).toBe(false);
  });
});
```

- [ ] **Step 2: Run it and watch it fail**

Run: `cd frontend && npx ng test --watch=false --include src/app/features/inventory/retail-purchase.spec.ts`
Expected: FAIL. `./retail-purchase` cannot be resolved.

- [ ] **Step 3: Write `retail-purchase.ts`**

```ts
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { InventoryService } from '../../core/api.services';
import { AuthService } from '../../core/auth.service';
import { IngredientResponse, ItemResponse, SupplierResponse } from '../../core/models';
import { ToastService } from '../../core/toast.service';
import { afterSave } from '../../core/save-handler';
import { ItemFormComponent } from './item-form';
import { PurchaseLine, PurchaseLinesComponent, newPurchaseLine, purchaseLinesError } from './purchase-lines';

/**
 * Retail purchase: something bought and carried in, recorded in one save. It becomes a purchase order
 * already received, so reports, cost and price history count it like any other purchase.
 */
@Component({
  selector: 'app-retail-purchase',
  standalone: true,
  imports: [FormsModule, PurchaseLinesComponent, ItemFormComponent],
  template: `
    <div class="page">
      <div class="page-head">
        <div><h1>Retail purchase</h1><div class="sub">Bought and carried in: saved as received at once.</div></div>
      </div>
      <div class="card card-pad form">
        <div class="field">
          <label for="retail-supplier">Bought from <span class="req" aria-hidden="true">*</span></label>
          <div class="supplier-row">
            <select id="retail-supplier" class="select" [ngModel]="form.supplierId" (ngModelChange)="onSupplierChange($event)">
              <option value="">Select supplier</option>
              @for (s of suppliers(); track s.id) { <option [value]="s.id">{{ s.name }}</option> }
            </select>
            @if (canDeclare()) { <button type="button" class="btn btn-sm btn-ghost" (click)="addingSupplier.set(!addingSupplier())">+ New supplier</button> }
          </div>
          @if (addingSupplier()) {
            <div class="supplier-row">
              <input class="input" [(ngModel)]="newSupplierName" placeholder="Shop name" aria-label="New supplier name" />
              <button type="button" class="btn btn-sm btn-primary" (click)="createSupplier()" [disabled]="!newSupplierName.trim()">Add</button>
            </div>
          }
        </div>
        <app-purchase-lines [lines]="form.lines" [items]="supplierItems()" [ingredients]="ingredients()" [disabled]="!form.supplierId"
                            [canCreateItem]="canDeclare()" (newItem)="itemFormFor.set($event)" />
        <div class="field"><label for="retail-note">Note</label><input id="retail-note" class="input" [(ngModel)]="form.note" maxlength="255" placeholder="e.g. Ran out of milk at 9pm" /></div>
        <div class="actions"><button class="btn btn-primary" (click)="save()" [disabled]="saving()">@if (saving()) { <span class="spinner"></span> } Save &amp; receive</button></div>
      </div>
    </div>
    @if (itemFormFor() !== null) {
      <app-item-form [suppliers]="suppliers()" [ingredients]="ingredients()" [supplierId]="form.supplierId"
                     (saved)="onItemCreated($event)" (closed)="itemFormFor.set(null)" />
    }
  `,
  styles: [`
    .form { display: grid; gap: 1rem; max-width: 820px; }
    .supplier-row { display: flex; gap: .5rem; align-items: center; margin-top: .4rem; }
    .actions { display: flex; justify-content: flex-end; }
  `],
})
export class RetailPurchaseComponent {
  private api = inject(InventoryService);
  private auth = inject(AuthService);
  private toast = inject(ToastService);

  suppliers = signal<SupplierResponse[]>([]);
  ingredients = signal<IngredientResponse[]>([]);
  allItems = signal<ItemResponse[]>([]);
  saving = signal(false);
  itemFormFor = signal<number | null>(null);
  addingSupplier = signal(false);
  newSupplierName = '';
  form = { supplierId: '', note: '', lines: [newPurchaseLine()] as PurchaseLine[] };
  private chosenSupplier = signal('');
  supplierItems = computed(() => this.allItems().filter((i) => i.active && i.supplierId === this.chosenSupplier()));

  constructor() {
    this.api.listSuppliers().subscribe({ next: (s) => this.suppliers.set(s.filter((x) => x.active !== false)) });
    this.api.listIngredients().subscribe({ next: (i) => this.ingredients.set(i) });
    this.api.listItems().subscribe({ next: (i) => this.allItems.set(i) });
  }

  /** Only someone who may edit stock master data can declare a supplier or an item, and with it a pack. */
  canDeclare() { return this.auth.can('inventory:write'); }

  onSupplierChange(id: string) {
    this.form.supplierId = id;
    this.chosenSupplier.set(id);
    this.form.lines = [newPurchaseLine()];
  }

  onItemCreated(item: ItemResponse) {
    this.allItems.update((all) => [...all, item]);
    const idx = this.itemFormFor();
    if (idx !== null && this.form.lines[idx]) this.form.lines[idx].itemId = item.id;
    this.itemFormFor.set(null);
  }

  createSupplier() {
    const name = this.newSupplierName.trim();
    if (!name) return;
    this.api.createSupplier({ name }).subscribe({ next: (s) => {
      this.suppliers.update((all) => [...all, s]);
      this.newSupplierName = '';
      this.addingSupplier.set(false);
      this.onSupplierChange(s.id);
    } });
  }

  save() {
    const problem = !this.form.supplierId ? 'Choose where it was bought' : purchaseLinesError(this.form.lines, this.supplierItems());
    if (problem) { this.toast.error(problem); return; }
    this.saving.set(true);
    this.api.retailPurchase({
      supplierId: this.form.supplierId, note: this.form.note.trim() || null,
      lines: this.form.lines.map((l) => ({ itemId: l.itemId, quantity: l.quantity, unitCost: l.unitCost })),
    }).subscribe(afterSave(this.saving, () => {
      this.toast.success('Retail purchase saved, stock updated');
      this.form = { supplierId: this.form.supplierId, note: '', lines: [newPurchaseLine()] };
    }));
  }
}
```

- [ ] **Step 4: Add the route, the nav entry, the palette action and the button**

`app.routes.ts`, after the purchase-orders route:

```ts
      { path: 'inventory/retail-purchase', loadComponent: () => import('./features/inventory/retail-purchase').then((m) => m.RetailPurchaseComponent), canMatch: [permissionGuard('purchasing:retail')] },
```

`layout/nav.ts`, after the Purchase Orders entry:

```ts
    { label: 'Retail purchase', path: '/inventory/retail-purchase', perms: ['purchasing:retail'], icon: 'receipt' },
```

`layout/command-palette.ts`, after the `act:new-po` line:

```ts
    to('act:retail', 'Retail purchase', 'receipt', 'purchasing:retail', '/inventory/retail-purchase');
```

In `purchase-orders.ts`, in the page head, before the New purchase order button:

```html
        @if (auth.can('purchasing:retail')) { <button class="btn btn-outline" (click)="openRetail()"><app-icon name="receipt" [size]="16" /> Retail purchase</button> }
```

with `openRetail() { void this.router.navigateByUrl('/inventory/retail-purchase'); }` in the class.
`router` is already injected. Wrap the two buttons in `<div class="head-actions">` and add
`.head-actions { display: flex; gap: .5rem; }` to the styles.

- [ ] **Step 5: Run the specs**

Run: `cd frontend && npx ng test --watch=false --include src/app/features/inventory/retail-purchase.spec.ts --include src/app/layout`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/app
git commit -m "[Add] Retail purchase page under its own permission"
```

---

### Task 13: Stock movements link to their purchase

**Files:**
- Modify: `frontend/src/app/features/inventory/stock.ts`,
  `frontend/src/app/features/inventory/stock.spec.ts`
- Modify: `frontend/src/app/features/inventory/purchase-orders.ts`,
  `frontend/src/app/features/inventory/purchase-orders.spec.ts`

**Interfaces:**
- Consumes: `InventoryService.purchaseOrderByReceipt` and `getPurchaseOrder`, and the
  `GOODS_RECEIPT` / `PURCHASE_ORDER` ref types.
- Produces:
  - `StockComponent.purchaseLink(m): { receipt: string } | { po: string } | null` and
    `openPurchase(m)`;
  - `PurchaseOrdersComponent` inputs `receipt` and `po`, which come from query parameters.

- [ ] **Step 1: Write the failing specs**

In `stock.spec.ts`, inside the main `describe`, after the existing `pinIcon`/`typeClass` test, add:

```ts
  it('links a receipt movement to its receipt, and an older purchase movement to its order', () => {
    const base = { id: 'm', ingredientId: 'i', branchId: 'b', type: 'PURCHASE_RECEIPT', quantity: '1', occurredAt: '', actor: '' } as const;
    expect(stock.purchaseLink({ ...base, refType: 'GOODS_RECEIPT', refId: 'r1' })).toEqual({ receipt: 'r1' });
    expect(stock.purchaseLink({ ...base, refType: 'PURCHASE_ORDER', refId: 'p1' })).toEqual({ po: 'p1' });
    expect(stock.purchaseLink({ ...base, type: 'WASTE', refType: 'WASTE' })).toBeNull();
  });
```

In `purchase-orders.spec.ts`, in the `route intent` describe, add:

```ts
  it('selects the order a receipt link names, and highlights the receipt', () => {
    const { f, ctrl } = setup(['purchasing:read'], '');
    f.componentRef.setInput('receipt', 'r1');
    TestBed.tick();
    ctrl.expectOne('/api/v1/purchase-orders/by-receipt/r1').flush({ id: 'p9', branchId: 'b1', supplierId: 's1', kind: 'ORDER', status: 'RECEIVED', lines: [], receipts: [] });
    expect(f.componentInstance.selected()?.id).toBe('p9');
    expect(f.componentInstance.highlightReceipt()).toBe('r1');
    ctrl.match(() => true);
  });
```

- [ ] **Step 2: Run them and watch them fail**

Run: `cd frontend && npx ng test --watch=false --include src/app/features/inventory/stock.spec.ts --include src/app/features/inventory/purchase-orders.spec.ts`
Expected: FAIL. `purchaseLink` does not exist, and the component has no `receipt` input.

- [ ] **Step 3: Implement the link on the Stock screen**

In `stock.ts`, add to the class:

```ts
  /** Where a movement's purchase lives: its goods receipt, or (for movements before receipts existed) its order. */
  purchaseLink(m: MovementResponse): { receipt: string } | { po: string } | null {
    if (!m.refId) return null;
    if (m.refType === 'GOODS_RECEIPT') return { receipt: m.refId };
    if (m.refType === 'PURCHASE_ORDER') return { po: m.refId };
    return null;
  }

  openPurchase(m: MovementResponse) {
    const link = this.purchaseLink(m);
    if (link) void this.router.navigate(['/inventory/purchase-orders'], { queryParams: link });
  }
```

If the class does not already hold the router as `private router = inject(Router);`, add it; the
file imports `Router` already. In the movement template, inside `<div class="soft mv-sub">`, after
the cost span, add:

```html
                    @if (purchaseLink(m) && auth.can('purchasing:read')) { <button type="button" class="btn btn-sm btn-ghost mv-link" (click)="openPurchase(m)">View purchase</button> }
```

- [ ] **Step 4: Handle the deep links on the Purchase orders screen**

In `purchase-orders.ts`, add these inputs and an effect, next to the existing `new` input:

```ts
  /** `?receipt=<id>` from a stock movement: open the order it belongs to and highlight that receipt. */
  receipt = input<string>();
  /** `?po=<id>` from an older stock movement: open that order. */
  po = input<string>();
```

In the constructor, add:

```ts
    effect(() => {
      const receiptId = this.receipt();
      const poId = this.po();
      if ((!receiptId && !poId) || !this.auth.me()) return;
      untracked(() => {
        const found = receiptId ? this.api.purchaseOrderByReceipt(receiptId) : this.api.getPurchaseOrder(poId!);
        found.subscribe({ next: (order) => { this.selected.set(order); this.highlightReceipt.set(receiptId ?? null); } });
        void this.router.navigate([], { queryParams: { receipt: null, po: null }, queryParamsHandling: 'merge', replaceUrl: true });
      });
    });
```

- [ ] **Step 5: Run the specs and the whole frontend suite**

Run: `cd frontend && npm test`
Expected: PASS for every spec.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/app/features/inventory
git commit -m "[Add] Stock movements link to their goods receipt"
```

---

### Task 14: Docs, full verification, and a walk-through in the running app

**Files:**
- Modify: `CONTEXT.md`
- Modify: `docs/adr/0001-no-per-ingredient-unit-conversion-table.md`
- Modify: `docs/adr/0006-conversion-at-the-ledger-seam.md`

- [ ] **Step 1: Update the glossary**

In `CONTEXT.md`, replace the **Item** entry's body with:

```markdown
What one supplier sells: their name for it, the single unit they sell it in, the one ingredient it
becomes once received, and optionally one pack ("hộp" of 100 pc). Several items may become the same
ingredient: the same beans from two suppliers, or one supplier's box and loose pieces. An item's
purchase unit must share a dimension with its ingredient's base unit, which is what makes receiving
need no decision.
```

Replace the **Purchase order line** entry's first sentence with "One supplier item on a purchase
order, at an agreed quantity and price per its pack (or per its unit when it has none)." Add
"copy of the pack," to the list of what the line keeps.

After the **Purchase order line** entry, add:

```markdown
**Pack**:
A label over a quantity of an item's unit ("1 hộp = 100 pc"), declared once on the item by a person
and copied onto each order line. Packs are ordered and received whole. A pack is not a unit: stock never
counts in it.
_Avoid_: packaging unit, case unit

**Goods receipt**:
One delivery confirmed against a purchase order, or a supplier's replacement for lost units: who
received it, when, and per line what was delivered, damaged and lost, and how any extra was taken.
_Avoid_: GRN, delivery note

**Damaged on arrival**:
Units that arrived unusable. They count toward the order but never enter stock. The units that were
paid for are a loss at the price paid, recorded on the goods receipt against the supplier.
_Avoid_: shrinkage, waste (waste is stock spoiled after it was received)

**Compensation**:
A supplier's replacement for lost units, received at the lost units' price. It clears that much of the
loss.
_Avoid_: credit note, return

**Retail purchase**:
Something bought and carried in from a shop, recorded in one step as a purchase order already received.
_Avoid_: petty cash purchase, quick buy
```

- [ ] **Step 2: Add notes to the two ADRs**

Append to `docs/adr/0001-no-per-ingredient-unit-conversion-table.md`:

```markdown

Note (2026-10, purchase packs): packs now exist as this decision anticipated. An item may declare one
pack, a label over a quantity of its own unit, and purchase lines copy it. The unit vocabulary and the
pure conversion function are unchanged.
```

Append to `docs/adr/0006-conversion-at-the-ledger-seam.md`:

```markdown

Note (2026-10, purchase packs): a line with a pack is received in whole packs, converted to the line's
unit before the ledger. A line without one keeps the any-unit receipt above. Damaged units never reach
the ledger; only usable units are received.
```

- [ ] **Step 3: Run both full suites**

Run:

```bash
cd backend && ./gradlew test --rerun
cd ../frontend && npm test && npx ng lint
```

Expected:
- the backend reports BUILD SUCCESSFUL with no failures (about 600 tests);
- every frontend spec passes;
- lint shows 0 errors.

If `--rerun` reports a test count far below the previous 566 in a few seconds, Docker is not
running. Start it and run again.

- [ ] **Step 4: Walk through it in the running app**

Run: `docker compose up -d --build` from the repo root, then open `http://localhost:8095` and sign in
with the `ADMIN_EMAIL` and `ADMIN_PASSWORD` from `.env`. Check each of these:

1. **Items → New item**: Ingredient "Ống hút giấy (pc)". The unit list shows only `pc`. Set pack
   "hộp", 100. The hint reads "Ordered in hộp · 1 hộp = 100 pc · stock counts pc". Save.
2. **Purchase orders → New**: choose the supplier and pick the item. Quantity 5 at 45000 shows
   "= 500 pc … · 450 ₫/pc". Create, then Send.
3. **Receive**: Delivered 5, Damaged 40. The drawer shows "Into stock: 460 pc · 450 ₫/pc" and
   "Damaged on arrival: 40 × 450 = 18.000 ₫". Confirm. The status is Received, and the receipt
   history shows the loss.
4. **Receive replacement**: enter 40 and receive. The loss line shows "(replaced 40)".
5. **Stock**: the straws are at 500. "View purchase" on the receipt movement opens the order with
   the receipt highlighted.
6. **Retail purchase**: from the nav, buy 1 "Cà phê bịch 500g" at 95000 (create the item first in
   Items with pack "bịch", 500 g). Save. Stock shows +500 g at 190 ₫/g, and the PO list shows a
   "Retail" badge.

- [ ] **Step 5: Commit**

```bash
git add CONTEXT.md docs/adr/0001-no-per-ingredient-unit-conversion-table.md docs/adr/0006-conversion-at-the-ledger-seam.md
git commit -m "[Docs] Packs, goods receipts and retail purchases in the glossary and ADRs"
```
