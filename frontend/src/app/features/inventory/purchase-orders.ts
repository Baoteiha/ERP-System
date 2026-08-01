import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { InventoryService } from '../../core/api.services';
import { AuthService } from '../../core/auth.service';
import { ConfirmService } from '../../core/confirm.service';
import { CreatePurchaseOrderRequest, IngredientResponse, PoLineRequest, PurchaseOrderResponse, SupplierResponse } from '../../core/models';
import { ToastService } from '../../core/toast.service';
import { MoneyPipe, ShortIdPipe } from '../../core/util';
import { ModalComponent } from '../../shared/modal';
import { IconComponent } from '../../shared/icon';

@Component({
  selector: 'app-purchase-orders',
  standalone: true,
  imports: [FormsModule, MoneyPipe, ShortIdPipe, ModalComponent, IconComponent],
  template: `
    <div class="page">
      <div class="page-head">
        <div><h1>Purchase orders</h1><div class="sub">Branch procurement workflow: draft, send, receive, cancel.</div></div>
        @if (auth.can('purchasing:write')) { <button class="btn btn-primary" (click)="openNew()"><app-icon name="plus" [size]="16" /> New purchase order</button> }
      </div>

      <div class="split">
        <div class="card">
          @if (loading()) { <div class="loading-block"><span class="spinner"></span> Loading…</div> }
          @else {
            <div class="table-wrap">
              <table class="data">
                <caption class="sr-only">Purchase orders</caption>
                <thead><tr><th scope="col">PO</th><th scope="col">Supplier</th><th scope="col">Status</th><th scope="col" class="right">Lines</th><th scope="col" class="right">Total</th><th scope="col"><span class="sr-only">Actions</span></th></tr></thead>
                <tbody>
                  @for (po of items(); track po.id) {
                    <tr (click)="select(po)" [class.row-selected]="selected()?.id === po.id">
                      <td><button type="button" class="row-open" (click)="select(po)"><b>{{ po.id | shortId }}</b></button><div class="soft">{{ po.branchId | shortId }}</div></td>
                      <td>{{ supplierName(po.supplierId) }}</td>
                      <td><span class="badge dot" [class]="statusClass(po.status)">{{ po.status }}</span></td>
                      <td class="num">{{ po.lines.length }}</td>
                      <td class="num">{{ total(po) | money }}</td>
                      <td class="row-actions">
                        @if (auth.can('purchasing:write')) {
                          @if (po.status === 'DRAFT') { <button class="btn btn-sm btn-ghost" (click)="send(po); $event.stopPropagation()" [disabled]="saving()">Send</button> }
                          @if (po.status === 'SENT' || po.status === 'PARTIALLY_RECEIVED') { <button class="btn btn-sm btn-ghost" (click)="openReceive(po); $event.stopPropagation()">Receive</button> }
                          @if (po.status === 'DRAFT' || po.status === 'SENT') { <button class="btn btn-sm btn-ghost" (click)="cancel(po); $event.stopPropagation()">Cancel</button> }
                        }
                      </td>
                    </tr>
                  } @empty { <tr><td colspan="6"><div class="empty"><div class="big"><app-icon name="clipboard" [size]="30" /></div>No purchase orders yet</div></td></tr> }
                </tbody>
              </table>
            </div>
          }
        </div>

        <div class="card detail">
          <div class="clip" aria-hidden="true"></div>
          @if (selected(); as po) {
            <div class="detail-title">
              <div>
                <div class="id-row">
                  <h2>{{ po.id | shortId }}</h2>
                  @if (stampColor(po.status); as c) { <span class="stamp stamp-in" [style.color]="c" aria-hidden="true">{{ po.status }}</span> }
                </div>
                <div class="sub">{{ supplierName(po.supplierId) }}</div>
              </div>
              <span class="badge dot" [class]="statusClass(po.status)">{{ po.status }}</span>
            </div>
            @if (po.note) { <div class="note">{{ po.note }}</div> }
            <div class="table-wrap">
              <table class="data compact">
                <caption class="sr-only">Purchase order lines</caption>
                <thead><tr><th scope="col">Ingredient</th><th scope="col" class="right">Ordered</th><th scope="col" class="right">Received</th><th scope="col" class="right">Unit cost</th></tr></thead>
                <tbody>
                  @for (l of po.lines; track l.id) {
                    <tr>
                      <td>{{ ingredientName(l.ingredientId) }}</td>
                      <td class="num">{{ l.orderedQty }}</td>
                      <td class="num">{{ l.receivedQty }}</td>
                      <td class="num">{{ l.unitCost | money }}</td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
          } @else {
            <div class="empty"><div class="big"><app-icon name="clipboard" [size]="30" /></div>Select a purchase order</div>
          }
        </div>
      </div>
    </div>

    @if (creating()) {
      <app-modal title="New purchase order" (close)="creating.set(false)">
        <div class="field">
          <label for="po-supplier">Supplier <span class="req" aria-hidden="true">*</span></label>
          <select id="po-supplier" class="select" [(ngModel)]="form.supplierId" required>
            <option value="">Select supplier</option>
            @for (s of suppliers(); track s.id) { <option [value]="s.id">{{ s.name }}</option> }
          </select>
        </div>
        <div class="field"><label for="po-note">Note</label><input id="po-note" class="input" [(ngModel)]="form.note" /></div>
        <div class="line-editor">
          @for (line of form.lines; track $index; let idx = $index) {
            <div class="po-line">
              <select class="select" [(ngModel)]="line.ingredientId" aria-label="Ingredient">
                <option value="">Ingredient</option>
                @for (i of ingredients(); track i.id) { <option [value]="i.id">{{ i.name }}</option> }
              </select>
              <input class="input" type="number" min="0" step="0.001" [(ngModel)]="line.orderedQty" placeholder="Qty" aria-label="Quantity" />
              <input class="input" type="number" min="0" step="1" [(ngModel)]="line.unitCost" placeholder="Unit cost" aria-label="Unit cost" />
              <button class="btn btn-ghost btn-icon" (click)="removeLine(idx)" aria-label="Remove line"><app-icon name="close" [size]="16" /></button>
            </div>
          }
          <button class="btn btn-outline" (click)="addLine()"><app-icon name="plus" [size]="15" /> Add line</button>
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
              <input class="input" type="number" min="0" step="0.001" [(ngModel)]="r.receivedQty" placeholder="Received qty" aria-label="Received qty" />
              <input class="input" type="number" min="0" step="1" [(ngModel)]="r.unitCostOverride" placeholder="Override cost" aria-label="Override cost" />
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
    .split { display: grid; grid-template-columns: minmax(0, 1fr) 420px; gap: 1.15rem; align-items: start; }
    table.data tbody tr { cursor: pointer; }
    .detail .empty { min-height: 320px; }
    .row-selected, .row-selected:hover { background: var(--azure-soft); box-shadow: inset 3px 0 0 var(--azure); }
    /* clipboard: paper sheet under a metal clip (sticky is a positioned anchor for .clip) */
    .detail { padding: 1.9rem 1.3rem 1.3rem; position: sticky; top: 0;
      background: linear-gradient(180deg, var(--paper), var(--paper-2));
      border-color: var(--paper-line); color: var(--paper-ink); }
    .detail .sub, .detail .soft, .detail .empty { color: var(--paper-ink-soft); }
    .detail .empty .big { background: rgba(255,255,255,.5); box-shadow: inset 0 0 0 1px var(--paper-line); }
    .detail table.data th { background: rgba(255,255,255,.4); color: var(--paper-ink-soft); border-bottom-color: var(--paper-line); }
    .detail table.data td { border-bottom-color: var(--paper-line); }
    .clip { position: absolute; top: -9px; left: 50%; transform: translateX(-50%); width: 92px; height: 18px; border-radius: 9px;
      background: linear-gradient(180deg, #d9d2c6, #a99f8e);
      box-shadow: inset 0 1px 0 rgba(255,255,255,.7), 0 1px 3px rgba(0,0,0,.3); }
    .clip::after { content: ""; position: absolute; left: 50%; top: 5px; transform: translateX(-50%); width: 54px; height: 7px; border-radius: 4px;
      background: linear-gradient(180deg, #c9c1b1, #978d7a); box-shadow: inset 0 1px 2px rgba(0,0,0,.28); }
    .detail-title { display: flex; justify-content: space-between; align-items: start; gap: 1rem; margin-bottom: 1rem; }
    .id-row { display: flex; align-items: center; gap: .6rem; flex-wrap: wrap; }
    .detail h2 { margin: 0; font-size: 1.3rem; font-family: var(--font-mono); letter-spacing: -.03em; color: var(--paper-ink); }
    /* pencilled margin note */
    .note { border-left: 3px solid var(--paper-line); padding: .5rem .8rem; margin-bottom: .85rem;
      font-style: italic; color: var(--paper-ink-soft); font-size: 14px; }
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
  /* rubber-stamp ink for terminal states only; null suppresses the stamp */
  stampColor(status: string): string | null {
    if (status === 'RECEIVED') return '#2e6b34';
    if (status === 'CANCELLED') return '#963120';
    return null;
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
    if (this.saving()) return;
    this.saving.set(true);
    this.api.sendPurchaseOrder(po.id).subscribe({
      next: (updated) => { this.saving.set(false); this.toast.success('Purchase order sent'); this.selected.set(updated); this.load(); },
      error: () => this.saving.set(false),
    });
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
