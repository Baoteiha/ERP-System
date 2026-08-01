import { DatePipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { InventoryService } from '../../core/api.services';
import { AuthService } from '../../core/auth.service';
import { IngredientResponse, MovementResponse, StockItemResponse } from '../../core/models';
import { ToastService } from '../../core/toast.service';
import { MoneyPipe, ShortIdPipe } from '../../core/util';
import { ModalComponent } from '../../shared/modal';
import { IconComponent } from '../../shared/icon';

type StockMode = 'adjust' | 'waste' | 'reorder';

@Component({
  selector: 'app-stock',
  standalone: true,
  imports: [FormsModule, DatePipe, MoneyPipe, ShortIdPipe, ModalComponent, IconComponent],
  template: `
    <div class="page">
      <div class="page-head">
        <div>
          <h1>Stock</h1>
          <div class="sub">On-hand quantities, reorder signals, and branch stock movements.</div>
        </div>
        <div class="segmented">
          <button [class.active]="view() === 'all'" (click)="view.set('all')">All</button>
          <button [class.active]="view() === 'low'" (click)="view.set('low')">Low stock</button>
        </div>
      </div>

      <div class="split">
        <div class="card">
          <div class="card-head">
            <div><b>{{ filtered().length }}</b> stock items</div>
            @if (auth.can('inventory:write')) {
              <div class="actions">
                <button class="btn btn-sm btn-outline" (click)="openAction('adjust')">Adjust</button>
                <button class="btn btn-sm btn-outline" (click)="openAction('waste')">Waste</button>
                <button class="btn btn-sm btn-outline" (click)="openAction('reorder')">Reorder level</button>
              </div>
            }
          </div>
          @if (loading()) { <div class="loading-block"><span class="spinner"></span> Loading…</div> }
          @else {
            <div class="table-wrap">
              <table class="data">
                <caption class="sr-only">Branch stock levels</caption>
                <thead><tr><th scope="col">Ingredient</th><th scope="col" class="right">On hand</th><th scope="col" class="right">Reorder</th><th scope="col" class="right">Avg cost</th><th scope="col">Status</th></tr></thead>
                <tbody>
                  @for (s of filtered(); track s.ingredientId + s.branchId) {
                    <tr (click)="selectIngredient(s.ingredientId)" [class.row-selected]="selectedIngredientId() === s.ingredientId" [class.row-low]="s.belowReorderLevel">
                      <td><button type="button" class="row-open" (click)="selectIngredient(s.ingredientId)"><b>{{ ingredientName(s.ingredientId) }}</b></button><div class="soft">{{ s.ingredientId | shortId }}</div></td>
                      <td class="num">{{ s.quantityOnHand }}</td>
                      <td class="num">{{ s.reorderLevel }}</td>
                      <td class="num">{{ s.avgUnitCost | money }}</td>
                      <td><span class="badge dot" [class]="s.belowReorderLevel ? 'badge-red' : 'badge-green'">@if (s.belowReorderLevel) { <app-icon name="boxes" [size]="13" aria-hidden="true" /> }{{ s.belowReorderLevel ? 'Below reorder' : 'Healthy' }}</span></td>
                    </tr>
                  } @empty { <tr><td colspan="5"><div class="empty"><div class="big"><app-icon name="box" [size]="30" /></div>No stock records yet</div></td></tr> }
                </tbody>
              </table>
            </div>
          }
        </div>

        <div class="card movement-card">
          <div class="card-head">
            <div><b>Movements</b><div class="sub mini">{{ selectedIngredientId() ? ingredientName(selectedIngredientId()!) : 'All ingredients' }}</div></div>
            @if (selectedIngredientId()) { <button class="btn btn-sm btn-ghost" (click)="selectedIngredientId.set(null); loadMovements()">Clear</button> }
          </div>
          @if (movementsLoading()) { <div class="loading-block"><span class="spinner"></span> Loading…</div> }
          @else {
            <div class="timeline">
              @for (m of movements(); track m.id) {
                <div class="movement">
                  <div class="pin" [class]="pinClass(m.type)" aria-hidden="true"><app-icon [name]="pinIcon(m.type)" [size]="13" /></div>
                  <div>
                    <div><b>{{ m.type }}</b> <span class="soft">{{ m.quantity }}</span></div>
                    <div class="soft">{{ ingredientName(m.ingredientId) }} · {{ m.occurredAt | date:'medium' }}</div>
                    @if (m.note) { <div class="note">{{ m.note }}</div> }
                  </div>
                  <div class="amount">{{ m.unitCost | money }}</div>
                </div>
              } @empty { <div class="empty"><div class="big"><app-icon name="receipt" [size]="30" /></div>No stock movements</div> }
            </div>
          }
        </div>
      </div>
    </div>

    @if (mode()) {
      <app-modal [title]="modalTitle()" (close)="mode.set(null)">
        <div class="field">
          <label for="stock-ingredient">Ingredient <span class="req" aria-hidden="true">*</span></label>
          <select id="stock-ingredient" class="select" [(ngModel)]="action.ingredientId" required>
            <option value="">Select ingredient</option>
            @for (i of ingredients(); track i.id) { <option [value]="i.id">{{ i.name }} ({{ i.baseUnit }})</option> }
          </select>
        </div>
        @if (mode() === 'adjust') {
          <div class="field"><label for="stock-qty-delta">Quantity delta</label><input id="stock-qty-delta" class="input" type="number" step="0.001" [(ngModel)]="action.quantityDelta" placeholder="-2.5 or 10" /></div>
          <div class="field"><label for="stock-note-adjust">Note</label><input id="stock-note-adjust" class="input" [(ngModel)]="action.note" /></div>
        }
        @if (mode() === 'waste') {
          <div class="field"><label for="stock-qty-waste">Quantity wasted</label><input id="stock-qty-waste" class="input" type="number" min="0" step="0.001" [(ngModel)]="action.quantity" /></div>
          <div class="field"><label for="stock-note-waste">Note</label><input id="stock-note-waste" class="input" [(ngModel)]="action.note" /></div>
        }
        @if (mode() === 'reorder') {
          <div class="field"><label for="stock-reorder">Reorder level</label><input id="stock-reorder" class="input" type="number" min="0" step="0.001" [(ngModel)]="action.reorderLevel" /></div>
        }
        <div footer>
          <button class="btn btn-outline" (click)="mode.set(null)">Cancel</button>
          <button class="btn btn-primary" (click)="saveAction()" [disabled]="saving()">@if (saving()) { <span class="spinner"></span> } Save</button>
        </div>
      </app-modal>
    }
  `,
  styles: [`
    /* stretch so both panels match heights and their empty states align */
    .split { display: grid; grid-template-columns: minmax(0, 1.15fr) minmax(360px, .85fr); gap: 1rem; align-items: stretch; }
    .movement-card { display: flex; flex-direction: column; }
    .timeline { flex: 1; }
    .timeline .empty { height: 100%; }
    .card-head { display: flex; justify-content: space-between; align-items: center; gap: 1rem; margin-bottom: .75rem; }
    .mini { font-size: .8rem; margin-top: .15rem; }
    table.data tbody tr { cursor: pointer; }
    .row-selected, .row-selected:hover { background: var(--azure-soft); box-shadow: inset 3px 0 0 var(--azure); }
    .row-low:not(.row-selected) td { background: color-mix(in srgb, var(--red-soft) 55%, transparent); }
    .movement-card { padding: 1.2rem; }
    .movement-card .card-head { padding: 0 0 1rem; margin-bottom: 0; }
    .timeline { display: grid; gap: .9rem; padding-top: .95rem; border-top: 1px solid var(--hairline); }
    .movement { position: relative; display: grid; grid-template-columns: 22px 1fr auto; gap: .75rem; align-items: start; padding-bottom: .9rem; border-bottom: 1px solid var(--hairline); }
    .movement::before { content: ""; position: absolute; left: 10.5px; top: 26px; bottom: -8px; border-left: 1px dashed var(--border-strong); }
    .movement:last-child::before { display: none; }
    /* icon coin per movement type */
    .pin { position: relative; width: 22px; height: 22px; border-radius: 50%; margin-top: .1rem; display: inline-flex; align-items: center; justify-content: center; }
    .pin-green { background: var(--green-soft); color: var(--green); }
    .pin-red   { background: var(--red-soft);   color: var(--red); }
    .pin-amber { background: var(--amber-soft); color: var(--amber); }
    .pin-azure { background: var(--azure-soft); color: var(--azure); }
    .pin-gray  { background: var(--surface-3);  color: var(--text-soft); }
    .note { margin-top: .25rem; font-size: .85rem; color: var(--text-soft); }
    .amount { font-family: var(--font-mono); font-variant-numeric: tabular-nums; font-weight: 600; white-space: nowrap; }
    @media (max-width: 980px) { .split { grid-template-columns: 1fr; } }
  `],
})
export class StockComponent {
  auth = inject(AuthService);
  private api = inject(InventoryService);
  private toast = inject(ToastService);

  stock = signal<StockItemResponse[]>([]);
  ingredients = signal<IngredientResponse[]>([]);
  movements = signal<MovementResponse[]>([]);
  selectedIngredientId = signal<string | null>(null);
  loading = signal(true);
  movementsLoading = signal(true);
  saving = signal(false);
  view = signal<'all' | 'low'>('all');
  mode = signal<StockMode | null>(null);
  action = { ingredientId: '', quantityDelta: '', quantity: '', reorderLevel: '', note: '' };

  filtered = computed(() => this.view() === 'low' ? this.stock().filter((s) => s.belowReorderLevel) : this.stock());

  constructor() {
    this.load();
    this.loadMovements();
  }

  load() {
    this.loading.set(true);
    this.api.listIngredients().subscribe({ next: (items) => this.ingredients.set(items) });
    this.api.listStock().subscribe({
      next: (items) => { this.stock.set(items); this.loading.set(false); },
      error: () => this.loading.set(false),
    });
  }

  loadMovements() {
    this.movementsLoading.set(true);
    this.api.movements(this.selectedIngredientId() ?? undefined).subscribe({
      next: (page) => { this.movements.set(page.content); this.movementsLoading.set(false); },
      error: () => this.movementsLoading.set(false),
    });
  }

  ingredientName(id: string) { return this.ingredients().find((i) => i.id === id)?.name ?? id.slice(0, 8); }
  selectIngredient(id: string) { this.selectedIngredientId.set(id); this.loadMovements(); }

  /* decorative icon coin per movement type (type text is displayed alongside) */
  pinIcon(type: string) {
    const t = (type || '').toUpperCase();
    if (t === 'RECEIVE' || t === 'PURCHASE') return 'truck';
    if (t === 'WASTE') return 'trash';
    if (t === 'ADJUST' || t === 'ADJUSTMENT') return 'sliders';
    if (t === 'SALE' || t === 'CONSUMPTION') return 'receipt';
    return 'box';
  }
  pinClass(type: string) {
    const t = (type || '').toUpperCase();
    if (t === 'RECEIVE' || t === 'PURCHASE') return 'pin-green';
    if (t === 'WASTE') return 'pin-red';
    if (t === 'ADJUST' || t === 'ADJUSTMENT') return 'pin-amber';
    if (t === 'SALE' || t === 'CONSUMPTION') return 'pin-azure';
    return 'pin-gray';
  }

  openAction(mode: StockMode) {
    this.mode.set(mode);
    this.action = { ingredientId: this.selectedIngredientId() ?? '', quantityDelta: '', quantity: '', reorderLevel: '', note: '' };
  }

  modalTitle() {
    if (this.mode() === 'adjust') return 'Adjust stock';
    if (this.mode() === 'waste') return 'Record waste';
    return 'Set reorder level';
  }

  saveAction() {
    if (!this.action.ingredientId) { this.toast.error('Select an ingredient'); return; }
    this.saving.set(true);
    const mode = this.mode();
    const req =
      mode === 'adjust' ? this.api.adjust({ ingredientId: this.action.ingredientId, quantityDelta: this.action.quantityDelta, note: this.action.note }) :
      mode === 'waste' ? this.api.waste({ ingredientId: this.action.ingredientId, quantity: this.action.quantity, note: this.action.note }) :
      this.api.setReorderLevel({ ingredientId: this.action.ingredientId, reorderLevel: this.action.reorderLevel });
    req.subscribe({
      next: () => { this.saving.set(false); this.mode.set(null); this.toast.success('Stock updated'); this.load(); this.loadMovements(); },
      error: () => this.saving.set(false),
    });
  }
}
