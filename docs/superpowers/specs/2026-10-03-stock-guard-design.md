# Stock Guard — Design

**Date:** 2026-10-03
**Branch:** `feat/stock-guard`, cut from `feat/command-centre-ui`
**Scope:** backend (inventory, sales, catalog) and the POS screen

## 1. Intent

Today a sale can take stock below zero. The ledger "warns but allows": it writes a line to the server
log and nothing else. In the QA data, Thảo Điền sold 101 croissants while holding 51 and ended at −50.

The owner decided that a sale must be blocked when the branch does not have enough stock, and that
the cashier must see the problem before paying.

| Question | Decision |
|---|---|
| What happens when a cart needs more than the branch has | The sale is blocked; there is no override |
| An ingredient the branch has never stocked | It counts as 0, so the sale is blocked |
| What the POS shows | Tiles say "Out of stock"; the order panel lists shortfalls and disables Pay |
| Where the rule is enforced | On the server, when stock is taken, under the existing stock-row lock |

Success means:
- No sale can complete with any ingredient going below zero, whichever till, tab or API client sends it.
- Two tills selling the last item at the same moment produce exactly one sale.
- The cashier sees the shortfall before pressing Pay.

## 2. Server rule

### Blocking a sale

`StockLedgerService.deduct` (the `SALE_DEPLETION` path, called by `InventoryApi.deductForOrder`) works
like this:
1. It locks every stock row the order uses, in ingredient-id order. This is `lockOrCreate`, a
   pessimistic `SELECT … FOR UPDATE`, and it already happens today.
2. It works out each ingredient's new quantity.
3. If **any** ingredient would end below zero, it throws `InsufficientStockException` listing **every**
   short ingredient. No movement is written and no quantity changes.

When the exception is thrown:
- The surrounding transaction rolls back. A payment that would have completed the order is **not**
  recorded, and the order stays `OPEN`.
- The idempotency claim on `ledger_reference` is part of the same transaction, so it rolls back too,
  and a later retry with enough stock succeeds.

Scope of the rule:
- **Sales only.** Waste, adjustments, purchase receipts and reversals keep their current rules. A
  stock-take correction must still be able to set any amount.
- **Untracked ingredients.** An ingredient with no stock row at the branch is a row at 0, so it blocks.
- **Products without a recipe** consume nothing and are never blocked.

### Error contract

`InsufficientStockException` extends `BusinessRuleException`. The handler answers **422** with:
- `code`: `"INSUFFICIENT_STOCK"`
- `detail`: "Not enough stock: Bánh croissant needs 101 pc, 51 left; …"
- `shortfalls`: `[{ ingredientId, ingredientName, unit, required, onHand }]`

`unit` is the ingredient's base unit. `required` and `onHand` are decimal strings at ledger scale.

`INSUFFICIENT_STOCK` joins the documented error codes.

### Shortfalls in the price check

`POST /orders/quote` is the live price check the POS calls as the cart changes.
- It also returns `stockShortfalls`, a list in the same shape, empty when the cart can be fulfilled.
- It reads current quantities **without** locking and never refuses.
- `POST /orders` (create) does not check stock. An open order consumes nothing; stock is taken only
  when the order completes.

### Menu availability

`GET /menu` gives each product `available: boolean`. It is true when the branch has enough of every
ingredient to make **one** of the product at its current recipe version, with no modifiers.
- Products without a recipe are always available.
- It is computed with one batched stock read for the whole menu. Add
  `InventoryApi.unavailable(branchId, Map<UUID, List<StockLine>>) → Set<UUID>`, which reuses the
  ledger's unit conversion. Do not make one query per product.

### Module boundaries

- Sales and catalog reach inventory only through `inventory.api`. ArchUnit enforces this.
- `ConsumptionResolver` (implemented by catalog) supplies the lines for one product.
- Shortfall names come from inventory, which owns ingredients.

## 3. POS

### Tiles

A product with `available === false` shows the text **"Out of stock"**:
- the tile is faded;
- the button is `disabled`;
- its accessible name includes "out of stock".

Modifier-dependent shortfalls are not known on the tile. The order panel catches them.

### Order panel

When the current quote has shortfalls:
- **The warning.** An alert block (`role="alert"`, red tint, text) lists each shortfall: "Bánh
  croissant: needs 101 pc, 51 left".
- **Pay.** It is disabled, with the visible text "Not enough stock" linked through `aria-describedby`.
- **Clearing.** Fixing the cart clears the block on the next quote.

### Refusal at Pay

This happens when another till took the stock first.
- The server message shows **inline** in the panel, not only as a toast.
- The menu reloads and the cart is re-quoted.

### Menu refresh

The menu reloads after every completed sale, as well as on a branch switch as today.

### Orders screen

"Add payment" on an open order passes the same server check. A refusal shows the server message.

## 4. Testing

### Backend (Testcontainers, the existing patterns)

- **Refusal:** a sale that would go below zero is refused with 422 `INSUFFICIENT_STOCK` and every
  shortfall listed. Nothing moves: no movement rows, on-hand unchanged, order still `OPEN`, payment not
  recorded.
- **Exactly enough:** a sale that leaves exactly 0 succeeds.
- **Untracked ingredient:** an ingredient with no stock row blocks the sale.
- **No recipe:** a product without a recipe sells with no stock.
- **Race:** two concurrent payments for the last unit end with one completed and one refused, and
  stock at 0, never −1.
- **Price check:** the quote reports shortfalls, including modifier deltas, and never refuses.
- **Menu:** `available` is false exactly for products that cannot be made once, true for products
  without a recipe, and the menu is built from one stock read.
- **Existing tests** that complete sales without stock get stock in their setup. No assertion is
  loosened.

### Frontend (Vitest)

- the tile shows "Out of stock" and is disabled;
- the shortfall block lists the figures and disables Pay;
- an `INSUFFICIENT_STOCK` refusal shows inline and reloads the menu and quote;
- the menu reloads after a completed sale.

### Visual check

On the seeded QA stack: Thảo Điền's croissant shows as out of stock, and a cart over the stock blocks
Pay.

## 5. Docs

- `doc/erp-cafe-inventory-spec.md`: the warn-but-allow wording becomes "sales are blocked below zero".
- Javadoc on `deduct` / `deductForOrder`.
- The error-code list in `backend/ARCHITECTURE.md`.

## 6. Not in scope

- Reserving stock for open orders.
- A manager override.
- Blocking waste or adjustments below zero.
- Per-branch or per-company settings to turn the rule off.
- Stock warnings anywhere but the POS and the Orders "Add payment".
