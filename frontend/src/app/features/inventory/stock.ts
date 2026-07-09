import { DatePipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { InventoryService } from '../../core/api.services';
import { AuthService } from '../../core/auth.service';
import { IngredientResponse, MovementResponse, StockItemResponse } from '../../core/models';
import { ToastService } from '../../core/toast.service';
import { MoneyPipe, ShortIdPipe } from '../../core/util';
import { ModalComponent } from '../../shared/modal';

type StockMode = 'adjust' | 'waste' | 'reorder';

@Component({
  selector: 'app-stock',
  standalone: true,
  imports: [FormsModule, DatePipe, MoneyPipe, ShortIdPipe, ModalComponent],
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
                <thead><tr><th>Ingredient</th><th>On hand</th><th>Reorder</th><th>Avg cost</th><th>Status</th></tr></thead>
                <tbody>
                  @for (s of filtered(); track s.ingredientId + s.branchId) {
                    <tr (click)="selectIngredient(s.ingredientId)" [class.row-selected]="selectedIngredientId() === s.ingredientId">
                      <td><b>{{ ingredientName(s.ingredientId) }}</b><div class="soft">{{ s.ingredientId | shortId }}</div></td>
                      <td>{{ s.quantityOnHand }}</td>
                      <td>{{ s.reorderLevel }}</td>
                      <td>{{ s.avgUnitCost | money }}</td>
                      <td><span class="badge" [class]="s.belowReorderLevel ? 'badge-red' : 'badge-green'">{{ s.belowReorderLevel ? 'Below reorder' : 'Healthy' }}</span></td>
                    </tr>
                  } @empty { <tr><td colspan="5"><div class="empty"><div class="big">📊</div>No stock records yet</div></td></tr> }
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
            <div class="timeline">
              @for (m of movements(); track m.id) {
                <div class="movement">
                  <div class="pin"></div>
                  <div>
                    <div><b>{{ m.type }}</b> <span class="soft">{{ m.quantity }}</span></div>
                    <div class="soft">{{ ingredientName(m.ingredientId) }} · {{ m.occurredAt | date:'medium' }}</div>
                    @if (m.note) { <div class="note">{{ m.note }}</div> }
                  </div>
                  <div class="amount">{{ m.unitCost | money }}</div>
                </div>
              } @empty { <div class="empty"><div class="big">🧾</div>No stock movements</div> }
            </div>
          }
        </div>
      </div>
    </div>

    @if (mode()) {
      <app-modal [title]="modalTitle()" (close)="mode.set(null)">
        <div class="field">
          <label>Ingredient</label>
          <select class="input" [(ngModel)]="action.ingredientId">
            <option value="">Select ingredient</option>
            @for (i of ingredients(); track i.id) { <option [value]="i.id">{{ i.name }} ({{ i.baseUnit }})</option> }
          </select>
        </div>
        @if (mode() === 'adjust') {
          <div class="field"><label>Quantity delta</label><input class="input" type="number" step="0.001" [(ngModel)]="action.quantityDelta" placeholder="-2.5 or 10" /></div>
          <div class="field"><label>Note</label><input class="input" [(ngModel)]="action.note" /></div>
        }
        @if (mode() === 'waste') {
          <div class="field"><label>Quantity wasted</label><input class="input" type="number" min="0" step="0.001" [(ngModel)]="action.quantity" /></div>
          <div class="field"><label>Note</label><input class="input" [(ngModel)]="action.note" /></div>
        }
        @if (mode() === 'reorder') {
          <div class="field"><label>Reorder level</label><input class="input" type="number" min="0" step="0.001" [(ngModel)]="action.reorderLevel" /></div>
        }
        <div footer>
          <button class="btn btn-outline" (click)="mode.set(null)">Cancel</button>
          <button class="btn btn-primary" (click)="saveAction()" [disabled]="saving()">@if (saving()) { <span class="spinner"></span> } Save</button>
        </div>
      </app-modal>
    }
  `,
  styles: [`
    .split { display: grid; grid-template-columns: minmax(0, 1.15fr) minmax(360px, .85fr); gap: 1rem; align-items: start; }
    .card-head { display: flex; justify-content: space-between; align-items: center; gap: 1rem; margin-bottom: .75rem; }
    .mini { font-size: .8rem; margin-top: .15rem; }
    .row-selected { background: #eef6ff; }
    .timeline { display: grid; gap: .85rem; }
    .movement { display: grid; grid-template-columns: 12px 1fr auto; gap: .75rem; align-items: start; padding-bottom: .85rem; border-bottom: 1px solid var(--line); }
    .pin { width: 10px; height: 10px; border-radius: 50%; margin-top: .35rem; background: var(--accent); box-shadow: 0 0 0 4px rgba(17, 99, 171, .12); }
    .note { margin-top: .25rem; font-size: .85rem; color: var(--ink-muted); }
    .amount { font-weight: 700; white-space: nowrap; }
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
