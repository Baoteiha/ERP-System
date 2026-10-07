import { DatePipe } from '@angular/common';
import { Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { InventoryService } from '../../core/api.services';
import { AuthService } from '../../core/auth.service';
import { ConfirmService } from '../../core/confirm.service';
import { GoodsReceiptLineResponse, IngredientResponse, ItemResponse, PoLineRequest, PoLineResponse, PurchaseOrderResponse, SupplierResponse } from '../../core/models';
import { ToastService } from '../../core/toast.service';
import { afterSave } from '../../core/save-handler';
import { LabelPipe, MoneyPipe, QtyPipe, ShortIdPipe, formatMoney } from '../../core/util';
import { ModalComponent } from '../../shared/modal';
import { IconComponent } from '../../shared/icon';
import { ItemFormComponent } from './item-form';
import { CompensationDialogComponent } from './compensation-dialog';
import { ReceiveDrawerComponent } from './receive-drawer';
import { conversionLine, orderingUnit } from './purchase-math';
import { PurchaseLine, PurchaseLinesComponent, newPurchaseLine, purchaseLinesError } from './purchase-lines';

interface PoForm { supplierId: string; note: string; lines: PurchaseLine[]; }
const newForm = (): PoForm => ({ supplierId: '', note: '', lines: [newPurchaseLine()] });

@Component({
  selector: 'app-purchase-orders',
  standalone: true,
  imports: [FormsModule, LabelPipe, MoneyPipe, ShortIdPipe, ModalComponent, IconComponent, PurchaseLinesComponent, ItemFormComponent, ReceiveDrawerComponent, CompensationDialogComponent, QtyPipe, DatePipe],
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
                      <td><button type="button" class="row-open" (click)="select(po); $event.stopPropagation()">{{ po.id | shortId }}</button></td>
                      <td class="supplier-col">{{ supplierName(po.supplierId) }}</td>
                      <td><span class="badge dot" [class]="statusClass(po.status)">{{ po.status | label }}</span>@if (po.kind === 'RETAIL') { <span class="badge badge-gray">Retail</span> }</td>
                      <td class="num">{{ po.lines.length }}</td>
                      <td class="num">{{ total(po) | money }}</td>
                      <td class="actions-col">
                        <div class="row-actions">
                        @if (auth.can('purchasing:write')) {
                          @if (po.status === 'DRAFT') { <button class="btn btn-sm btn-ghost" [attr.aria-label]="'Send ' + (po.id | shortId)" (click)="send(po); $event.stopPropagation()" [disabled]="saving()">Send</button> }
                          @if (po.status === 'SENT' || po.status === 'PARTIALLY_RECEIVED') { <button class="btn btn-sm btn-ghost" [attr.aria-label]="'Receive ' + (po.id | shortId)" (click)="openReceive(po); $event.stopPropagation()">Receive</button> }
                          @if (po.status === 'DRAFT' || po.status === 'SENT' || po.status === 'PARTIALLY_RECEIVED') { <button class="btn btn-sm btn-ghost" [attr.aria-label]="'Cancel ' + (po.id | shortId)" (click)="cancel(po); $event.stopPropagation()">Cancel</button> }
                        }
                        </div>
                      </td>
                    </tr>
                  } @empty { <tr><td colspan="6"><div class="empty"><div class="big"><app-icon name="clipboard" [size]="30" /></div>No purchase orders yet</div></td></tr> }
                </tbody>
              </table>
            </div>
          }
        </div>

        <div class="card detail">
          @if (selected(); as po) {
            <div class="card-head">
              <div>
                <h2 class="po-id">{{ po.id | shortId }}</h2>
                <div class="sub">{{ supplierName(po.supplierId) }}</div>
              </div>
              <span class="badge dot" [class]="statusClass(po.status)">{{ po.status | label }}</span>
            </div>
            @if (po.note) { <div class="note">{{ po.note }}</div> }
            <div class="table-wrap">
              <table class="data compact">
                <caption class="sr-only">Purchase order lines</caption>
                <thead><tr><th scope="col">Item</th><th scope="col" class="right">Ordered</th><th scope="col" class="right">Received</th><th scope="col" class="right">Unit cost</th></tr></thead>
                <tbody>
                  @for (l of po.lines; track l.id) {
                    <tr>
                      <td>{{ lineName(l) }}@if (lineConversion(l); as c) { <div class="soft mini">{{ c }}</div> }</td>
                      <td class="num">{{ l.orderedQty | qty }} <span class="unit">{{ ordering(l) }}</span></td>
                      <td class="num">{{ l.receivedQty | qty }} <span class="unit">{{ ordering(l) }}</span></td>
                      <td class="num">{{ l.unitCost | money }} <span class="unit">/ {{ ordering(l) }}</span></td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
            <div class="card-pad po-total"><span class="soft">Total</span> <b class="num">{{ selectedTotal() | money }}</b></div>
            @if (po.receipts.length) {
              <div class="history">
                <div class="history-head">
                  <b>Receipt history</b>
                  @if (auth.can('purchasing:write') && po.status !== 'CANCELLED' && hasLoss(po)) {
                    <button class="btn btn-sm btn-ghost" (click)="replacing.set(po)">Receive replacement</button>
                  }
                </div>
                @for (r of po.receipts; track r.id) {
                  <div class="receipt" [class.highlight]="highlightReceipt() === r.id">
                    <div class="soft mini">{{ r.receivedAt | date:'medium' }} · {{ r.receivedBy || '—' }} · {{ r.kind === 'COMPENSATION' ? 'Replacement' : 'Delivery' }}</div>
                    @for (rl of r.lines; track rl.id) { <div class="mini">{{ receiptLineText(po, rl) }}</div> }
                    @if (r.note) { <div class="soft mini">“{{ r.note }}”</div> }
                  </div>
                }
              </div>
            }
          } @else {
            <div class="empty"><div class="big"><app-icon name="clipboard" [size]="30" /></div>Select a purchase order</div>
          }
        </div>
      </div>
    </div>

    @if (creating()) {
      <app-modal variant="drawer" [width]="720" title="New purchase order" (close)="creating.set(false)">
        <div class="field">
          <label for="po-supplier">Supplier <span class="req" aria-hidden="true">*</span></label>
          <div class="supplier-row">
            <select id="po-supplier" class="select" [ngModel]="form.supplierId" (ngModelChange)="onSupplierChange($event)" required>
              <option value="">Select supplier</option>
              @for (s of suppliers(); track s.id) { <option [value]="s.id">{{ s.name }}</option> }
            </select>
            @if (auth.can('inventory:write')) { <button type="button" class="btn btn-sm btn-ghost" (click)="addingSupplier.set(!addingSupplier())">+ New supplier</button> }
          </div>
          @if (addingSupplier()) {
            <div class="supplier-row">
              <input class="input" [(ngModel)]="newSupplierName" placeholder="Supplier name" aria-label="New supplier name" />
              <button type="button" class="btn btn-sm btn-primary" (click)="createSupplier()" [disabled]="!newSupplierName.trim()">Add</button>
            </div>
          }
        </div>
        <div class="field"><label for="po-note">Note</label><input id="po-note" class="input" [(ngModel)]="form.note" /></div>
        <app-purchase-lines [lines]="form.lines" [items]="supplierItems()" [ingredients]="ingredients()" [disabled]="!form.supplierId"
                            [canCreateItem]="auth.can('inventory:write')" (newItem)="itemFormFor.set($event)" />
        <div footer>
          <button class="btn btn-outline" (click)="creating.set(false)">Cancel</button>
          <button class="btn btn-primary" (click)="save()" [disabled]="saving()">@if (saving()) { <span class="spinner"></span> } Create</button>
        </div>
      </app-modal>
    }
    @if (itemFormFor() !== null) {
      <app-item-form [suppliers]="suppliers()" [ingredients]="ingredients()" [supplierId]="form.supplierId"
                     (saved)="onItemCreated($event)" (closed)="itemFormFor.set(null)" />
    }

    @if (receiving(); as po) {
      <app-receive-drawer [po]="po" [items]="allItems()" [ingredients]="ingredients()"
                          (received)="afterChange($event); receiving.set(null)" (closed)="receiving.set(null)" />
    }
    @if (replacing(); as po) {
      <app-compensation-dialog [po]="po" [items]="allItems()" [ingredients]="ingredients()"
                               (done)="afterChange($event); replacing.set(null)" (closed)="replacing.set(null)" />
    }
  `,
  styles: [`
    .split { display: grid; grid-template-columns: minmax(0, 1fr) 340px; gap: 1.15rem; align-items: start; }
    table.data tbody tr { cursor: pointer; }
    .detail .empty { min-height: 320px; }
    .row-selected, .row-selected:hover { background: var(--accent-soft); box-shadow: inset 3px 0 0 var(--accent); }
    .supplier-col { min-width: 160px; }
    .actions-col { white-space: nowrap; }
    .detail { position: sticky; top: calc(var(--topbar-h) + 1rem); }
    .po-id { margin: 0; font-size: 1.15rem; font-family: var(--font-mono); letter-spacing: -.03em; }
    .po-total { display: flex; justify-content: flex-end; align-items: baseline; gap: .8rem; border-top: 1px solid var(--border); }
    .note { padding: .6rem 1.35rem; border-bottom: 1px solid var(--border); color: var(--text-soft); font-size: 13.5px; }
    .with-unit { display: flex; align-items: center; gap: .4rem; min-width: 0; }
    .with-unit .input { min-width: 0; }
    .unit { color: var(--text-muted); font-size: 12px; white-space: nowrap; }
    .history { border-top: 1px solid var(--border); padding: .7rem 1.35rem; display: grid; gap: .55rem; }
    .history-head { display: flex; justify-content: space-between; align-items: center; }
    .receipt { display: grid; gap: .15rem; padding: .35rem .5rem; border-radius: var(--radius); }
    .receipt.highlight { background: var(--accent-soft); }
    .mini { font-size: 12px; }
    .compact th, .compact td { padding: .65rem .55rem; }
    .supplier-row { display: flex; gap: .5rem; align-items: center; margin-top: .4rem; }
    .line-editor { display: grid; gap: .65rem; }
    .po-line { display: grid; gap: .5rem; align-items: center; }
    @media (max-width: 980px) { .split { grid-template-columns: minmax(0, 1fr); } .po-line { grid-template-columns: 1fr; } }
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
  allItems = signal<ItemResponse[]>([]);
  form: PoForm = newForm();

  // the picked supplier's catalogue; the form re-reads it as the supplier changes
  private formSupplier = signal('');
  supplierItems = computed(() => this.allItems().filter((i) => i.active && i.supplierId === this.formSupplier()));

  selectedTotal = computed(() => this.selected() ? this.total(this.selected()!) : '0');

  /** `?new=1` from the command palette opens the create dialog */
  new = input<string>();

  private router = inject(Router);
  private canWrite = computed(() => this.auth.can('purchasing:write'));

  constructor() {
    // a link's intent is one-shot: act on it once identity is known, then drop it, permitted or not
    effect(() => {
      if (this.new() === '1' && this.auth.me()) {
        untracked(() => {
          if (this.canWrite()) this.openNew();
          void this.router.navigate([], { queryParams: { new: null }, queryParamsHandling: 'merge', replaceUrl: true });
        });
      }
    });
    // runs once at start and again whenever the till switches branch, so no other branch's orders linger
    effect(() => {
      this.auth.activeBranchId();
      untracked(() => { this.selected.set(null); this.items.set([]); this.load(); });
    });
  }

  load() {
    this.loading.set(true);
    this.api.listIngredients().subscribe({ next: (i) => this.ingredients.set(i) });
    this.api.listSuppliers().subscribe({ next: (s) => this.suppliers.set(s) });
    this.api.listItems().subscribe({ next: (i) => this.allItems.set(i) });
    const branch = this.auth.activeBranchId();
    this.api.listPurchaseOrders().subscribe({
      // a slow answer for a branch the till has since left must not overwrite the current one
      next: (page) => {
        if (this.auth.activeBranchId() !== branch) return;
        this.items.set(page.content); this.loading.set(false);
        if (!this.selected() && page.content[0]) this.selected.set(page.content[0]);
      },
      error: () => { if (this.auth.activeBranchId() === branch) this.loading.set(false); },
    });
  }

  openNew() { this.form = newForm(); this.formSupplier.set(''); this.creating.set(true); }
  itemFormFor = signal<number | null>(null);
  addingSupplier = signal(false);
  newSupplierName = '';

  onItemCreated(item: ItemResponse) {
    this.allItems.update((all) => [...all, item]);
    const idx = this.itemFormFor();
    if (idx !== null && this.form.lines[idx]) this.form.lines[idx].itemId = item.id;
    this.itemFormFor.set(null);
  }

  createSupplier() {
    const name = this.newSupplierName.trim();
    if (!name) return;
    this.api.createSupplier({ name }).subscribe({ next: (s) => {
      this.suppliers.update((all) => [...all, s]);
      this.newSupplierName = '';
      this.addingSupplier.set(false);
      this.onSupplierChange(s.id);
    } });
  }

  onSupplierChange(id: string) {
    this.form.supplierId = id;
    this.formSupplier.set(id);
    // an item belongs to one supplier, so lines picked from the previous one no longer apply
    this.form.lines = this.form.lines.map(() => newPurchaseLine());
  }
  select(po: PurchaseOrderResponse) { this.selected.set(po); }

  supplierName(id: string) { return this.suppliers().find((s) => s.id === id)?.name ?? id.slice(0, 8); }
  ingredientName(id: string) { return this.ingredients().find((i) => i.id === id)?.name ?? id.slice(0, 8); }
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
    const problem = !this.form.supplierId ? 'Choose a supplier' : purchaseLinesError(this.form.lines, this.supplierItems());
    if (problem) { this.toast.error(problem); return; }
    this.saving.set(true);
    const lines: PoLineRequest[] = this.form.lines.map((l) => ({ itemId: l.itemId, orderedQty: String(l.quantity), unitCost: String(l.unitCost) }));
    this.api.createPurchaseOrder({ supplierId: this.form.supplierId, note: this.form.note, lines })
      .subscribe(afterSave(this.saving, (po) => { this.creating.set(false); this.toast.success('Purchase order created'); this.load(); this.selected.set(po); }));
  }

  send(po: PurchaseOrderResponse) {
    if (this.saving()) return;
    this.saving.set(true);
    this.api.sendPurchaseOrder(po.id).subscribe(afterSave(this.saving, (updated) => { this.toast.success('Purchase order sent'); this.selected.set(updated); this.load(); }));
  }

  async cancel(po: PurchaseOrderResponse) {
    const ok = await this.confirm.ask({ title: `Cancel ${po.id.slice(0, 8)}?`, confirmText: 'Cancel PO', danger: true });
    if (!ok) return;
    this.api.cancelPurchaseOrder(po.id).subscribe({ next: (updated) => { this.toast.success('Purchase order cancelled'); this.selected.set(updated); this.load(); } });
  }

  replacing = signal<PurchaseOrderResponse | null>(null);
  highlightReceipt = signal<string | null>(null);

  openReceive(po: PurchaseOrderResponse) { this.receiving.set(po); }

  afterChange(updated: PurchaseOrderResponse) { this.selected.set(updated); this.load(); }

  ordering(l: PoLineResponse) { return orderingUnit(l); }
  lineName(l: PoLineResponse) { return this.allItems().find((i) => i.id === l.itemId)?.name ?? this.ingredientName(l.ingredientId); }
  lineConversion(l: PoLineResponse) {
    const ing = this.ingredients().find((g) => g.id === l.ingredientId);
    return l.packLabel ? conversionLine(Number(l.orderedQty), Number(l.unitCost.amount), l, ing?.baseUnit ?? '', ing?.name ?? '') : '';
  }
  hasLoss(po: PurchaseOrderResponse) {
    return po.receipts.some((r) => r.lines.some((l) => Number(l.lossQty) - Number(l.compensatedQty) > 0));
  }
  receiptLineText(po: PurchaseOrderResponse, rl: GoodsReceiptLineResponse) {
    const l = po.lines.find((x) => x.id === rl.purchaseOrderLineId);
    if (!l) return '';
    if (Number(rl.replacementQty) > 0) return `${this.lineName(l)}: replaced ${rl.replacementQty} ${l.unit}`;
    let s = `${this.lineName(l)}: delivered ${rl.deliveredQty} ${orderingUnit(l)}`;
    if (Number(rl.overDeliveredQty) > 0) s += ` (${rl.overDeliveredQty} extra, ${rl.overDeliveryKind === 'FREE' ? 'free' : 'charged'})`;
    if (Number(rl.damagedQty) > 0) s += ` · damaged ${rl.damagedQty} ${l.unit}`;
    if (Number(rl.lossQty) > 0) s += ` · loss ${formatMoney(rl.lossAmount)}${Number(rl.compensatedQty) > 0 ? ` (replaced ${rl.compensatedQty})` : ''}`;
    return s;
  }
}
