import { BranchPerformance } from '../core/models';

export interface TowerSpec {
  branchId: string; code: string; name: string; netSales: number; orderCount: number;
  netMargin: number; height: number; top: boolean; slab: boolean; loss: boolean; x: number; z: number;
}
export interface TowerModel { towers: TowerSpec[]; others: { count: number; netSales: number } | null; }

export const TOWER_MAX_HEIGHT = 6;
export const TOWER_MIN_HEIGHT = 0.25;
export const TOWER_LIMIT = 24;
export const TOWER_GAP = 2.2;

/** Branch performance → towers: top 24 by net sales, heights relative to the best, laid out on a centred grid. */
export function towerModel(outlets: BranchPerformance[]): TowerModel {
  const rows = outlets
    .map(o => ({ o, netSales: Number(o.summary.netSales), netMargin: Number(o.summary.netMargin) }))
    .sort((a, b) => b.netSales - a.netSales);
  const kept = rows.slice(0, TOWER_LIMIT);
  const rest = rows.slice(TOWER_LIMIT);
  const max = kept.length ? kept[0].netSales : 0;
  const cols = Math.ceil(Math.sqrt(kept.length));
  const gridRows = cols ? Math.ceil(kept.length / cols) : 0;

  const towers = kept.map(({ o, netSales, netMargin }, i): TowerSpec => ({
    branchId: o.branchId, code: o.code, name: o.name, netSales, orderCount: o.summary.orderCount, netMargin,
    height: max > 0 ? Math.max(TOWER_MIN_HEIGHT, (netSales / max) * TOWER_MAX_HEIGHT) : TOWER_MIN_HEIGHT,
    top: i === 0 && netSales > 0,
    slab: netSales === 0,
    loss: netMargin < 0,
    x: ((i % cols) - (cols - 1) / 2) * TOWER_GAP,
    z: (Math.floor(i / cols) - (gridRows - 1) / 2) * TOWER_GAP,
  }));

  return {
    towers,
    others: rest.length ? { count: rest.length, netSales: rest.reduce((s, r) => s + r.netSales, 0) } : null,
  };
}
