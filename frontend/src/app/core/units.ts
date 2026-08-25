/**
 * The unit vocabulary, mirroring the backend `Unit` enum. Kept closed on purpose:
 * packaging ("sack", "case") is not a unit — it belongs to the item being bought,
 * expressed as a quantity of one of these.
 *
 * Conversion never crosses a dimension, so any picker that already knows the
 * ingredient should offer only its own dimension via `unitsFor`.
 */
export type Dimension = 'MASS' | 'VOLUME' | 'COUNT';

export interface UnitOption { value: string; label: string; dimension: Dimension; }

/** Ascending by size within each dimension, matching `Unit.of(Dimension)`. */
export const UNITS: UnitOption[] = [
  { value: 'mg', label: 'mg — milligram', dimension: 'MASS' },
  { value: 'g', label: 'g — gram', dimension: 'MASS' },
  { value: 'kg', label: 'kg — kilogram', dimension: 'MASS' },
  { value: 'ml', label: 'ml — millilitre', dimension: 'VOLUME' },
  { value: 'cl', label: 'cl — centilitre', dimension: 'VOLUME' },
  { value: 'l', label: 'l — litre', dimension: 'VOLUME' },
  { value: 'pc', label: 'pc — piece', dimension: 'COUNT' },
];

export const UNIT_GROUPS: { label: string; dimension: Dimension; units: UnitOption[] }[] = [
  { label: 'Weight', dimension: 'MASS', units: UNITS.filter((u) => u.dimension === 'MASS') },
  { label: 'Volume', dimension: 'VOLUME', units: UNITS.filter((u) => u.dimension === 'VOLUME') },
  { label: 'Count', dimension: 'COUNT', units: UNITS.filter((u) => u.dimension === 'COUNT') },
];

export function dimensionOf(unit: string | undefined): Dimension | undefined {
  return UNITS.find((u) => u.value === unit)?.dimension;
}

/** The units a quantity of `baseUnit` may be expressed in — i.e. its own dimension. */
export function unitsFor(baseUnit: string | undefined): UnitOption[] {
  const dimension = dimensionOf(baseUnit);
  return dimension ? UNITS.filter((u) => u.dimension === dimension) : UNITS;
}
