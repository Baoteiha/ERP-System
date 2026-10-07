import { convertQty, convertRate } from '../../core/units';

/** What a pack-aware line needs: its own unit and its optional pack. */
export interface PackInfo { unit: string; packLabel?: string | null; packSize?: string | number | null; }

export type OverKind = 'CHARGED' | 'FREE';

const qtyFormat = new Intl.NumberFormat('vi-VN', { maximumFractionDigits: 3 });
const rateFormat = new Intl.NumberFormat('vi-VN', { maximumFractionDigits: 4 });
export const fmtQty = (n: number) => qtyFormat.format(n);
export const fmtRate = (n: number) => rateFormat.format(n);

/** The unit a purchase line is counted in: its pack when it has one, otherwise its unit. */
export function orderingUnit(p: PackInfo): string {
  return p.packLabel ? p.packLabel : p.unit;
}

/** How many of the line's unit one ordering unit holds: the pack size, or 1. */
export function perOrdering(p: PackInfo): number {
  return p.packLabel && p.packSize ? Number(p.packSize) : 1;
}

/** "1 hộp = 100 pc", or "1 bao = 25 kg = 25.000 g" when stock counts another unit; '' without a pack. */
export function packSentence(p: PackInfo, baseUnit?: string): string {
  if (!p.packLabel || !p.packSize) return '';
  const size = Number(p.packSize);
  let s = `1 ${p.packLabel} = ${fmtQty(size)} ${p.unit}`;
  if (baseUnit && baseUnit !== p.unit) s += ` = ${fmtQty(convertQty(size, p.unit, baseUnit))} ${baseUnit}`;
  return s;
}

/** "= 500 pc Ống hút giấy · 450 ₫/pc": the stock a line will create; '' until it has a quantity. */
export function conversionLine(qty: number, costPerOrdering: number, p: PackInfo, baseUnit: string, ingredient: string): string {
  if (!(qty > 0) || !baseUnit) return '';
  const units = qty * perOrdering(p);
  const quantity = convertQty(units, p.unit, baseUnit);
  let s = '= ' + (p.unit !== baseUnit ? `${fmtQty(units)} ${p.unit} = ` : '') + `${fmtQty(quantity)} ${baseUnit} ${ingredient}`;
  if (costPerOrdering > 0) s += ` · ${fmtRate(convertRate(costPerOrdering / perOrdering(p), p.unit, baseUnit))} ₫/${baseUnit}`;
  return s;
}

export interface ReceiptDraft {
  delivered: number; outstanding: number; damaged: number; overKind: OverKind | null;
  costPerOrdering: number; perOrdering: number;
}
export interface ReceiptSummary {
  over: number; chargedIntoStock: number; freeIntoStock: number; lossQty: number; lossAmount: number;
  costPerUnit: number; error: string;
}

/** Mirrors the server's ReceiptSplit, so the drawer shows what confirming will do before it is pressed. */
export function receiptSummary(d: ReceiptDraft): ReceiptSummary {
  const over = Math.max(0, d.delivered - d.outstanding);
  const freeOrdering = d.overKind === 'FREE' ? over : 0;
  const chargedUnits = Math.max(0, d.delivered - freeOrdering) * d.perOrdering;
  const freeUnits = freeOrdering * d.perOrdering;
  const costPerUnit = d.perOrdering > 0 ? d.costPerOrdering / d.perOrdering : 0;
  let error = '';
  if (!(d.delivered > 0)) error = 'Delivered must be above 0';
  else if (d.perOrdering !== 1 && !Number.isInteger(d.delivered)) error = 'Packs are counted whole';
  else if (over > 0 && !d.overKind) error = `${fmtQty(over)} more than outstanding: choose charged or free gift`;
  else if (d.damaged < 0 || d.damaged > chargedUnits + freeUnits) error = 'Damaged cannot exceed what was delivered';
  const damagedCharged = Math.min(Math.max(d.damaged, 0), chargedUnits);
  const damagedFree = Math.max(0, d.damaged - damagedCharged);
  return {
    over, chargedIntoStock: chargedUnits - damagedCharged, freeIntoStock: Math.max(0, freeUnits - damagedFree),
    lossQty: damagedCharged, lossAmount: damagedCharged * costPerUnit, costPerUnit, error,
  };
}
