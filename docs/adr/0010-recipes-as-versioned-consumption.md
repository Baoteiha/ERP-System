# Recipes are versioned consumption behind a port the core owns

A recipe is the F&B vertical's bill of materials: what one product consumes
(CAT-13). Until now a sale read it when the order completed
(`CatalogApi.explode`), so an order taken while the latte took 18 g of beans,
and paid after a barista changed it to 20 g, deducted 20 g. And sales, part of
the core, called the catalog vertical to find out, which ADR-0005 rules out.

**Versions.** Saving a recipe adds a `recipe_version` — numbered from 1 per
recipe, with its lines and the time it was saved — and puts it in force
(`recipe.current_version_no`). The application never rewrites a version, and the
database holds it to that only in part: the app role holds no UPDATE or DELETE
on `recipe_version` or `recipe_line` (V24, `RecipeVersioningTest`), so a saved
row cannot be changed or removed through it. It does hold INSERT, and there is no trigger,
while the row-level policy only checks that the version is the company's, so
nothing stops it adding a `recipe_line` under an old version. A bug or a
hand-written statement could therefore still extend a version already in force. Only the
schema owner can change or delete rows. The recipe endpoint's
`version` is the number in force. It is also the stale-edit token: a save that
names an older number answers 409, and of two saves racing from the same
number, the unique `(recipe_id, version_no)` lets one commit and answers the
other 409.

**The snapshot.** An order line records `recipe_version_id`, the version in
force when the line was taken. Completion asks for each line's consumption at
that version, so a payment after a recipe change deducts what the recipe said
when the line was rung up. Reversals are unchanged: a void or refund returns
what the sale's movements recorded and never asks the recipe again.

**The port.** `inventory.api.ConsumptionResolver` belongs to the core; catalog
implements it (`CatalogConsumptionResolver`) and sales depends only on it:

- `currentRecipeVersion(company, product)` — the version to record on a new
  line; empty when the product has no recipe.
- `consumptionFor(company, product, quantity, modifiers, version)` — the
  version's lines plus the selected modifiers' deltas, times the quantity,
  netted per ingredient and unit (the ledger converts units, ADR-0006).

Both answer only for the company they name: another company's product or
version is not found. A version of a product deleted since the line was taken
still resolves, because that sale still has to deduct. `CatalogApi.explode`
and `IngredientConsumption` are gone.

**One line shape, typed units.** A recipe line and a modifier delta are one
embeddable, `ConsumptionLine(ingredientId, quantity, Unit unit)`. The unit is
stored as its canonical symbol, constrained like `ingredient.base_unit` (V8),
and still checked at save against the ingredient, so `cup` is refused (422).

**Existing data (V24).** Each recipe's lines as they stood became its version
1, in force since the recipe was last saved. Lines of OPEN and PAID orders —
the ones that still deduct when they complete — point at that version, the
only one there is. Every other line stays NULL, as none will deduct again: a
completed sale's consumption is in the ledger, which its reversal reads, and
the rest never deducted. From then on NULL means the product had no recipe
when the line was taken, and such a line consumes nothing, even if a recipe is
added before payment. The migration rewrites accepted unit spellings ("Gram" →
`g`) and stops, listing the rows, on any unit it cannot place.

**Modifier deltas are not versioned.** A modifier belongs to a group that many
products share, and an order line names it by id only. A sale applies its
modifiers' deltas as they stand when it completes.

## Considered options

- **Copy the consumption onto the order line.** Exact for modifiers too, but
  it copies the recipe into every line sold, and the bill of materials a sale
  used is no longer something the system can name.
- **Resolve a legacy line to the version in force at its creation.** The
  backfilled version can only carry the recipe's last save time, which is
  later than many legacy lines; they would resolve to nothing and deduct
  nothing. The migration points them at version 1 instead.
- **Version modifier deltas as well.** Needs a version of each selected
  modifier recorded on `order_line_modifier` and passed through the port.
  Deferred until a café's deltas change often enough to matter.
- **Keep the entity's optimistic-lock version as the stale-edit token.** It
  moves once per save as the version number does, but would show two numbers
  for one recipe.
- **Put the port in sales.** Inventory is what consumes stock and already
  owns `StockLine`; in `inventory.api`, another vertical's bill of materials
  can answer the same port with no change to sales.

## Consequences

- Every recipe save is a row kept for good; versions are never pruned, since
  an open order may point at any of them.
- Editing a modifier's deltas while an order using it is open changes that
  order's deduction; removing the modifier, or deleting its group, drops its
  deltas from it, as before.
- An ingredient counts as in use only through the version in force (and
  modifier deltas). If one is deleted while an open order taken under an older
  version still names it, paying that order is refused (422, ingredient not
  found) until the order is cancelled. Deleting an ingredient also needs zero
  stock at every branch, so the window is narrow.
- A new order line costs one more query: the version in force.
- `order_line.recipe_version_id` has no foreign key: sales names catalog rows
  by id, as it does `product_id`, so the modules can be extracted apart
  (ADR-0002).
- Pricing (`CatalogApi.priceLine`) is now the one call from sales into the
  catalog vertical (ADR-0005).
