# Unit conversion happens at the ledger seam

Every quantity in the stock ledger is denominated in its ingredient's **base
unit** — `StockMovement.quantity` and `StockItem.quantityOnHand` never hold
anything else. Conversion into the base unit happens **exactly once, at the
point a quantity enters the ledger**, using `Unit.convert` within one
`Dimension`: sale depletion converts each stock line before merging
(`StockLedgerService`), and goods receiving converts through the item's
purchase unit. A receipt may be recorded in a different unit than the order
was placed in, provided it shares the ingredient's dimension. Cross-dimension
quantities are rejected, never guessed.

## Considered options

Converting in each caller (catalog's recipe explosion, purchasing, future
counts and transfers) was rejected: it writes the same rule N times and every
new caller can forget it — the exact bug this decision fixes, where recipe
lines in different units were summed as raw numbers. Converting nowhere and
constraining every input unit to equal the base unit was rejected as hostile
to real data entry (suppliers sell kg, staff count g).

## Consequences

- The ledger (`StockLedgerService`) resolves each line's ingredient to learn
  its base unit — an extra read per deduction, accepted for making the
  invariant unforgeable by callers.
- The ledger is **immutable**: existing movement rows are never rewritten.
  Conversion applies to entries going forward only.
- An ingredient's base unit becomes the one unit that must stay
  dimension-stable over its life (`IngredientService` must reject
  cross-dimension base-unit changes — tracked in the finish plan).
- Upholds ADR-0001: no per-ingredient conversion table; factors are a pure
  function of two units in one dimension, and packaging stays a label over a
  quantity of a real unit.
