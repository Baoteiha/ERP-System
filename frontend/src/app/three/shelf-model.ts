import { IngredientResponse, Money, StockItemResponse } from '../core/models';

export type Level = 'ok' | 'low' | 'empty';
export interface ContainerSpec {
  ingredientId: string; name: string; unit: string; onHand: number; reorderLevel: number;
  avgUnitCost: Money; fill: number; level: Level; noReorderLevel: boolean; shelf: number; slot: number;
}
export interface ShelfSpec { category: string; index: number; containers: ContainerSpec[]; }

const UNCATEGORISED = 'Uncategorised';

/** Stock + ingredients → one shelf per category (alphabetical, "Uncategorised" last), containers alphabetical. */
export function shelfModel(stock: StockItemResponse[], ingredients: IngredientResponse[]): ShelfSpec[] {
  const byId = new Map(ingredients.map(i => [i.id, i]));
  const groups = new Map<string, { s: StockItemResponse; i: IngredientResponse }[]>();
  for (const s of stock) {
    const i = byId.get(s.ingredientId);
    if (!i) continue;
    const category = i.category?.trim() || UNCATEGORISED;
    const list = groups.get(category) ?? [];
    list.push({ s, i });
    groups.set(category, list);
  }

  const categories = [...groups.keys()].sort((a, b) =>
    a === UNCATEGORISED ? 1 : b === UNCATEGORISED ? -1 : a.localeCompare(b));

  return categories.map((category, index) => ({
    category,
    index,
    containers: groups.get(category)!
      .sort((a, b) => a.i.name.localeCompare(b.i.name))
      .map(({ s, i }, slot) => container(s, i, index, slot)),
  }));
}

function container(s: StockItemResponse, i: IngredientResponse, shelf: number, slot: number): ContainerSpec {
  const onHand = Number(s.quantityOnHand);
  const reorderLevel = Number(s.reorderLevel);
  const noReorderLevel = !(reorderLevel > 0);
  let fill: number;
  if (onHand <= 0) fill = 0;
  else if (noReorderLevel) fill = 1;
  else fill = Math.min(1, Math.max(0, onHand / Math.max(3 * reorderLevel, onHand)));
  const level: Level = onHand <= 0 ? 'empty' : s.belowReorderLevel ? 'low' : 'ok';
  return {
    ingredientId: s.ingredientId, name: i.name, unit: i.baseUnit, onHand, reorderLevel,
    avgUnitCost: s.avgUnitCost, fill, level, noReorderLevel, shelf, slot,
  };
}
