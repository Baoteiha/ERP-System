# ERP Cafe — Inventory Module Spec

> Inventory module for the Nguyệt Quế cafe ERP. This document scopes **inventory
> only**; it references the catalog, sales, and organization modules where they
> touch stock, but does not specify them.
>
> **Status legend:** ✅ built · 🟡 partial · ⬜ not started

## 1. Goal

Inventory is the **system of record for what is physically in the store, what it
cost, and where it went**. Every other claim the ERP makes about margin, waste,
or purchasing is derived from this module, so its accuracy is the constraint on
everything downstream.

The design principle is a single append-only ledger. Stock levels are a
projection of that ledger, never an independently-edited number. Corrections are
new movements, not overwrites — so any figure in the system can be drilled back
to the events that produced it.

**Acceptance test for the whole module:** no manager keeps a shadow spreadsheet.
If someone maintains a parallel Excel file for the numbers they actually
believe, the module has failed regardless of feature count.

## 2. Current state

Substantially more is built than earlier drafts of this document described.

**Built:** ingredient master, branch-scoped stock items, append-only movement
ledger with seven movement types, supplier master and supplier items,
purchase-order lifecycle with lines in their own unit, moving-average costing,
versioned-recipe depletion on sale with COGS capture, reversal of exactly what a
sale took, low-stock detection, waste and adjustment entry, unit conversion at
the ledger seam, row-level security on every tenant table under a non-superuser
role, optimistic locking on edits.

**Principal gaps:** physical stocktake and variance reporting, lot/expiry
tracking, prep/sub-recipe production, transfers, and the supplier communication
loop.

## 3. Domain model

| Entity | Status | Notes |
|---|---|---|
| `Ingredient` | ✅ | `companyId`, `name`, `baseUnit`, `category`, `active`, `deleted` |
| `StockItem` | ✅ | Branch-scoped projection; unique on `(ingredient_id, branch_id)`; holds `quantityOnHand`, `reorderLevel`, `avgUnitCost` |
| `StockMovement` | ✅ | Append-only ledger; `type`, `quantity` (in the ingredient's base unit), `unitCost`, `refType`/`refId`, `note`. A **ledger reference** `(refType, refId)` is claimed once in `ledger_reference` (V13), so a repeated deduction or reversal of one reference is a no-op |
| `MovementType` | 🟡 | `PURCHASE_RECEIPT`, `SALE_DEPLETION`, `SALE_REVERSAL`, `WASTE`, `ADJUSTMENT`, `TRANSFER_IN`, `TRANSFER_OUT`. Missing: `PRODUCTION_IN`, `PRODUCTION_OUT`, `SUPPLIER_RETURN`, `COUNT_VARIANCE` |
| `Supplier` | ✅ | |
| `PurchaseOrder` / `PurchaseOrderLine` | ✅ | Status: `DRAFT`, `SENT`, `PARTIALLY_RECEIVED`, `RECEIVED`, `CANCELLED` |
| `Item` | ✅ | What a supplier sells: `companyId`, `sku`, `name`, `supplierId`, `ingredientId`, `unit`, `active`, `deleted`. Purchase unit must share a `Dimension` with the ingredient's base unit. A `PurchaseOrderLine` records the item it came from (`item_id`, nullable) and keeps its own `unit`, quantity and cost (V25); an item line must belong to the order's supplier |
| `Unit` / `Dimension` | ✅ | **New.** Closed unit vocabulary (mg, g, kg, ml, cl, l, pc) with dimension-scoped conversion |
| ~~`UnitConversion`~~ | ❌ | **Rejected.** Per-ingredient conversion factors; superseded by `Unit`/`Dimension` — see [ADR-0001](../docs/adr/0001-no-per-ingredient-unit-conversion-table.md) |
| `StockLot` | ⬜ | **New.** Lot-level quantity, expiry, unit cost. Requires `StockItem` to become a rollup over lots |
| `StockCount` / `StockCountLine` | ⬜ | **New.** Physical count session, counted vs. system quantity, variance |
| `PrepItem` | ⬜ | **New.** Ingredient producible from other ingredients via a prep recipe |
| `SellingSchedule` | ⬜ | **New.** Drives demand forecasting (whiteboard: *"đổi lịch bán"*) |

## 4. Functional specification

### 4.1 Ingredient master and units — `INV-1xx`

| ID | Function | Status |
|---|---|---|
| INV-101 | Ingredient CRUD, scoped to company, soft-delete; delete refused while stock, an open purchase order or a recipe or modifier still uses it, and the base unit is fixed once stock has moved (`IngredientIntegrityTest`) | ✅ |
| INV-102 | Ingredient categories | ✅ |
| INV-103 | Base unit per ingredient | ✅ |
| INV-104 | **Closed unit vocabulary with dimension-scoped conversion** — no per-ingredient factors; see [ADR-0001](../docs/adr/0001-no-per-ingredient-unit-conversion-table.md) | ✅ |
| INV-105 | **Enforce conversion at every boundary** — receipt ✅ (`ReceivingConversionTest`, `PurchaseLineUnitTest`), ledger entry ✅ ([ADR-0006](../docs/adr/0006-conversion-at-the-ledger-seam.md), `UnitConversionSeamTest`), recipe and modifier-delta save-time validation ✅ (`InventoryApi.validateConsumption`, `RecipeVersioningTest`); count, transfer ⬜ (neither exists yet) | 🟡 |
| INV-106 | Ingredient provenance — optionally restrict creation to received stock | ⬜ |

**INV-104 is settled and every INV-105 boundary that exists now converts.** `Unit` is a
closed vocabulary, each unit belongs to a `Dimension`, and conversion within a
dimension is a pure function — no per-ingredient conversion table
([ADR-0001](../docs/adr/0001-no-per-ingredient-unit-conversion-table.md)).
`Ingredient.baseUnit` is canonicalised and constrained to that vocabulary (V8),
`Item` carries a purchase unit that must share a dimension with it (V9), and
conversion happens once, at the ledger seam
([ADR-0006](../docs/adr/0006-conversion-at-the-ledger-seam.md)): the ledger converts
every stock line to the ingredient's base unit before merging (rejecting
cross-dimension lines), and receiving converts a receipt counted in any
same-dimension unit — including a per-received-unit cost override — before booking.

A recipe line and a modifier delta now carry a `Unit` of the closed vocabulary
(V24) and are checked at save against the ingredient's base unit, so a bad unit
(`cup`) is refused with 422 when the recipe is written, not when a sale reaches the
ledger. A purchase line is counted and priced in its own unit, taken from its item
or given with the ingredient (base unit when neither), and a receipt defaults to
that unit.

Still open on INV-105: counts and transfers don't exist yet and must convert when
they do.

### 4.2 Ledger and movements — `INV-2xx`

| ID | Function | Status |
|---|---|---|
| INV-201 | Append-only movement ledger; corrections are new movements | ✅ |
| INV-202 | Stock level as projection of the ledger | ✅ |
| INV-203 | Movement history, paged and filterable | ✅ |
| INV-204 | Manual adjustment with note | ✅ |
| INV-205 | Waste entry | 🟡 |
| INV-206 | **Waste reason codes** — enum (`EXPIRED`, `SPILLED`, `PREP_ERROR`, `STAFF_DRINK`, `TRAINING`, `CUSTOMER_RETURN`), replacing free-text `note` | ⬜ |
| INV-207 | **Inter-branch transfer endpoint** — pairs `TRANSFER_OUT`/`TRANSFER_IN`, with in-transit state | ⬜ |
| INV-208 | **Supplier return** — new movement type, links to the originating PO and credit | ⬜ |
| INV-209 | Every movement drillable to its source document | 🟡 |

`WasteRequest` currently takes a free-text `note`. Expired, spilled, staff drink,
and training loss are different business problems with different remedies; free
text means they can never be aggregated or acted on. INV-206 is a small change
with disproportionate analytical value.

Transfer types already exist in `MovementType` and `StockItem` is already
branch-scoped, so INV-207 needs only a service and endpoint — no schema change.

### 4.3 Procurement — `INV-3xx`

| ID | Function | Status |
|---|---|---|
| INV-301 | Supplier master CRUD | ✅ |
| INV-302 | Purchase order lifecycle | ✅ |
| INV-303 | Receiving against a PO, full or partial | ✅ |
| INV-304 | Receipt writes `PURCHASE_RECEIPT` movements at actual cost | ✅ |
| INV-305 | **Receiving discrepancy record** — what was ordered vs. delivered vs. accepted, per line, with a reason | ⬜ |
| INV-306 | **Purchase price variance** — flag when invoiced unit cost differs from the PO by more than a threshold | ⬜ |
| INV-307 | **Supplier price history** per ingredient, with trend | ⬜ |
| INV-308 | Payment recorded against a PO | ⬜ |
| INV-309 | Supplier catalog — what each supplier sells, at what pack size and price | 🟡 |
| INV-310 | Supplier performance — on-time rate, fill rate, price stability | ⬜ |

`PARTIALLY_RECEIVED` exists as a status, but nothing records *which* lines were
short or why. INV-305 and INV-306 close that, and together answer the
whiteboard's `10kg xoài — 1tr` versus `10kg xoài — 1tr5` question.

### 4.4 Consumption — `INV-4xx`

| ID | Function | Status |
|---|---|---|
| INV-401 | Recipe consumption when an order completes, deducting ingredients at the recipe version the line was taken under ([ADR-0010](../docs/adr/0010-recipes-as-versioned-consumption.md)) | ✅ |
| INV-402 | Modifier recipe lines included in depletion | ✅ |
| INV-403 | COGS captured at depletion | ✅ |
| INV-404 | Reversal on void and refund: one reversal per recorded movement, same quantity and unit cost, whatever the recipe says now (`ReverseDeductionIntegrationTest`) | ✅ |
| INV-405 | Stock guard: a sale that would take any ingredient below zero is refused (422 `INSUFFICIENT_STOCK`, with a `shortfalls` list); `POST /orders/quote` reports `stockShortfalls` and `GET /menu` flags each product `available` (`StockGuardIntegrationTest`, `QuoteShortfallIntegrationTest`, `MenuAvailabilityIntegrationTest`) | ✅ |
| INV-406 | **Prep / sub-recipe production** — consume ingredients, produce a stockable prep item (e.g. brew a batch of tea syrup) | ⬜ |
| INV-407 | **Yield and shrinkage factors** — 1 kg of beans does not yield 1 kg of brewed coffee | ⬜ |

INV-406 corresponds to the whiteboard's `1TS = 1 Trà + 1 Chanh + …` note. Every
cafe has prep items; without them, batch production has to be faked as waste
plus adjustment, which corrupts both figures.

INV-405 is the stock guard. A sale is refused when any ingredient's net
consumption is above zero and would leave on-hand below zero; an ingredient with
no stock row at the branch counts as 0. The refusal is HTTP 422 `INSUFFICIENT_STOCK`
with a `shortfalls` array (`ingredientId`, `ingredientName`, `unit`, `required`,
`onHand`), and nothing moves. Only sales are refused: waste, adjustments, receipts
and reversals are unchanged, so on-hand can still go negative through a waste or an
adjustment. A product without a recipe always sells. `GET /menu` marks a product
unavailable when one of it (no modifiers) exceeds stock; the quote catches the
modifiers.

### 4.5 Accuracy control — `INV-5xx`

| ID | Function | Status |
|---|---|---|
| INV-501 | **Stocktake session** — open a count, enter counted quantities per ingredient, close it | ⬜ |
| INV-502 | **Variance posting** — difference posts as `COUNT_VARIANCE`, never as a silent overwrite | ⬜ |
| INV-503 | **Theoretical vs. actual usage** — expected usage (sales × recipes) against real usage (opening + receipts − closing count) | ⬜ |
| INV-504 | **Variance ranked by money lost**, per ingredient, per branch, per period | ⬜ |
| INV-505 | Partial / cycle counts, so high-value items can be counted more often | ⬜ |
| INV-506 | Count sheets usable on a phone, offline | ⬜ |

**This section is the highest-value block in the module after INV-104.** The
variance loop is what converts inventory from bookkeeping into a measurement
instrument: it is the only mechanism that reveals theft, over-pouring, unlogged
waste, and recipe drift. "You lost 1.8M₫ of milk this month that no sale
accounts for" is the sentence that changes behaviour.

`POST /stock/adjust` exists, but a blind adjustment is a correction, not a
count — it *hides* variance by absorbing it. A count records the discrepancy
before correcting it. Without INV-501–504 there is nothing tying
`quantityOnHand` back to physical reality, and book stock drifts from real stock
within weeks.

### 4.6 Perishables — `INV-6xx`

| ID | Function | Status |
|---|---|---|
| INV-601 | **Lot-level stock** — same ingredient, multiple deliveries, distinct expiry dates and unit costs | ⬜ |
| INV-602 | **Expiry capture at receipt** | ⬜ |
| INV-603 | **FEFO depletion** — first-expiring lot consumed first | ⬜ |
| INV-604 | **Expiry alerting** — approaching-expiry report and notification | ⬜ |
| INV-605 | Auto-suggest waste write-off for lapsed lots | ⬜ |

`StockItem` is one row per ingredient per branch with a single `quantityOnHand`,
so it structurally cannot hold two lots of milk with different expiry dates.
This is a schema change: `StockItem` becomes a rollup over `StockLot`. Earlier
drafts of this spec promised to capture expiry at intake and then had nowhere to
store it. For a cafe running dairy, syrups, and fruit, this is table stakes.

### 4.7 Costing — `INV-7xx`

| ID | Function | Status |
|---|---|---|
| INV-701 | Moving-average unit cost per stock item; a receipt after stock went negative (by waste or adjustment; sales can no longer do it) takes the receipt's cost, not a blend with the zero-cost shortfall (`IngredientIntegrityTest`) | ✅ |
| INV-702 | COGS per order | ✅ |
| INV-703 | **Stated valuation policy** — weighted average is the chosen method; document it and make it explicit in the API | ⬜ |
| INV-704 | **Recipe cost rollup** — current cost to produce each product, recalculated as purchase prices move | ⬜ |
| INV-705 | **Margin alerting** — notify when a product's margin falls below target because input costs rose | ⬜ |
| INV-706 | Inventory valuation report — total value on hand, by branch and category | ⬜ |
| INV-707 | Period COGS reconcilable to an external ledger | ⬜ |

INV-704 and INV-705 are what make inventory reach pricing decisions rather than
merely record them. They implement the whiteboard's *"Quản lý giá tiền sản
phẩm"*. Inventory that never reaches a pricing decision is accurate bookkeeping
and nothing more.

### 4.8 Replenishment — `INV-8xx`

| ID | Function | Status |
|---|---|---|
| INV-801 | Static reorder level per stock item | ✅ |
| INV-802 | Low-stock query | ✅ |
| INV-803 | **Selling schedule** — what is sold on which days | ⬜ |
| INV-804 | **Demand forecast** — schedule × recipes → projected ingredient need | ⬜ |
| INV-805 | **Suggested purchase orders** — draft POs grouped by supplier, from the shortfall | ⬜ |
| INV-806 | **Forecast accuracy tracking** — score predictions against actuals per ingredient | ⬜ |
| INV-807 | Day-of-week, holiday, and seasonality factors | ⬜ |

A static threshold answers "am I low now". The whiteboard asks *"chưa có đủ
nguyên liệu cho ngày mai"* — "will I have enough for tomorrow", which is a
different mechanism requiring the schedule in INV-803.

INV-806 matters more than it appears: a forecast nobody scores is a guess with a
user interface.

### 4.9 Supplier communication and AI — `INV-9xx`

| ID | Function | Status |
|---|---|---|
| INV-901 | **Invoice capture** — photograph a delivery invoice, extract lines, match against the open PO, flag disagreements | ⬜ |
| INV-902 | Low-stock alert drafted automatically | ⬜ |
| INV-903 | Alert states quantity needed **and required delivery time** | ⬜ |
| INV-904 | **Messaging channel integration** — Zalo, Messenger, Viber | ⬜ |
| INV-905 | **Unified supplier inbox** — one screen across channels (*"màn hình tích hợp"*) | ⬜ |
| INV-906 | **Conversational order chase** — "đã chuyển hàng chưa?" → parse reply → update expected arrival | ⬜ |
| INV-907 | Price negotiation and confirmation capture (*"chốt giá tiền"*) | ⬜ |
| INV-908 | Camera product intake — detect product, look up details, pull reference image | ⬜ |
| INV-909 | Intake form auto-fill with missing-field prompts | ⬜ |

**INV-901 is the AI feature worth building first**, not INV-908. Reading a
delivery invoice and reconciling it against a known PO is a constrained problem
with a checkable answer, and it directly feeds the discrepancy and price-variance
work in INV-305/306. Recognising an arbitrary product from a camera hover and
searching the internet for it is unreliable, and its output still needs manual
confirmation.

The value of AI here is removing capture friction, not demonstrating
recognition. Inventory systems fail because logging waste takes ninety seconds
during a rush, so nobody does it, so the data rots. Receiving should take
seconds; waste should be two taps at the bin. That is the bar.

INV-908/909 remain in scope but are deliberately sequenced last, consistent with
the whiteboard marking prefill as backlog.

### 4.10 Reporting — `INV-10xx`

| ID | Function | Status |
|---|---|---|
| INV-1001 | Stock on hand, by branch and category | ✅ |
| INV-1002 | Movement history | ✅ |
| INV-1003 | **Variance report** (see INV-503/504) | ⬜ |
| INV-1004 | **Waste analysis by reason code**, trended | ⬜ |
| INV-1005 | **Usage report** — consumption per ingredient per period | ⬜ |
| INV-1006 | Valuation report | ⬜ |
| INV-1007 | Supplier price trend | ⬜ |
| INV-1008 | Every reported figure drills through to source movements | 🟡 |

### 4.11 Interface — `INV-11xx`

| ID | Function | Status |
|---|---|---|
| INV-1101 | Ingredient, supplier, PO, and stock management screens | ✅ |
| INV-1102 | **Mobile receiving flow**, usable one-handed at the dock | ⬜ |
| INV-1103 | **Mobile waste entry**, two taps | ⬜ |
| INV-1104 | **Offline capture with sync** — the receiving dock is exactly where wifi drops | ⬜ |
| INV-1105 | Count sheet interface (see INV-506) | ⬜ |
| INV-1106 | Alert and exception inbox | ⬜ |
| INV-1107 | 3D cafe map with clickable storage locations | ⬜ |

INV-1107 is retained as a stretch goal, explicitly deprioritised. It was the
headline of earlier drafts, but no cafe abandons a working inventory system for
want of a 3D visualisation, and it requires per-location stock granularity the
model does not have. Location-level stock should be justified by a real need
before the visualisation is built on top of it.

### 4.12 Security — `INV-12xx`

| ID | Function | Status |
|---|---|---|
| INV-1201 | Tenant scoping on all inventory endpoints | ✅ |
| INV-1202 | Postgres RLS on every tenant table, forced (V22; `RlsEverywhereTest`) | ✅ |
| INV-1203 | Optimistic locking against stale-form edits | ✅ |
| INV-1204 | Extend RLS to remaining inventory tables | ✅ V22, with the tables of every other module |
| INV-1205 | Non-superuser production DB role | ✅ `erp_app` (V22), which is not a superuser and cannot bypass RLS (`AppDatabaseRoleTest`); the migrations refuse an existing role that could (V22, V26_005) |
| INV-1206 | Role-based permissions on adjustment, waste, and count approval | ⬜ |

INV-1206 matters because adjustment and waste are the two endpoints through
which stock can be made to disappear without a sale. They need approval
thresholds and an audit trail.

## 5. Roadmap

Sequenced by whether the work unblocks other work, not by visibility.

**Phase A — make the numbers correct.** INV-104, INV-105. Nothing else is worth
building on numbers that can be wrong by three orders of magnitude. Done, apart
from the count and transfer boundaries that wait for those features; it included
a migration that normalised existing recipe-line units (V24).

**Phase B — make the numbers verifiable.** INV-501 through INV-504, plus
INV-206. This is the variance loop. It is worth more than every remaining phase
combined, and it is mostly a stocktake entity plus a report over the ledger that
already exists.

**Phase C — close procurement.** INV-305 through INV-308, INV-207, INV-208.
Receiving discrepancy, price variance, transfers, supplier returns.

**Phase D — perishables.** INV-601 through INV-605. The lot schema change and
FEFO.

**Phase E — costing reaches decisions.** INV-704 through INV-706, INV-1004,
INV-1005.

**Phase F — prep and production.** INV-406, INV-407.

**Phase G — demand-driven replenishment.** INV-803 through INV-807.

**Phase H — supplier communication.** INV-901 through INV-907. Invoice capture
first, then messaging integration.

**Phase I — capture friction and stretch goals.** INV-1102 through INV-1104,
then INV-908/909, then INV-1107.

## 6. Quality bar

Feature completion is not the target. A finished module means:

1. **No shadow spreadsheet.** Nobody keeps parallel figures they trust more.
2. **The variance loop runs.** Someone reviews ranked variance every period, and
   acts on it.
3. **Cost changes reach pricing.** A supplier price rise surfaces as a margin
   warning without anyone asking.
4. **Capture is cheap enough to stay honest.** Receiving in seconds, waste in two
   taps, offline-tolerant.
5. **Forecasts are scored**, not merely produced.
6. **Financial figures reconcile** to an external ledger.
7. **The system is never in the way.** Sub-second on a cheap phone; it never
   blocks the queue.

Points 1 and 2 are the load-bearing ones. A module satisfying only those is more
useful than one satisfying only the other five.

## 7. Open questions

- **Valuation method.** The code implements weighted average. Confirm this over
  FIFO before lots land — lots make FIFO cheap to implement, and the choice
  affects COGS during price volatility.
- **Lot granularity.** Per delivery line, or per physical container? Per line is
  simpler; per container is what staff can actually count.
- **Count cadence.** Full monthly count, or weekly cycle counts weighted by
  value? Affects INV-505 design.
- **Quantity capture at intake.** Fully from image, or confirmed manually? Pack
  size ambiguity suggests manual confirmation of a suggested value.
- **Forecast horizon.** Next day only, or a rolling week?
- **Messaging integration route.** Official Zalo OA API, or a bridge? Determines
  whether INV-904 is a week or a month.
- **Location-level stock.** Needed operationally, or only to justify INV-1107?

## 8. Out of scope

Sales, staffing, HR, identity, and organization are separate modules. Inventory
consumes `OrganizationApi` for branches and exposes `InventoryApi` for
depletion and COGS; those two seams are the whole contract.

General-ledger integration is out of scope for this module, but INV-707 should
leave period COGS in a form an accounting system can consume.
