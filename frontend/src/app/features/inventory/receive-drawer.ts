import { Component, OnInit, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { InventoryService } from '../../core/api.services';
import { IngredientResponse, ItemResponse, PoLineResponse, PurchaseOrderResponse } from '../../core/models';
import { ToastService } from '../../core/toast.service';
import { afterSave } from '../../core/save-handler';
import { convertQty, convertRate } from '../../core/units';
import { formatMoney, ShortIdPipe } from '../../core/util';
import { ModalComponent } from '../../shared/modal';
import { OverKind, fmtQty, fmtRate, orderingUnit, perOrdering, receiptSummary } from './purchase-math';

/** A number input bound with ngModel yields a number, or null when cleared, so the fields accept both. */
type Typed = string | number | null;
interface ReceiveRow { lineId: string; delivered: Typed; damaged: Typed; actualPrice: Typed; overKind: OverKind | null; note: string; }
const text = (v: Typed) => (v === null || v === undefined ? '' : String(v).trim());

/**
 * Receive drawer: count what arrived in packs, note the damaged units, decide on any extra, and see what
 * enters stock and what is lost before confirming. One confirmation makes one goods receipt.
 */
@Component({
  selector: 'app-receive-drawer',
  standalone: true,
  imports: [FormsModule, ModalComponent, ShortIdPipe],
  template: `
    <app-modal variant="drawer" [width]="760" [title]="'Receive ' + (po().id | shortId)" (close)="closed.emit()">
      <div class="rows">
        @for (r of rows; track r.lineId) {
          @if (line(r); as l) {
            <div class="row">
              <div class="row-head"><b>{{ name(l) }}</b><span class="soft">Outstanding: {{ outstanding(l) }} {{ ordering(l) }}</span></div>
              <div class="grid">
                <label class="with-unit">Delivered <input class="input" type="number" min="0" [step]="l.packLabel ? 1 : 0.001" [(ngModel)]="r.delivered" /><span class="unit">{{ ordering(l) }}</span></label>
                <label class="with-unit">Damaged <input class="input" type="number" min="0" step="any" [(ngModel)]="r.damaged" /><span class="unit">{{ l.unit }}</span></label>
                <label class="with-unit">Actual price <input class="input" type="number" min="0" step="1" [(ngModel)]="r.actualPrice" placeholder="Order price" /><span class="unit">/ {{ ordering(l) }}</span></label>
                <label class="with-unit">Note <input class="input" [(ngModel)]="r.note" maxlength="255" /></label>
              </div>
              @if (summary(r).over > 0) {
                <div class="extra" role="radiogroup" [attr.aria-label]="'Extra ' + ordering(l)">
                  <span>{{ fmt(summary(r).over) }} {{ ordering(l) }} extra:</span>
                  <label><input type="radio" [name]="'over-' + r.lineId" [checked]="r.overKind === 'CHARGED'" (change)="r.overKind = 'CHARGED'" /> Charged</label>
                  <label><input type="radio" [name]="'over-' + r.lineId" [checked]="r.overKind === 'FREE'" (change)="r.overKind = 'FREE'" /> Free gift (0 ₫)</label>
                </div>
              }
              @if (rowState(r) === 'skip-fully-received') { <div class="soft">Fully received</div> }
              @else if (rowState(r) === 'skip') { <div class="soft">Not in this delivery</div> }
              @else if (rowState(r) === 'error') { <div class="err">{{ summary(r).error }}</div> }
              @else {
                <div class="soft">→ {{ intoStockText(r) }}</div>
                @if (lossText(r); as loss) { <div class="loss">→ {{ loss }}</div> }
              }
            </div>
          }
        }
        <div class="field"><label for="receipt-note">Receipt note</label><input id="receipt-note" class="input" [(ngModel)]="note" maxlength="255" /></div>
      </div>
      <div footer>
        <button class="btn btn-outline" (click)="closed.emit()">Cancel</button>
        <button class="btn btn-primary" (click)="confirm()" [disabled]="saving()">@if (saving()) { <span class="spinner"></span> } Confirm receipt</button>
      </div>
    </app-modal>
  `,
  styles: [`
    .rows { display: grid; gap: 1rem; }
    .row { display: grid; gap: .45rem; padding-bottom: .8rem; border-bottom: 1px solid var(--border); }
    .row-head { display: flex; justify-content: space-between; gap: 1rem; }
    .grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: .5rem; }
    .with-unit { display: flex; align-items: center; gap: .4rem; font-size: 12.5px; }
    .with-unit .input { min-width: 0; }
    .unit { color: var(--text-muted); font-size: 12px; white-space: nowrap; }
    .extra { display: flex; gap: .9rem; align-items: center; font-size: 13px; }
    .err { color: var(--danger); font-size: 12.5px; }
    .loss { color: var(--warning, var(--danger)); font-size: 12.5px; }
    @media (max-width: 760px) { .grid { grid-template-columns: 1fr; } }
  `],
})
export class ReceiveDrawerComponent implements OnInit {
  po = input.required<PurchaseOrderResponse>();
  items = input.required<ItemResponse[]>();
  ingredients = input.required<IngredientResponse[]>();
  received = output<PurchaseOrderResponse>();
  closed = output<void>();

  private api = inject(InventoryService);
  private toast = inject(ToastService);
  saving = signal(false);
  rows: ReceiveRow[] = [];
  note = '';
  readonly fmt = fmtQty;

  ngOnInit() {
    this.rows = this.po().lines.map((l) => ({ lineId: l.id, delivered: String(Math.max(0, Number(l.orderedQty) - Number(l.receivedQty))), damaged: '0', actualPrice: '', overKind: null, note: '' }));
  }

  line(r: ReceiveRow) { return this.po().lines.find((l) => l.id === r.lineId); }
  ordering(l: PoLineResponse) { return orderingUnit(l); }
  outstanding(l: PoLineResponse) { return fmtQty(Math.max(0, Number(l.orderedQty) - Number(l.receivedQty))); }
  name(l: PoLineResponse) {
    return this.items().find((i) => i.id === l.itemId)?.name ?? this.ingredients().find((g) => g.id === l.ingredientId)?.name ?? l.ingredientId.slice(0, 8);
  }
  private baseOf(l: PoLineResponse) { return this.ingredients().find((g) => g.id === l.ingredientId)?.baseUnit ?? l.unit; }

  summary(r: ReceiveRow) {
    const l = this.line(r)!;
    return receiptSummary({
      delivered: Number(text(r.delivered)), outstanding: Number(l.orderedQty) - Number(l.receivedQty), damaged: Number(text(r.damaged)) || 0,
      overKind: r.overKind, costPerOrdering: text(r.actualPrice) !== '' ? Number(text(r.actualPrice)) : Number(l.unitCost.amount), perOrdering: perOrdering(l),
    });
  }

  /** What to show under a row: a muted skip note when nothing is delivered, else the error or the stock summary. */
  rowState(r: ReceiveRow): 'skip-fully-received' | 'skip' | 'error' | 'ok' {
    if (!(Number(text(r.delivered)) > 0)) {
      const l = this.line(r)!;
      return Number(l.orderedQty) - Number(l.receivedQty) <= 0 ? 'skip-fully-received' : 'skip';
    }
    return this.summary(r).error ? 'error' : 'ok';
  }

  intoStockText(r: ReceiveRow) {
    const l = this.line(r)!;
    const s = this.summary(r);
    const base = this.baseOf(l);
    let text = `Into stock: ${fmtQty(convertQty(s.chargedIntoStock, l.unit, base))} ${base} · ${fmtRate(convertRate(s.costPerUnit, l.unit, base))} ₫/${base}`;
    if (s.freeIntoStock > 0) text += ` + ${fmtQty(convertQty(s.freeIntoStock, l.unit, base))} ${base} free`;
    return text;
  }

  lossText(r: ReceiveRow) {
    const s = this.summary(r);
    if (!(s.lossQty > 0)) return '';
    return `Damaged on arrival: ${fmtQty(s.lossQty)} × ${fmtRate(s.costPerUnit)} = ${formatMoney(s.lossAmount)}`;
  }

  confirm() {
    const active = this.rows.filter((r) => Number(text(r.delivered)) > 0);
    if (!active.length) { this.toast.error('Enter what was delivered'); return; }
    const problem = active.map((r) => this.summary(r).error).find((e) => !!e);
    if (problem) { this.toast.error(problem); return; }
    this.saving.set(true);
    const receipts = active.map((r) => ({
      lineId: r.lineId, receivedQty: text(r.delivered), damagedQty: Number(text(r.damaged)) > 0 ? text(r.damaged) : null,
      unitCostOverride: text(r.actualPrice) !== '' ? text(r.actualPrice) : null,
      overDeliveryKind: this.summary(r).over > 0 ? r.overKind : null, note: r.note.trim() || null,
    }));
    this.api.receivePurchaseOrder(this.po().id, { note: this.note.trim() || null, receipts })
      .subscribe(afterSave(this.saving, (updated) => { this.toast.success('Receipt saved, stock updated'); this.received.emit(updated); }));
  }
}
