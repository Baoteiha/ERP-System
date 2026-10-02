import { DatePipe } from '@angular/common';
import { Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { InventoryService } from '../../core/api.services';
import { AuthService } from '../../core/auth.service';
import { IngredientResponse, MovementResponse, MovementType, StockItemResponse } from '../../core/models';
import { ToastService } from '../../core/toast.service';
import { afterSave } from '../../core/save-handler';
import { unitsFor } from '../../core/units';
import { LabelPipe, MoneyPipe, QtyPipe, ShortIdPipe } from '../../core/util';
import { ModalComponent } from '../../shared/modal';
import { IconComponent } from '../../shared/icon';

type StockMode = 'adjust' | 'waste' | 'reorder';

@Component({
  selector: 'app-stock',
  standalone: true,
  imports: [FormsModule, DatePipe, LabelPipe, MoneyPipe, QtyPipe, ShortIdPipe, ModalComponent, IconComponent],
  template: `
    <div class="page">
      <div class="page-head">
        <div>
          <h1>Stock</h1>
          <div class="sub">On-hand quantities, reorder signals, and branch stock movements.</div>
        </div>
        <div class="segmented view-switch" role="group" aria-label="Stock view">
          <button type="button" class="active" aria-pressed="true">Table</button>
        </div>
      </div>

      <div class="split">
        <div class="card">
          <div class="card-head">
            <div class="head-left">
              <div><b>{{ filtered().length }}</b> stock items</div>
              <div class="segmented" role="group" aria-label="Stock filter">
                <button type="button" [class.active]="view() === 'all'" [attr.aria-pressed]="view() === 'all'" (click)="view.set('all')">All</button>
                <button type="button" [class.active]="view() === 'low'" [attr.aria-pressed]="view() === 'low'" (click)="view.set('low')">Low stock</button>
              </div>
            </div>
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
                    <tr (click)="selectIngredient(s.ingredientId)" [class.row-selected]="selectedIngredientId() === s.ingredientId">
                      <td><button type="button" class="row-open" (click)="selectIngredient(s.ingredientId)">{{ ingredientName(s.ingredientId) }}</button><div class="soft">{{ s.ingredientId | shortId }}</div></td>
                      <td class="num">{{ s.quantityOnHand | qty }} {{ baseUnitOf(s.ingredientId) }}</td>
                      <td class="num">{{ s.reorderLevel | qty }} {{ baseUnitOf(s.ingredientId) }}</td>
                      <td class="num">{{ s.avgUnitCost | money }}</td>
                      <td><span class="badge dot" [class]="s.belowReorderLevel ? 'badge-red' : 'badge-green'">@if (s.belowReorderLevel) { <app-icon name="boxes" [size]="13" aria-hidden="true" /> }{{ s.belowReorderLevel ? 'Below reorder' : 'Healthy' }}</span></td>
                    </tr>
                  } @empty { <tr><td colspan="5"><div class="empty"><div class="big"><app-icon name="box" [size]="30" /></div>No stock records yet</div></td></tr> }
                </tbody>
              </table>
            </div>
          }
        </div>

        <div class="card">
          <div class="card-head">
            <div><b>Movements</b><div class="sub mini">{{ selectedIngredientId() ? ingredientName(selectedIngredientId()!) : 'All ingredients' }}</div></div>
            @if (selectedIngredientId()) { <button class="btn btn-sm btn-ghost" (click)="selectedIngredientId.set(null); loadMovements()">Clear</button> }
          </div>
          @if (movementsLoading()) { <div class="loading-block"><span class="spinner"></span> Loading…</div> }
          @else {
            <ul class="movements">
              @for (m of movements(); track m.id) {
                <li class="movement">
                  <div class="mv-main">
                    <span class="badge" [class]="typeClass(m.type)"><app-icon [name]="pinIcon(m.type)" [size]="12" aria-hidden="true" />{{ m.type | label }}</span>
                    <span class="mv-ing">{{ ingredientName(m.ingredientId) }}</span>
                    <span class="spacer"></span>
                    <span class="num">{{ m.quantity | qty }} {{ baseUnitOf(m.ingredientId) }}</span>
                  </div>
                  <div class="soft mv-sub">
                    <span>{{ m.occurredAt | date:'medium' }}</span>
                    <span class="num">{{ m.unitCost | money }}</span>
                  </div>
                  @if (m.note) { <div class="note">{{ m.note }}</div> }
                </li>
              } @empty { <li class="empty"><div class="big"><app-icon name="receipt" [size]="30" /></div>No stock movements</li> }
            </ul>
          }
        </div>
      </div>
    </div>

    @if (mode()) {
      <app-modal variant="drawer" [title]="modalTitle()" (close)="mode.set(null)">
        <div class="field">
          <label for="stock-ingredient">Ingredient <span class="req" aria-hidden="true">*</span></label>
          <select id="stock-ingredient" class="select" [(ngModel)]="form.ingredientId" (ngModelChange)="onIngredientChange()" required>
            <option value="">Select ingredient</option>
            @for (i of ingredients(); track i.id) { <option [value]="i.id">{{ i.name }} ({{ i.baseUnit }})</option> }
          </select>
        </div>
        @if (mode() === 'adjust') {
          <div class="field"><label for="stock-qty-delta">Quantity delta</label><input id="stock-qty-delta" class="input" type="number" step="0.001" [(ngModel)]="form.quantityDelta" placeholder="-2.5 or 10" /></div>
          <div class="field"><label for="stock-unit-adjust">Unit</label>
            <select id="stock-unit-adjust" class="select" [(ngModel)]="form.unit">
              @for (u of unitOptions(); track u.value) { <option [value]="u.value">{{ u.label }}</option> }
            </select></div>
          <div class="field"><label for="stock-note-adjust">Note</label><input id="stock-note-adjust" class="input" [(ngModel)]="form.note" /></div>
        }
        @if (mode() === 'waste') {
          <div class="field"><label for="stock-qty-waste">Quantity wasted</label><input id="stock-qty-waste" class="input" type="number" min="0" step="0.001" [(ngModel)]="form.quantity" /></div>
          <div class="field"><label for="stock-unit-waste">Unit</label>
            <select id="stock-unit-waste" class="select" [(ngModel)]="form.unit">
              @for (u of unitOptions(); track u.value) { <option [value]="u.value">{{ u.label }}</option> }
            </select></div>
          <div class="field"><label for="stock-note-waste">Note</label><input id="stock-note-waste" class="input" [(ngModel)]="form.note" /></div>
        }
        @if (mode() === 'reorder') {
          <div class="field"><label for="stock-reorder">Reorder level</label><input id="stock-reorder" class="input" type="number" min="0" step="0.001" [(ngModel)]="form.reorderLevel" /></div>
        }
        <div footer>
          <button class="btn btn-outline" (click)="mode.set(null)">Cancel</button>
          <button class="btn btn-primary" (click)="saveAction()" [disabled]="saving()">@if (saving()) { <span class="spinner"></span> } Save</button>
        </div>
      </app-modal>
    }
  `,
  styles: [`
    .split { display: grid; grid-template-columns: minmax(0, 1.25fr) minmax(380px, .75fr); gap: 1rem; align-items: start; }
    .head-left { display: flex; align-items: center; gap: .9rem; flex-wrap: wrap; }
    .card-head .actions { display: flex; gap: .4rem; flex-wrap: wrap; }
    .mini { font-size: .8rem; margin-top: .15rem; }
    table.data tbody tr { cursor: pointer; }
    table.data td { white-space: nowrap; }
    table.data td:first-child { white-space: normal; }
    .row-selected, .row-selected:hover { background: var(--accent-soft); box-shadow: inset 3px 0 0 var(--accent); }
    .movements { list-style: none; margin: 0; padding: 0; }
    .movement { padding: .8rem 1.35rem; border-bottom: 1px solid var(--border); display: grid; gap: .3rem; }
    .movement:last-child { border-bottom: none; }
    .mv-main .badge, .mv-main .num { white-space: nowrap; }
    .mv-main { display: flex; align-items: center; gap: .6rem; min-width: 0; }
    .mv-ing { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .mv-sub { display: flex; justify-content: space-between; gap: .6rem; font-size: 12px; }
    .note { font-size: .85rem; color: var(--text-soft); }
    @media (max-width: 980px) { .split { grid-template-columns: minmax(0, 1fr); } }
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
  form = { ingredientId: '', quantityDelta: '', quantity: '', reorderLevel: '', unit: '', note: '' };

  filtered = computed(() => this.view() === 'low' ? this.stock().filter((s) => s.belowReorderLevel) : this.stock());

  /** `?action=adjust` from the command palette opens the adjust dialog */
  action = input<string>();

  private router = inject(Router);
  private canWrite = computed(() => this.auth.can('inventory:write'));

  constructor() {
    // a link's intent is one-shot: act on it once identity is known, then drop it, permitted or not
    effect(() => {
      if (this.action() === 'adjust' && this.auth.me()) {
        untracked(() => {
          if (this.canWrite()) this.openAction('adjust');
          void this.router.navigate([], { queryParams: { action: null }, queryParamsHandling: 'merge', replaceUrl: true });
        });
      }
    });
    // runs once at start and again whenever the till switches branch, so no other branch's rows linger
    effect(() => {
      this.auth.activeBranchId();
      untracked(() => {
        this.selectedIngredientId.set(null);
        // the old branch's rows must not stay on screen (or stay if the new request fails)
        this.stock.set([]);
        this.movements.set([]);
        this.load();
        this.loadMovements();
      });
    });
  }

  load() {
    this.loading.set(true);
    this.api.listIngredients().subscribe({ next: (items) => this.ingredients.set(items) });
    const branch = this.auth.activeBranchId();
    this.api.listStock().subscribe({
      // a slow answer for a branch the till has since left must not overwrite the current one
      next: (items) => { if (this.auth.activeBranchId() === branch) { this.stock.set(items); this.loading.set(false); } },
      error: () => { if (this.auth.activeBranchId() === branch) this.loading.set(false); },
    });
  }

  loadMovements() {
    this.movementsLoading.set(true);
    const branch = this.auth.activeBranchId();
    this.api.movements(this.selectedIngredientId() ?? undefined).subscribe({
      next: (page) => { if (this.auth.activeBranchId() === branch) { this.movements.set(page.content); this.movementsLoading.set(false); } },
      error: () => { if (this.auth.activeBranchId() === branch) this.movementsLoading.set(false); },
    });
  }

  ingredientName(id: string) { return this.ingredients().find((i) => i.id === id)?.name ?? id.slice(0, 8); }
  selectIngredient(id: string) { this.selectedIngredientId.set(id); this.loadMovements(); }

  /** Glyph shown inside the movement badge. */
  pinIcon(type: MovementType) {
    switch (type) {
      case 'PURCHASE_RECEIPT': return 'truck';
      case 'WASTE': return 'trash';
      case 'ADJUSTMENT': return 'sliders';
      case 'SALE_DEPLETION': case 'SALE_REVERSAL': return 'receipt';
      default: return 'box';
    }
  }
  /** Badge colour per movement type; the type text is always shown. */
  typeClass(type: MovementType) {
    switch (type) {
      case 'PURCHASE_RECEIPT': return 'badge-green';
      case 'WASTE': return 'badge-red';
      case 'ADJUSTMENT': return 'badge-amber';
      case 'SALE_DEPLETION': case 'SALE_REVERSAL': return 'badge-blue';
      default: return 'badge-gray';
    }
  }

  openAction(mode: StockMode) {
    this.mode.set(mode);
    this.form = { ingredientId: this.selectedIngredientId() ?? '', quantityDelta: '', quantity: '', reorderLevel: '', unit: '', note: '' };
    this.onIngredientChange();
  }

  /** Only units of the ingredient's own dimension; the base unit is the default. */
  unitOptions() { return unitsFor(this.baseUnitOf(this.form.ingredientId)); }
  onIngredientChange() { this.form.unit = this.baseUnitOf(this.form.ingredientId) ?? ''; }
  baseUnitOf(id: string) { return this.ingredients().find((i) => i.id === id)?.baseUnit; }

  modalTitle() {
    if (this.mode() === 'adjust') return 'Adjust stock';
    if (this.mode() === 'waste') return 'Record waste';
    return 'Set reorder level';
  }

  saveAction() {
    if (!this.form.ingredientId) { this.toast.error('Select an ingredient'); return; }
    this.saving.set(true);
    const mode = this.mode();
    const req =
      mode === 'adjust' ? this.api.adjust({ ingredientId: this.form.ingredientId, quantityDelta: this.form.quantityDelta, unit: this.form.unit || undefined, note: this.form.note }) :
      mode === 'waste' ? this.api.waste({ ingredientId: this.form.ingredientId, quantity: this.form.quantity, unit: this.form.unit || undefined, note: this.form.note }) :
      this.api.setReorderLevel({ ingredientId: this.form.ingredientId, reorderLevel: this.form.reorderLevel });
    req.subscribe(afterSave(this.saving, () => { this.mode.set(null); this.toast.success('Stock updated'); this.load(); this.loadMovements(); }));
  }
}
