# ERP Cafe

A multi-tenant back office for a coffee-shop chain: what each branch stocks, what
it buys, and what it sells. The load-bearing distinction throughout is between the
things a café **buys** and the things it **sells** — they are different concepts
with different units, and most of this glossary exists to keep them apart.

## Language

### Tenancy

**Company**:
One customer of the system — a café chain. Every piece of master data belongs to
exactly one, and nothing is ever visible across the boundary.
_Avoid_: tenant, organization, account

**Branch**:
One physical café. Stock is held per branch; master data is not.
_Avoid_: store, location, outlet, site

### Ingredients and units

**Ingredient**:
A raw material a company stocks and consumes — beans, oat milk, cups. The only
thing stock is ever counted in.
_Avoid_: item, material, raw material, SKU

**Base unit**:
The unit an ingredient's stock is counted in. One per ingredient, drawn from the
closed unit vocabulary, and the denomination of every quantity in the ledger.
_Avoid_: stock unit, canonical unit, stocking UOM

**Dimension**:
The physical quantity a unit measures — mass, volume, or count. Conversion is
defined only within one dimension; there is no number of millilitres in a kilogram.
_Avoid_: unit type, measure, UOM class

**Unit**:
A member of the fixed, closed vocabulary of measures (mg, g, kg, ml, cl, l, pc).
Not user-configurable: a fixed set is what lets any two units of the same dimension
convert without a lookup.
_Avoid_: UOM, measure

**Canonical unit**:
The reference unit of a dimension (g, ml, pc) that every conversion factor is
expressed against. An implementation detail of conversion, not a modelling
concept — an ingredient's base unit need not be one.
_Avoid_: base unit

**Packaging**:
A label over a quantity of a real unit — "25 kg sack", "case of 20". Never a unit,
and never a base unit. A sack is 25 kg, and it is the kilograms that convert.
_Avoid_: pack unit, pack size, case, container

### Purchasing

**Supplier**:
A company the café buys from.
_Avoid_: vendor, merchant

**Item**:
What one supplier sells: their name for it, the single unit they sell it in, and
the one ingredient it becomes once received. Several items may become the same
ingredient — the same beans from two suppliers, or one supplier's 1 kg bag and
25 kg sack. An item's purchase unit must share a dimension with its ingredient's
base unit, which is what makes receiving need no decision.
_Avoid_: product, supplier product, purchasable, SKU

**Purchase unit**:
The unit an item is bought in — the supplier's vocabulary, not the café's.
_Avoid_: order unit, supplier unit, buying UOM

**Purchase order**:
An agreement to buy from one supplier, delivered to one branch, received in whole
or in part.
_Avoid_: PO, requisition

**Purchase order line**:
One item on a purchase order, at an agreed quantity and unit cost. The line records
the item it was placed against, but keeps its own copy of the quantity, unit, and
cost: master data may be edited or retired afterwards, and an order is a record of
what was agreed at the time, not a live view of the catalogue.
_Avoid_: PO line, order item

**Receiving**:
Recording what a delivery actually contained. Converts each line's quantity from its
purchase unit into the ingredient's base unit, and is the only way stock enters.
_Avoid_: goods receipt, intake, delivery

### Stock

**Stock movement**:
One immutable, signed change to an ingredient's stock at a branch, denominated in
that ingredient's base unit. The ledger of these is the source of truth for stock;
mistakes are corrected by a further movement, never by rewriting one.
_Avoid_: transaction, entry, adjustment (an adjustment is one *kind* of movement)

**Stock item**:
An ingredient's current balance at one branch — quantity on hand, reorder level,
and moving-average cost. A cached rollup of the movements, never authoritative
over them.
_Avoid_: stock level, inventory item, item

### Menu

**Product**:
What a customer buys — a line on the menu, priced per company and overridable per
branch.
_Avoid_: menu item, item, SKU

**Recipe**:
What one product consumes, as a set of ingredient quantities. A sale explodes the
recipe into stock movements.
_Avoid_: bill of materials, BOM

**Recipe unit**:
The unit a recipe line expresses consumption in. Must be reconciled against the
ingredient's base unit before the deduction is trustworthy; today it is free text
and is not (INV-105).
_Avoid_: consumption unit
