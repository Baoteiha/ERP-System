import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { InventoryService } from '../../core/api.services';
import { AuthService } from '../../core/auth.service';
import { ConfirmService } from '../../core/confirm.service';
import { CreatePurchaseOrderRequest, IngredientResponse, PoLineRequest, PurchaseOrderResponse, SupplierResponse } from '../../core/models';
import { ToastService } from '../../core/toast.service';
import { MoneyPipe, ShortIdPipe } from '../../core/util';
import { ModalComponent } from '../../shared/modal';

@Component({
  selector: 'app-purchase-orders',
  standalone: true,
  imports: [FormsModule, MoneyPipe, ShortIdPipe, ModalComponent],
  template: `
    <div class="page">
      <div class="page-head">
        <div><h1>Purchase orders</h1><div class="sub">Branch procurement workflow: draft, send, receive, cancel.</div></div>
        @if (auth.can('purchasing:write')) { <button class="btn btn-primary" (click)="openNew()">＋ New purchase order</button> }
      </div>

      <div class="split">
        <div class="card">
          @if (loading()) { <div class="loading-block"><span class="spinner"></span> Loading…</div> }
          @else {
            <div class="table-wrap">
              <table class="data">
                <thead><tr><th>PO</th><th>Supplier</th><th>Status</th><th>Lines</th><th>Total</th><th></th></tr></thead>
                <tbody>
                  @for (po of items(); track po.id) {
                    <tr (click)="select(po)" [class.row-selected]="selected()?.id === po.id">
                      <td><b>{{ po.id | shortId }}</b><div class="soft">{{ po.branchId | shortId }}</div></td>
                      <td>{{ supplierName(po.supplierId) }}</td>
                      <td><span class="badge" [class]="statusClass(po.status)">{{ po.status }}</span></td>
                      <td>{{ po.lines.length }}</td>
                      <td>{{ total(po) | money }}</td>
                      <td class="row-actions">
                        @if (auth.can('purchasing:write')) {
                          @if (po.status === 'DRAFT') { <button class="btn btn-sm btn-ghost" (click)="send(po); $event.stopPropagation()">Send</button> }
                          @if (po.status === 'SENT' || po.status === 'PARTIALLY_RECEIVED') { <button class="btn btn-sm btn-ghost" (click)="openReceive(po); $event.stopPropagation()">Receive</button> }
                          @if (po.status === 'DRAFT' || po.status === 'SENT') { <button class="btn btn-sm btn-ghost" (click)="cancel(po); $event.stopPropagation()">Cancel</button> }
                        }
                      </td>
                    </tr>
                  } @empty { <tr><td colspan="6"><div class="empty"><div class="big">🧾</div>No purchase orders yet</div></td></tr> }
                </tbody>
              </table>
            </div>
          }
        </div>

        <div class="card detail">
          @if (selected(); as po) {
            <div class="detail-title">
              <div><h2>{{ po.id | shortId }}</h2><div class="sub">{{ supplierName(po.supplierId) }}</div></div>
              <span class="badge" [class]="statusClass(po.status)">{{ po.status }}</span>
            </div>
            @if (po.note) { <div class="note">{{ po.note }}</div> }
            <div class="table-wrap">
              <table class="data compact">
                <thead><tr><th>Ingredient</th><th>Ordered</th><th>Received</th><th>Unit cost</th></tr></thead>
                <tbody>
                  @for (l of po.lines; track l.id) {
                    <tr>
                      <td>{{ ingredientName(l.ingredientId) }}</td>
                      <td>{{ l.orderedQty }}</td>
                      <td>{{ l.receivedQty }}</td>
                      <td>{{ l.unitCost | money }}</td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
          } @else {
            <div class="empty"><div class="big">📋</div>Select a purchase order</div>
          }
        </div>
      </div>
    </div>

    @if (creating()) {
      <app-modal title="New purchase order" (close)="creating.set(false)">
        <div class="field">
          <label>Supplier</label>
          <select class="input" [(ngModel)]="form.supplierId">
            <option value="">Select supplier</option>
            @for (s of suppliers(); track s.id) { <option [value]="s.id">{{ s.name }}</option> }
          </select>
        </div>
        <div class="field"><label>Note</label><input class="input" [(ngModel)]="form.note" /></div>
        <div class="line-editor">
          @for (line of form.lines; track $index; let idx = $index) {
            <div class="po-line">
              <select class="input" [(ngModel)]="line.ingredientId">
                <option value="">Ingredient</option>
                @for (i of ingredients(); track i.id) { <option [value]="i.id">{{ i.name }}</option> }
              </select>
              <input class="input" type="number" min="0" step="0.001" [(ngModel)]="line.orderedQty" placeholder="Qty" />
              <input class="input" type="number" min="0" step="1" [(ngModel)]="line.unitCost" placeholder="Unit cost" />
              <button class="btn btn-ghost" (click)="removeLine(idx)">×</button>
            </div>
          }
          <button class="btn btn-outline" (click)="addLine()">＋ Add line</button>
        </div>
        <div footer>
          <button class="btn btn-outline" (click)="creating.set(false)">Cancel</button>
          <button class="btn btn-primary" (click)="save()" [disabled]="saving()">@if (saving()) { <span class="spinner"></span> } Create</button>
        </div>
      </app-modal>
    }

    @if (receiving(); as po) {
      <app-modal [title]="'Receive ' + (po.id | shortId)" (close)="receiving.set(null)">
        <div class="line-editor">
          @for (r of receiveLines; track r.lineId) {
            <div class="po-line receive">
              <div><b>{{ lineIngredientName(r.lineId) }}</b><div class="soft">Remaining {{ remaining(r.lineId) }}</div></div>
              <input class="input" type="number" min="0" step="0.001" [(ngModel)]="r.receivedQty" placeholder="Received qty" />
              <input class="input" type="number" min="0" step="1" [(ngModel)]="r.unitCostOverride" placeholder="Override cost" />
            </div>
          }
        </div>
        <div footer>
          <button class="btn btn-outline" (click)="receiving.set(null)">Cancel</button>
          <button class="btn btn-primary" (click)="receive()" [disabled]="saving()">@if (saving()) { <span class="spinner"></span> } Receive</button>
        </div>
      </app-modal>
    }
  `,
  styles: [`
    .split { display: grid; grid-template-columns: minmax(0, 1fr) 420px; gap: 1rem; align-items: start; }
    .row-selected { background: #eef6ff; }
    .detail-title { display: flex; justify-content: space-between; align-items: start; gap: 1rem; margin-bottom: 1rem; }
    .detail h2 { margin: 0; font-size: 1.25rem; }
    .note { border-left: 3px solid var(--accent); padding: .6rem .75rem; background: #f7fbff; margin-bottom: .75rem; color: var(--ink-muted); }
    .compact th, .compact td { padding: .65rem .55rem; }
    .line-editor { display: grid; gap: .65rem; }
    .po-line { display: grid; grid-template-columns: minmax(180px, 1fr) 110px 130px 40px; gap: .5rem; align-items: center; }
    .po-line.receive { grid-template-columns: 1fr 130px 145px; }
    @media (max-width: 980px) { .split { grid-template-columns: 1fr; } .po-line, .po-line.receive { grid-template-columns: 1fr; } }
  `],
})
export class PurchaseOrdersComponent {
  auth = inject(AuthService);
  private api = inject(InventoryService);
  private toast = inject(ToastService);
  private confirm = inject(ConfirmService);

  items = signal<PurchaseOrderResponse[]>([]);
  ingredients = signal<IngredientResponse[]>([]);
  suppliers = signal<SupplierResponse[]>([]);
  loading = signal(true);
  saving = signal(false);
  selected = signal<PurchaseOrderResponse | null>(null);
  creating = signal(false);
  receiving = signal<PurchaseOrderResponse | null>(null);
  form: CreatePurchaseOrderRequest = { supplierId: '', note: '', lines: [{ ingredientId: '', orderedQty: '', unitCost: '' }] };
  receiveLines: { lineId: string; receivedQty: string; unitCostOverride?: string | null }[] = [];

  selectedTotal = computed(() => this.selected() ? this.total(this.selected()!) : '0');

  constructor() { this.load(); }

  load() {
    this.loading.set(true);
    this.api.listIngredients().subscribe({ next: (i) => this.ingredients.set(i) });
    this.api.listSuppliers().subscribe({ next: (s) => this.suppliers.set(s) });
    this.api.listPurchaseOrders().subscribe({
      next: (page) => { this.items.set(page.content); this.loading.set(false); if (!this.selected() && page.content[0]) this.selected.set(page.content[0]); },
      error: () => this.loading.set(false),
    });
  }

  openNew() { this.form = { supplierId: '', note: '', lines: [{ ingredientId: '', orderedQty: '', unitCost: '' }] }; this.creating.set(true); }
  addLine() { this.form.lines.push({ ingredientId: '', orderedQty: '', unitCost: '' }); }
  removeLine(idx: number) { if (this.form.lines.length > 1) this.form.lines.splice(idx, 1); }
  select(po: PurchaseOrderResponse) { this.selected.set(po); }

  supplierName(id: string) { return this.suppliers().find((s) => s.id === id)?.name ?? id.slice(0, 8); }
  ingredientName(id: string) { return this.ingredients().find((i) => i.id === id)?.name ?? id.slice(0, 8); }
  lineIngredientName(lineId: string) {
    const line = this.receiving()?.lines.find((l) => l.id === lineId);
    return line ? this.ingredientName(line.ingredientId) : lineId.slice(0, 8);
  }
  remaining(lineId: string) {
    const line = this.receiving()?.lines.find((l) => l.id === lineId);
    return line ? Math.max(0, Number(line.orderedQty) - Number(line.receivedQty)).toString() : '0';
  }
  total(po: PurchaseOrderResponse) {
    return po.lines.reduce((sum, line) => sum + Number(line.orderedQty) * Number(line.unitCost.amount), 0).toString();
  }
  statusClass(status: string) {
    if (status === 'RECEIVED') return 'badge-green';
    if (status === 'CANCELLED') return 'badge-red';
    if (status === 'SENT' || status === 'PARTIALLY_RECEIVED') return 'badge-blue';
    return 'badge-gray';
  }

  save() {
    if (!this.form.supplierId || this.form.lines.some((l: PoLineRequest) => !l.ingredientId || !l.orderedQty || !l.unitCost)) {
      this.toast.error('Supplier and all line fields are required');
      return;
    }
    this.saving.set(true);
    this.api.createPurchaseOrder(this.form).subscribe({
      next: (po) => { this.saving.set(false); this.creating.set(false); this.toast.success('Purchase order created'); this.load(); this.selected.set(po); },
      error: () => this.saving.set(false),
    });
  }

  send(po: PurchaseOrderResponse) {
    this.api.sendPurchaseOrder(po.id).subscribe({ next: (updated) => { this.toast.success('Purchase order sent'); this.selected.set(updated); this.load(); } });
  }

  async cancel(po: PurchaseOrderResponse) {
    const ok = await this.confirm.ask({ title: `Cancel ${po.id.slice(0, 8)}?`, confirmText: 'Cancel PO', danger: true });
    if (!ok) return;
    this.api.cancelPurchaseOrder(po.id).subscribe({ next: (updated) => { this.toast.success('Purchase order cancelled'); this.selected.set(updated); this.load(); } });
  }

  openReceive(po: PurchaseOrderResponse) {
    this.receiving.set(po);
    this.receiveLines = po.lines.map((l) => ({ lineId: l.id, receivedQty: Math.max(0, Number(l.orderedQty) - Number(l.receivedQty)).toString(), unitCostOverride: '' }));
  }

  receive() {
    const po = this.receiving();
    if (!po) return;
    this.saving.set(true);
    const receipts = this.receiveLines.filter((r) => Number(r.receivedQty) > 0).map((r) => ({ ...r, unitCostOverride: r.unitCostOverride || null }));
    this.api.receivePurchaseOrder(po.id, { receipts }).subscribe({
      next: (updated) => { this.saving.set(false); this.receiving.set(null); this.selected.set(updated); this.toast.success('Stock received'); this.load(); },
      error: () => this.saving.set(false),
    });
  }
}
