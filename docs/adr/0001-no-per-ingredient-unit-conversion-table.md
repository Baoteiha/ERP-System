# No per-ingredient unit conversion table

Quantities cross module boundaries in three different units — an item's purchase
unit, an ingredient's base unit, a recipe line's unit — and something has to
reconcile them. We do **not** store conversion factors per ingredient. Instead the
unit vocabulary is closed and fixed (mg, g, kg, ml, cl, l, pc), every unit belongs
to a dimension, and conversion within a dimension is a pure function of the two
units, needing no database lookup. Units of different dimensions do not convert at
all; the attempt is rejected rather than defaulted.

## Considered options

The obvious alternative, and the one this repo's own spec originally called for
(INV-104, "declare factors between purchase unit, stock base unit, and recipe unit
per ingredient"), is a `UnitConversion` table keyed by ingredient. Nearly every ERP
ships one, so its absence here is the surprising choice and the reason this ADR
exists.

A per-ingredient table buys exactly one thing: units whose relationship varies by
ingredient — "1 sack = 25 kg" for beans but 20 kg for sugar. It costs a lookup on
every quantity that crosses a boundary, a row that can be missing or wrong at the
moment a delivery is being received, and a second place where a unit can be defined.

We chose instead to rule that packaging is not a unit. A sack is a quantity of
kilograms, so it is recorded as kilograms and the ambiguity never enters the system.
That leaves only relationships that are the same for every ingredient — a kilogram
is a thousand grams regardless of what is in it — which is precisely what makes a
pure function sufficient.

## Consequences

An item's purchase unit must share a dimension with its ingredient's base unit, and
this is enforced on write. There is no way to express "this ingredient is bought by
weight and stocked by count": an ingredient bought in kg cannot be stocked in pc.
Should that requirement turn out to be real — bought in kg, counted in pieces, at a
per-ingredient weight — it cannot be absorbed by adding a factor to a table, and
this decision has to be reopened rather than extended.

Because every factor is a power of ten and the largest divisor is a thousand,
conversion is exact and never rounds, which is what makes it safe to run on the
receiving path without a reconciliation step afterwards.

Pack size stays available as a future addition on the item — a label over a
quantity of a real unit — and is additive precisely because it is not a unit.
