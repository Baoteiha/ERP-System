import { describe, expect, it } from 'vitest';
import { IngredientResponse, StockItemResponse } from '../core/models';
import { shelfModel } from './shelf-model';

function ing(id: string, name: string, category?: string): IngredientResponse {
  return { id, companyId: 'c', name, baseUnit: 'kg', category, active: true, version: 0 };
}
function stock(id: string, onHand: number, reorder: number, below = false): StockItemResponse {
  return {
    ingredientId: id, branchId: 'b', quantityOnHand: String(onHand), reorderLevel: String(reorder),
    avgUnitCost: { amount: '1000', currency: 'VND' }, belowReorderLevel: below,
  };
}
function one(onHand: number, reorder: number, below = false) {
  return shelfModel([stock('a', onHand, reorder, below)], [ing('a', 'Beans', 'Dry')])[0].containers[0];
}

describe('shelfModel', () => {
  it('is full at three times the reorder level', () => expect(one(30, 10).fill).toBe(1));
  it('is half full at 15 on hand with reorder 10', () => expect(one(15, 10).fill).toBe(0.5));
  it('treats stock far above the target as full', () => expect(one(100, 10).fill).toBe(1));
  it('is low when below the reorder level', () => expect(one(5, 10, true).level).toBe('low'));
  it('is ok otherwise', () => expect(one(20, 10).level).toBe('ok'));
  it('is empty at zero on hand with no fill', () => {
    const c = one(0, 10, true);
    expect(c.level).toBe('empty');
    expect(c.fill).toBe(0);
  });
  it('shows a full container with a flag when there is no reorder level', () => {
    const c = one(5, 0);
    expect(c.noReorderLevel).toBe(true);
    expect(c.fill).toBe(1);
  });
  it('is empty with no fill when there is no stock and no reorder level', () => {
    const c = one(0, 0);
    expect(c.fill).toBe(0);
    expect(c.level).toBe('empty');
  });

  it('sorts categories with Uncategorised last and containers by name', () => {
    const ingredients = [ing('1', 'Zest', 'Fruit'), ing('2', 'Apple', 'Fruit'), ing('3', 'Cup'), ing('4', 'Milk', 'Dairy')];
    const shelves = shelfModel(['1', '2', '3', '4'].map(id => stock(id, 10, 5)), ingredients);
    expect(shelves.map(s => s.category)).toEqual(['Dairy', 'Fruit', 'Uncategorised']);
    expect(shelves.map(s => s.index)).toEqual([0, 1, 2]);
    expect(shelves[1].containers.map(c => c.name)).toEqual(['Apple', 'Zest']);
    expect(shelves[1].containers.map(c => [c.shelf, c.slot])).toEqual([[1, 0], [1, 1]]);
  });

  it('drops stock rows with unknown ingredients', () => {
    const shelves = shelfModel([stock('a', 1, 1), stock('ghost', 1, 1)], [ing('a', 'Beans', 'Dry')]);
    expect(shelves.flatMap(s => s.containers).map(c => c.ingredientId)).toEqual(['a']);
  });
});
