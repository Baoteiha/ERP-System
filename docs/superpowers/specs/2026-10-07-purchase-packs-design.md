# Purchase Packs, Goods Receipts and Retail Purchases — Design

**Date:** 2026-10-07
**Branch:** `dev` (main and dev both at `1fc7beb` when this was written)
**Scope:** backend (inventory/purchasing, identity permissions) and the Items, Purchase orders and
Stock screens

## 1. Intent

The owner wants purchasing to match how suppliers actually sell: in boxes, sacks and bags, not in
loose units. They also want to see exactly what each delivery brought in and what was lost on
arrival. This is sub-project 1 of a three-part redesign:

| # | Sub-project | Status |
|---|---|---|
| 1 | **Packs, goods receipts and retail purchases** (this document) | Designed |
| 2 | Lots and expiry: each receipt becomes a lot, and sales take the lot that expires first | Later, with its own design |
| 3 | AI assistant in `ai-service/` (same repo): reads the box photo at receiving, searches for suppliers | Later; it prefills the forms built here |

The owner made these decisions:

| Question | Decision |
|---|---|
| Where a pack ("1 hộp = 100 pc") is declared | Once, on the item, by a person. It is never inferred from the item name |
| How many packs an item can have | At most one. It converts straight to a real unit (1 thùng = 12 l). Buying both boxes and singles means two items |
| How orders are placed | In whole packs, priced per pack |
| Ingredient lines on a PO ("Oat milk, 2 l" with no item) | No longer created. Every new PO line is a supplier item. Old lines still display and can be received |
| What is recorded at receiving | **Delivered**, in packs; this counts toward the PO. **Damaged**, in the item's unit; this is a loss |
| A damaged box | The PO line is still fully received (5/5 boxes). The usable units go into stock at the agreed price. The damaged units never enter stock and are recorded as a loss at the price paid, against the supplier |
| Over-delivery (5 ordered, 6 arrived) | Allowed. The receiver must choose **charged** (the PO line grows, same price) or **free gift** (0 đ) |
| A supplier replacing damaged units | "Nhận hàng bù": the units come in at the price of the damaged ones and the loss is cleared |
| Buying one bag of coffee at a shop | A one-step **retail purchase** ("Mua lẻ") form that creates a PO which is already received |
| Who may make retail purchases | A new permission, `purchasing:retail`. MANAGER has it by default, and the owner can add it to CASHIER |
| Expiry dates | Not in this sub-project. The goods receipt introduced here is where sub-project 2 will add them |

Success means:
- A purchase in packs puts the right quantity of the right ingredient into stock, at a cost per base
  unit equal to the agreed pack price divided by its contents.
- The damaged-on-arrival loss equals damaged units × the price paid for them, to the đồng, whatever
  stock bought at other prices is already on hand.
- Every delivery leaves a goods receipt that says who received what, when, how much was damaged and
  why any extra was accepted.
- The stock ledger's code and its existing tests, including the stock guard's, are unchanged and
  still pass.

## 2. Data

All of it ships in one migration, `V29_001__purchase_packs_and_goods_receipts.sql`. Existing rows
keep their meaning.

### The rule for PO lines

A PO line's `ordered_qty`, `received_qty` and `unit_cost` are in its **ordering unit**. That is the
pack when the line has one, and otherwise the line's `unit`. A PO is a record of what was agreed
("5 hộp × 45.000 đ"). Conversion to the stock unit happens only at receiving. Existing lines have no
pack, so their ordering unit is their `unit`, exactly as today.

### Changes

| Table | Change |
|---|---|
| `item` | `pack_label VARCHAR(32) NULL` and `pack_size NUMERIC(19,4) NULL`. A check requires both to be null, or both set with `pack_size > 0`. `pack_size` is counted in the item's `unit`, so "hộp", 100 on an item bought in `pc` means a box of 100 pieces |
| `purchase_order_line` | `pack_label` and `pack_size`, **copied from the item** when the PO is created. Editing the item later does not change existing lines |
| `purchase_order` | `kind VARCHAR(16) NOT NULL DEFAULT 'ORDER'`, constrained to `ORDER` or `RETAIL` |
| `goods_receipt` (new) | One row per delivery confirmed: `id`, `version`, `branch_id`, `purchase_order_id`, `kind` (`DELIVERY` or `COMPENSATION`), `note`, and the audit columns. Who received the goods and when are `created_by` and `created_at` |
| `goods_receipt_line` (new) | `id`, `version`, `goods_receipt_id`, `purchase_order_line_id`, `delivered_qty` (ordering unit), `damaged_qty` (line unit, default 0), `loss_qty` (the damaged units that were paid for, line unit), `over_delivered_qty` (ordering unit, default 0), `over_delivery_kind` (`CHARGED`, `FREE` or null), `unit_cost` + `currency` (actual price per ordering unit), `cost_per_unit` (per line unit, which is what the ledger and the loss use), `compensated_qty` (line unit, default 0, never above `loss_qty`), `replacement_qty` (on a COMPENSATION line, the units it brings in), `compensates_line_id` (on a COMPENSATION line, the damaged line it replaces), `note` |
| `item` index | `uk_item_supplier_ingredient_unit` (one live item per supplier, ingredient and unit) is replaced by `uk_item_supplier_ingredient_unit_pack`, which also keys on the pack (label case-insensitive, size). "Ống hút hộp 100" and loose "Ống hút" from one supplier, both in `pc`, are two ways of buying, as the owner decided. The same box twice is still a duplicate |
| `stock_movement` | **No schema change.** Receipts made under this design write `ref_type = 'GOODS_RECEIPT'` and `ref_id` = the receipt's id. Older movements keep `PURCHASE_ORDER` |

Both new tables get row-level security, following the `purchase_order` / `purchase_order_line`
pattern. `goods_receipt` resolves to a company through its branch, and `goods_receipt_line` through
its receipt. Both are added to `RlsEverywhereTest`'s `COMPANY_OF` map.

## 3. Item screen

The New/Edit item form gains an optional **Pack** block below "Bought in":

```
Bought in *           Supplier SKU
[pc — piece      ▾]   [Optional           ]

Pack (optional)
Pack name             Each hộp holds
[hộp            ]     [100      ] pc

→ Ordered in hộp · 1 hộp = 100 pc · stock counts pc
```

- **Pack name** is free text, because a pack is a label and not a unit. It offers suggestions: hộp,
  thùng, bao, bịch, chai, lốc, gói.
- The two fields are **both filled or both empty**, and the size must be above 0. Empty means the
  item works as today.
- **The hint updates as the user types.** For an item bought in kg whose ingredient is counted in g,
  it says "1 bao = 25 kg = 25,000 g · stock counts g".
- **Unit dropdown fix:** "Bought in" lists only the units of the ingredient's own dimension.
  `availableUnits` becomes a plain method. Today it is a `computed()` that reads the plain `form`
  object, so it never updates and lists all seven units. The other unit pickers in the app already
  use plain methods.
- **Changing "Bought in" while a pack is set** flags the pack size as needing re-entry, because the
  number was counted in the old unit.
- **Editing a pack on an item already in use** is allowed. The form says "Applies to new orders.
  Existing orders keep their pack."
- The items list shows the pack in the "Bought in" column, as "**hộp** (100 pc) → pc".
- The form becomes a **shared component**, so the PO and retail forms open the same form from their
  "+ New item" entry.

The backend (`ItemService` and the item DTOs) enforces the same rules: both or neither, size > 0,
and a label of at most 32 characters.

## 4. Ordering

The New purchase order form:

```
Supplier *
[Tiệm bánh Hương Quê                    ▾]   + New supplier

[Ống hút giấy hộp 100          ▾]  [ 5 ] hộp   [45,000] / hộp   ✕
  = 500 pc Ống hút giấy · 450 đ/pc
[Bột mì bao 25 kg              ▾]  [ 2 ] bao   [480,000] / bao  ✕
  = 50 kg = 50,000 g Bột mì · 19.2 đ/g

[+ Add line]                              Total: 1,185,000 đ
```

- The line picker lists **only the chosen supplier's items**. The "Ingredients" group is removed.
  The list ends with **"+ New item"**, which opens the shared item form with the supplier filled in
  and selects the new item on that line.
- Lines are disabled until a supplier is chosen. A supplier with no items shows "This supplier has
  no items yet · + New item".
- **"+ New supplier"** quick-creates a supplier from a name only, through the existing suppliers
  endpoint.
- Changing the supplier clears item lines, as today.
- A pack line takes a **whole number of packs** and its price is per pack. A line without a pack
  takes any quantity in the item's unit, as today.
- Under each line, a **conversion line** shows what the line will put into stock: ingredient,
  quantity in base units and cost per base unit. The form shows a total.
- The lifecycle is unchanged: Draft → Sent → Received. PO details show each line as "5 hộp × 45,000
  = 225,000 đ · = 500 pc".

Backend:
- A line without `itemId` is refused with "A purchase line must be a supplier item".
  `PoLineRequest` keeps `ingredientId` and `unit` only so that it can refuse them clearly, as it
  does today.
- The item must belong to the PO's supplier and be active. Both checks exist already.
- A pack line's `orderedQty` must be a whole number above 0.
- `pack_label` and `pack_size` are copied onto the line at creation. POs cannot be edited after
  creation, so the copy is final.

## 5. Receiving

### Screen

```
Receive · PO #A1B2 · Tiệm bánh Hương Quê

Ống hút giấy hộp 100            Outstanding: 5 hộp
  Delivered [ 5 ] hộp           Damaged [ 40 ] pc   Note [one box torn]
  → Into stock: 460 pc · 450 đ/pc
  → Damaged on arrival: 40 × 450 = 18,000 đ

Bột mì bao 25 kg                Outstanding: 2 bao
  Delivered [ 2 ] bao           Damaged [ 0  ] kg
  → Into stock: 50,000 g · 19.2 đ/g
                                        [Cancel]  [Confirm receipt]
```

- **Delivered** defaults to the outstanding quantity, so a complete delivery is one click. On a pack
  line it is a whole number of packs.
- **Damaged** is in the item's unit and cannot exceed what was delivered.
- **Actual price** (today's override cost) is kept, per pack. It is used when the invoice differs
  from the order.
- When **Delivered exceeds Outstanding**, a required choice appears: "1 hộp extra: charged / free
  gift (0 đ)".
- Lines without a pack keep ADR-0006's option to count the delivery in any unit of the same
  dimension, for both delivered and damaged. That quantity is converted to the line's unit.
- Each confirmation creates one goods receipt. PO details gain a **Receipt history** listing each
  receipt's date, receiver, delivered, damaged, over-delivery and compensation.

| What happened | Entered | Result |
|---|---|---|
| Short: 5 ordered, 4 arrived | Delivered 4 | The line stays outstanding by 1. A later delivery is a new receipt |
| Damaged: 5 arrived, 40 pieces torn | Delivered 5, Damaged 40 | The line is fully received. Stock +460, loss 18,000 đ |
| Extra: 5 ordered, 6 arrived | Delivered 6 + reason | Charged: the order becomes 6. Free: the order stays 5 and 100 pc enter at 0 đ |

### Money rules

For each receipt line on a pack line, with `P` = pack size and `c` = actual price per pack:

- Cost per line unit: `u = c / P` (450 = 45,000 / 100). Without a pack, `u = c`.
- Charged packs = delivered − free packs. Free packs = `over_delivered_qty` when the kind is
  `FREE`, otherwise 0.
- Damage is taken from the charged units first, so the loss is never understated.
  - `damagedCharged = min(damaged, chargedPacks × P)`
  - `damagedFree = damaged − damagedCharged`
- The ledger receives, through the existing `StockLedgerService.receive`, under
  `GOODS_RECEIPT` / receipt id. Each cost is turned into a base-unit rate with `toBaseRate`, as
  receiving does today (450 đ/pc stays 450; 19,200 đ/kg becomes 19.2 đ/g):
  - `chargedPacks × P − damagedCharged` at `u`;
  - `freePacks × P − damagedFree` at 0, when that is above 0.
- **Damaged units never enter the ledger.** The loss is `damagedCharged × u`, recorded on the receipt
  line.
- PO line:
  - `received_qty` grows by the delivered packs up to the outstanding quantity.
  - `CHARGED` over-delivery also grows `ordered_qty` and `received_qty` by the extra.
  - `FREE` over-delivery leaves both as they are; the extra lives only on the receipt line.
  - Status follows `refreshReceiptStatus()` as today.

**Why not "receive everything, then waste the damaged units":** `StockLedgerService.waste` values
waste at the current blended moving average. That is not the price paid for the damaged units.
Worked example, with 200 pc already on hand at 400 đ and 5 boxes received at 45,000 đ with 40
pieces damaged:

| | Receive 500, waste 40 | **This design: receive 460** |
|---|---|---|
| Average after receiving | 435.71 | **434.85** |
| Recorded loss | 17,429 đ (wrong) | **18,000 đ** |
| Stock value left | 287,571 đ | **287,000 đ** |

Rounding follows the ledger: costs are divided at `CALC_SCALE` (10) and stored at `Money`'s 4
decimal places, using HALF_EVEN.

### Compensation ("Nhận hàng bù")

A PO that has uncompensated damage shows **"Receive replacement"**. It creates a goods receipt of
kind `COMPENSATION`:

- Each of its lines names the damaged receipt line (`compensates_line_id`) and a quantity in the line
  unit. The quantity cannot exceed `damaged − compensated` on that line.
- Stock receives that quantity at the damaged line's `cost_per_unit`, and the original line's
  `compensated_qty` grows by it. The remaining loss is `(damaged − compensated) × cost_per_unit`.
- Continuing the example, compensating all 40 units leaves 700 pc worth 305,000 đ (average 435.71),
  exactly as if nothing had been damaged, and a loss of 0.
- It is allowed on PARTIALLY_RECEIVED and RECEIVED POs, but not on CANCELLED ones.

### API

- `POST /purchase-orders/{id}/receive` (`purchasing:write`). The body keeps today's field names so
  existing clients and tests carry on: `receipts[]`, each with `lineId`, `receivedQty` (meaning
  delivered), `unit?` (lines without a pack only) and `unitCostOverride?` (the actual price). It adds
  `damagedQty?`, `overDeliveryKind?`, a per-line `note?` and a top-level `note`. It refuses:
  - damaged above delivered;
  - a fractional number of packs;
  - over-delivery without a kind, or a kind without over-delivery;
  - a line not on this PO;
  - a PO not in SENT or PARTIALLY_RECEIVED.
- `POST /purchase-orders/{id}/compensations` (`purchasing:write`). Body: `note` and `lines[]`, each
  with `receiptLineId` and `quantity`. A replacement covers lost units only, meaning damaged units
  that were paid for, up to `loss_qty − compensated_qty`. Damaged free gifts cost nothing and are
  not replaced.
- `GET /purchase-orders/by-receipt/{receiptId}` (`purchasing:read`) returns the order a receipt
  belongs to, so a stock movement can link to it. It is scoped to the active branch.
- `PurchaseOrderResponse` gains `kind`, each line's pack, and `receipts[]` with their lines and loss
  amounts.

## 6. Retail purchase ("Mua lẻ")

Retail purchase is its own page, `/inventory/retail-purchase`, guarded by `purchasing:retail`. It
appears in the Inventory nav and the ⌘K palette, and a **"Retail purchase"** button beside "New
purchase order" opens it. It is a page rather than a drawer on the Purchase orders screen because
that screen needs `purchasing:read`, which a cashier with only the retail permission does not hold.

```
Retail purchase · Branch: Quận 1

Bought from *
[Tạp hóa Cô Ba                          ▾]   + New supplier

[Cà phê Trung Nguyên bịch 500g  ▾]  [ 1 ] bịch   [95,000] / bịch   ✕
  = 500 g Cà phê hạt Robusta · 190 đ/g
[Sữa tươi hộp 1L                ▾]  [ 2 ] hộp    [32,000] / hộp    ✕
  = 2 l = 2,000 ml Sữa tươi · 32 đ/ml

[+ Add line]                              Total paid: 159,000 đ
Note [Ran out of milk at 9pm             ]
                                      [Cancel]  [Save & receive]
```

- It reuses the PO form's line editor, including "+ New item". There are no damaged or
  over-delivery fields, because the goods were inspected before paying.
- **"Save & receive"** runs one transaction that creates:
  - a PO of kind `RETAIL` in status RECEIVED;
  - one `DELIVERY` goods receipt with delivered = ordered;
  - the ledger receipts.

  Any failure saves nothing.
- Retail POs carry a **"Retail"** badge in the PO list.
- **Known limit:** a saved retail purchase cannot be undone. This matches received POs, since the
  ledger never rewrites rows. Mistakes are corrected with a stock adjustment.

API: `POST /purchase-orders/retail` (`purchasing:retail`). It names exactly one permission, because
`ArchitectureTest` requires every endpoint to be `hasAuthority('<one permission>')`. OWNER and
MANAGER hold it anyway. Body: `supplierId`, `note` and `lines[]` of `{ itemId, quantity, unitCost }`.
It uses the active branch. The lines must be the supplier's items, so a supplier with no items
cannot be bought from until someone with `inventory:write` sets them up. For that reason the
"+ New supplier" and "+ New item" shortcuts appear only for users who hold `inventory:write`, and
they use the existing endpoints.

### Permission

- New permission `purchasing:retail` ("Record retail purchases"). Add it to `Permissions.CATALOG`,
  raise `CATALOG_VERSION` to 2 and set `SINCE` to 2, so the startup sync offers it once to existing
  MANAGER roles.
- MANAGER has it by default, and OWNER through `ALL`. CASHIER does not by default. The owner adds
  it on the Roles screen, which `RoleService.addPermissions` allows on default roles, and the sync
  never revokes it.
- A user with only `purchasing:retail` (plus the `inventory:read` that CASHIER already has, for the
  supplier and item lists) can use the retail page and pick existing suppliers and items.
  "+ New supplier" and "+ New item" are hidden without `inventory:write`, so staff cannot declare
  packs by accident. They cannot create ordinary POs.

## 7. Stock screen

On the Movements list, each receipt row links to its source:
- a `GOODS_RECEIPT` row opens its PO with that receipt highlighted;
- an older `PURCHASE_ORDER` row opens the PO.

The trail runs from the average cost, to the movement, to the receipt, to the order.

## 8. Testing

Write the tests first. Backend tests use the existing Testcontainers PostgreSQL setup, and frontend
tests use Vitest.

### Money and stock (the core)

| Case | Expected |
|---|---|
| 5 boxes delivered, none damaged | Stock +500 pc at 450 |
| 5 boxes delivered, 40 damaged, 200 pc on hand at 400 | 660 pc, average **434.85**, loss **exactly 18,000** |
| Short: 4 of 5 delivered | The line is outstanding by 1, PARTIALLY_RECEIVED |
| Charged over-delivery: 6 of 5 | Ordered 6, stock +600 at 450 |
| Free over-delivery: 6 of 5 | Stock +500 at 450 and +100 at 0, ordered stays 5 |
| Free + damaged | Damage is charged first, and the loss is at 450 |
| Compensation of 40 | Stock +40 at 450, loss 0, average **435.71** |
| Compensation above outstanding damage, damaged above delivered, over-delivery without a kind, 2.5 packs | Refused with a clear message |
| Actual price differs from the order | Stock at the actual price per pack ÷ size |
| A legacy ingredient line | Still receivable, as before |
| A line without a pack received in another unit of the same dimension | Converted to the line's unit (ADR-0006) |

### Rules

- Pack validation: both or neither, size > 0. Editing an item's pack leaves existing PO lines
  unchanged.
- PO create: an ingredient line is refused, another supplier's item is refused, and a fractional
  number of packs is refused.
- Retail: one save creates the PO, receipt and stock. A failure mid-save leaves no PO, receipt or
  movement.
- Permissions:
  - `purchasing:retail` alone can make a retail purchase but cannot create an ordinary PO or an item.
  - After the catalog bump, the startup sync gives existing MANAGER roles the new permission once.

### Isolation and regression

- `RlsEverywhereTest` covers `goods_receipt` and `goods_receipt_line`. Another company's receipts
  are not visible.
- All existing ledger and stock-guard tests pass. The eleven test classes that buy stock through
  ingredient lines switch to item lines through one shared helper, `AbstractIntegrationTest.itemFor`,
  which finds or creates the supplier's loose item for an ingredient.

### Frontend (Vitest)

- Item form:
  - an ingredient counted in `pc` offers only `pc`, which is the screenshot bug;
  - the pack hint text is right;
  - both-or-neither is enforced.
- PO form:
  - there are no ingredient options;
  - the conversion line text is right;
  - packs are whole numbers;
  - "+ New item" selects the created item.
- Receive drawer:
  - the summary lines are right;
  - the over-delivery choice appears and is required;
  - compensation is capped.
- Retail form: the total is right, and "+ New item" is hidden without `inventory:write`.

### In the running app (QA stack, :4301)

Walk the straws example end to end:
1. Create the item "hộp 100".
2. Order 5 boxes.
3. Receive 5 with 40 damaged.
4. Receive the replacement.
5. Check the stock, the average and the loss.

Then record a retail purchase of one bag of coffee.

## 9. Docs

- `CONTEXT.md`:
  - **Purchase order line:** it is a supplier item, in packs when the item has one.
  - **Item:** it may declare one pack.
  - New entries: **Pack**, **Goods receipt**, **Damaged on arrival**, **Compensation**, **Retail
    purchase**.
- ADR-0001: a dated note that packs now exist as labels over a quantity of a real unit, which the
  decision anticipated. The decision itself is unchanged.
- ADR-0006: a note that pack lines receive in packs, while lines without a pack keep the any-unit
  option.

## 10. Not in scope

- Lots, expiry dates and first-expired-first-out (sub-project 2).
- AI photo reading and supplier search, and photo storage (sub-project 3).
- Supplier payments and payables: the system computes totals but does not track what has been paid.
- Editing a PO after creation, and undoing a received or retail purchase.
- More than one pack per item, and nested packs (thùng of hộp).
- Returning goods to a supplier.
