import { Component, OnInit, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { InventoryService } from '../../core/api.services';
import { GoodsReceiptLineResponse, IngredientResponse, ItemResponse, PurchaseOrderResponse } from '../../core/models';
import { ToastService } from '../../core/toast.service';
import { afterSave } from '../../core/save-handler';
import { ModalComponent } from '../../shared/modal';
import { fmtQty } from './purchase-math';

interface ReplacementRow { receiptLineId: string; name: string; unit: string; remaining: number; quantity: string | number | null; }
const text = (v: string | number | null) => (v === null || v === undefined ? '' : String(v).trim());

/** Receive replacement: bring in what a supplier sent for lost units, at those units' price, clearing the loss. */
@Component({
  selector: 'app-compensation-dialog',
  standalone: true,
  imports: [FormsModule, ModalComponent],
  template: `
    <app-modal title="Receive replacement" (close)="closed.emit()">
      @for (r of rows; track r.receiptLineId) {
        <div class="row">
          <span><b>{{ r.name }}</b> <span class="soft">· {{ fmt(r.remaining) }} {{ r.unit }} still lost</span></span>
          <label class="with-unit"><input class="input" type="number" min="0" [max]="r.remaining" step="any" [(ngModel)]="r.quantity" [attr.aria-label]="'Replacement for ' + r.name" /><span class="unit">{{ r.unit }}</span></label>
        </div>
      }
      <div class="field"><label for="comp-note">Note</label><input id="comp-note" class="input" [(ngModel)]="note" maxlength="255" /></div>
      <div footer>
        <button class="btn btn-outline" (click)="closed.emit()">Cancel</button>
        <button class="btn btn-primary" (click)="confirm()" [disabled]="saving()">Receive</button>
      </div>
    </app-modal>
  `,
  styles: [`
    .row { display: flex; justify-content: space-between; align-items: center; gap: 1rem; margin-bottom: .6rem; }
    .with-unit { display: flex; align-items: center; gap: .4rem; max-width: 160px; }
    .unit { color: var(--text-muted); font-size: 12px; }
  `],
})
export class CompensationDialogComponent implements OnInit {
  po = input.required<PurchaseOrderResponse>();
  items = input.required<ItemResponse[]>();
  ingredients = input.required<IngredientResponse[]>();
  done = output<PurchaseOrderResponse>();
  closed = output<void>();

  private api = inject(InventoryService);
  private toast = inject(ToastService);
  saving = signal(false);
  rows: ReplacementRow[] = [];
  note = '';
  readonly fmt = fmtQty;

  ngOnInit() {
    const lost: GoodsReceiptLineResponse[] = this.po().receipts.flatMap((r) => r.lines)
      .filter((l) => Number(l.lossQty) - Number(l.compensatedQty) > 0);
    this.rows = lost.map((l) => {
      const poLine = this.po().lines.find((pl) => pl.id === l.purchaseOrderLineId);
      const remaining = Number(l.lossQty) - Number(l.compensatedQty);
      const name = this.items().find((i) => i.id === poLine?.itemId)?.name
        ?? this.ingredients().find((g) => g.id === poLine?.ingredientId)?.name ?? '—';
      return { receiptLineId: l.id, name, unit: poLine?.unit ?? '', remaining, quantity: String(remaining) };
    });
  }

  confirm() {
    const chosen = this.rows.filter((r) => Number(text(r.quantity)) > 0);
    if (!chosen.length) { this.toast.error('Enter what the supplier sent'); return; }
    const over = chosen.find((r) => Number(text(r.quantity)) > r.remaining);
    if (over) { this.toast.error(`At most ${fmtQty(over.remaining)} ${over.unit} of ${over.name} is still lost`); return; }
    this.saving.set(true);
    this.api.compensatePurchaseOrder(this.po().id, { note: this.note.trim() || null, lines: chosen.map((r) => ({ receiptLineId: r.receiptLineId, quantity: text(r.quantity) })) })
      .subscribe(afterSave(this.saving, (updated) => { this.toast.success('Replacement received'); this.done.emit(updated); }));
  }
}
