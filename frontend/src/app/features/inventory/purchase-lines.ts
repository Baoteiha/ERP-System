import { Component, input, output } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { IngredientResponse, ItemResponse } from '../../core/models';
import { MoneyPipe } from '../../core/util';
import { IconComponent } from '../../shared/icon';
import { conversionLine, orderingUnit } from './purchase-math';

/** A line as edited: a supplier item, the quantity in its ordering unit, and the price per that unit. */
export interface PurchaseLine { itemId: string; quantity: string; unitCost: string; }
export const newPurchaseLine = (): PurchaseLine => ({ itemId: '', quantity: '', unitCost: '' });
/** The picker's "+ New item" value; never sent to the server. */
export const NEW_ITEM = '__new__';

/** Why the lines cannot be saved yet, or '' when they can. */
export function purchaseLinesError(lines: PurchaseLine[], items: ItemResponse[]): string {
  if (lines.some((l) => !l.itemId)) return 'Every line needs an item';
  if (lines.some((l) => !(Number(l.quantity) > 0) || l.unitCost === '' || Number(l.unitCost) < 0)) return 'Every line needs a quantity above 0 and a cost';
  for (const l of lines) {
    const item = items.find((i) => i.id === l.itemId);
    if (item?.packLabel && !Number.isInteger(Number(l.quantity))) return `Packs are counted whole: ${l.quantity} ${item.packLabel}`;
  }
  return '';
}

/**
 * Purchase lines: pick the supplier's items, enter quantities in their packs, and see what each line puts
 * into stock. Shared by the New purchase order form and the Retail purchase page.
 */
@Component({
  selector: 'app-purchase-lines',
  standalone: true,
  imports: [FormsModule, MoneyPipe, IconComponent],
  template: `
    <div class="line-editor">
      @if (!disabled() && !items().length) {
        <div class="empty-lines soft">This supplier has no items yet.
          @if (canCreateItem()) { <button type="button" class="btn btn-sm btn-ghost" (click)="newItem.emit(0)">+ New item</button> }
        </div>
      }
      @for (line of lines(); track $index; let idx = $index) {
        <div class="po-line">
          <select class="select" [ngModel]="line.itemId" (ngModelChange)="pick(idx, $event)" [disabled]="disabled()" aria-label="Item">
            <option value="">{{ disabled() ? 'Choose a supplier first' : 'Choose an item' }}</option>
            @for (it of items(); track it.id) { <option [value]="it.id">{{ it.name }}</option> }
            @if (canCreateItem() && !disabled()) { <option [value]="newItemValue">+ New item</option> }
          </select>
          <div class="with-unit">
            <input class="input" type="number" min="0" [step]="itemOf(line)?.packLabel ? 1 : 0.001" [(ngModel)]="line.quantity" placeholder="Qty" aria-label="Quantity" [disabled]="disabled()" />
            <span class="unit">{{ unitLabel(line) }}</span>
          </div>
          <div class="with-unit">
            <input class="input" type="number" min="0" step="1" [(ngModel)]="line.unitCost" placeholder="Price" aria-label="Price" [disabled]="disabled()" />
            @if (unitLabel(line); as u) { <span class="unit">/ {{ u }}</span> }
          </div>
          <button type="button" class="btn btn-ghost btn-icon" (click)="remove(idx)" aria-label="Remove line"><app-icon name="close" [size]="16" /></button>
          @if (conversion(line); as c) { <div class="conv soft">{{ c }}</div> }
        </div>
      }
      <div class="foot">
        <button type="button" class="btn btn-outline" (click)="add()" [disabled]="disabled()"><app-icon name="plus" [size]="15" /> Add line</button>
        <span class="total"><span class="soft">Total</span> <b class="num">{{ total() | money }}</b></span>
      </div>
    </div>
  `,
  styles: [`
    .line-editor { display: grid; gap: .65rem; }
    .po-line { display: grid; grid-template-columns: minmax(180px, 1fr) 140px 170px 40px; gap: .5rem; align-items: center; }
    .conv { grid-column: 1 / -1; font-size: 12px; margin-top: -.3rem; }
    .with-unit { display: flex; align-items: center; gap: .4rem; min-width: 0; }
    .with-unit .input { min-width: 0; }
    .unit { color: var(--text-muted); font-size: 12px; white-space: nowrap; }
    .foot { display: flex; justify-content: space-between; align-items: center; }
    .total { display: flex; gap: .6rem; align-items: baseline; }
    .empty-lines { display: flex; align-items: center; gap: .6rem; font-size: 13px; }
    @media (max-width: 980px) { .po-line { grid-template-columns: 1fr; } }
  `],
})
export class PurchaseLinesComponent {
  lines = input.required<PurchaseLine[]>();
  /** The chosen supplier's active items. */
  items = input.required<ItemResponse[]>();
  ingredients = input.required<IngredientResponse[]>();
  /** True until a supplier is chosen. */
  disabled = input(false);
  canCreateItem = input(false);
  /** Asks the parent to open the item form for this line; the parent sets the line's itemId once it is saved. */
  newItem = output<number>();
  readonly newItemValue = NEW_ITEM;

  itemOf(line: PurchaseLine) { return this.items().find((i) => i.id === line.itemId); }

  pick(idx: number, value: string) {
    if (value === NEW_ITEM) {
      this.lines()[idx].itemId = '';
      this.newItem.emit(idx);
      return;
    }
    this.lines()[idx].itemId = value;
  }

  unitLabel(line: PurchaseLine) { const it = this.itemOf(line); return it ? orderingUnit(it) : ''; }

  conversion(line: PurchaseLine) {
    const it = this.itemOf(line);
    if (!it) return '';
    const ing = this.ingredients().find((g) => g.id === it.ingredientId);
    return conversionLine(Number(line.quantity), Number(line.unitCost), it, ing?.baseUnit ?? '', ing?.name ?? '');
  }

  total() { return this.lines().reduce((s, l) => s + (Number(l.quantity) || 0) * (Number(l.unitCost) || 0), 0); }
  add() { this.lines().push(newPurchaseLine()); }
  remove(idx: number) { if (this.lines().length > 1) this.lines().splice(idx, 1); }
}
