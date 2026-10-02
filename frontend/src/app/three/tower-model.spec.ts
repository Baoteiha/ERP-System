import { describe, expect, it } from 'vitest';
import { BranchPerformance } from '../core/models';
import { TOWER_GAP, TOWER_LIMIT, TOWER_MAX_HEIGHT, TOWER_MIN_HEIGHT, towerModel } from './tower-model';

function branch(i: number, netSales: number, netMargin = 1): BranchPerformance {
  return {
    branchId: `b${i}`, name: `Branch ${i}`, code: `B${i}`,
    summary: {
      orderCount: i, grossSales: '0', discountTotal: '0', netSales: String(netSales), taxTotal: '0',
      grandTotal: '0', cogs: '0', laborCost: '0', grossMargin: '0', netMargin: String(netMargin),
    },
  };
}

describe('towerModel', () => {
  const model = towerModel([branch(1, 100), branch(2, 50, -0.1), branch(3, 0)]);

  it('scales heights relative to the best branch with a minimum', () => {
    expect(model.towers.map(t => t.height)).toEqual([TOWER_MAX_HEIGHT, 3, TOWER_MIN_HEIGHT]);
  });

  it('marks only the first tower as top', () => {
    expect(model.towers.map(t => t.top)).toEqual([true, false, false]);
  });

  it('marks zero sales as a slab and negative margin as a loss', () => {
    expect(model.towers.map(t => t.slab)).toEqual([false, false, true]);
    expect(model.towers.map(t => t.loss)).toEqual([false, true, false]);
  });

  it('sorts by net sales descending', () => {
    const m = towerModel([branch(1, 10), branch(2, 30), branch(3, 20)]);
    expect(m.towers.map(t => t.branchId)).toEqual(['b2', 'b3', 'b1']);
  });

  it('does not mark top when the best branch has no sales', () => {
    const m = towerModel([branch(1, 0), branch(2, 0)]);
    expect(m.towers.every(t => !t.top)).toBe(true);
    expect(m.towers.every(t => t.height === TOWER_MIN_HEIGHT)).toBe(true);
  });

  it('lays out a centred grid', () => {
    const m = towerModel([branch(1, 4), branch(2, 3), branch(3, 2), branch(4, 1)]);
    expect(m.towers.slice(0, 3).map(t => t.x)).toEqual([-1.1, 1.1, -1.1]);
    expect(m.towers.map(t => t.z)).toEqual([-1.1, -1.1, 1.1, 1.1]);
    expect(TOWER_GAP).toBe(2.2);
  });

  it('keeps the top 24 and totals the rest as others', () => {
    const many = Array.from({ length: 30 }, (_, i) => branch(i, 100 - i));
    const m = towerModel(many);
    expect(m.towers.length).toBe(TOWER_LIMIT);
    expect(m.others?.count).toBe(6);
    expect(m.others?.netSales).toBe(76 + 75 + 74 + 73 + 72 + 71);
  });

  it('has no others when everything fits', () => {
    expect(model.others).toBeNull();
  });
});
