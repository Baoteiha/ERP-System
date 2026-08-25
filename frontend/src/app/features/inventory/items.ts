import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { InventoryService } from '../../core/api.services';
import { AuthService } from '../../core/auth.service';
import { ConfirmService } from '../../core/confirm.service';
import { IngredientResponse, ItemRequest, ItemResponse, SupplierResponse } from '../../core/models';
import { ToastService } from '../../core/toast.service';
import { unitsFor } from '../../core/units';
import { ModalComponent } from '../../shared/modal';
import { IconComponent } from '../../shared/icon';

/**
 * Items — what each supplier sells, and which ingredient it becomes in stock.
 *
 * The screen exists to make one relationship legible: a purchase unit on the left, a
 * stock unit on the right, and the conversion between them stated out loud. The unit
 * picker only ever offers units of the ingredient's own dimension, so the mapping
 * error the server rejects is not reachable from here.
 */
@Component({
  selector: 'app-items',
  standalone: true,
  imports: [FormsModule, ModalComponent, IconComponent],
  template: `
    <div class="page">
      <div class="page-head">
        <div>
          <h1>Items</h1>
          <div class="sub">What each supplier sells, and the ingredient it lands in.</div>
        </div>
        @if (canWrite) { <button class="btn btn-primary" (click)="openNew()"><app-icon name="plus" [size]="16" /> New item</button> }
      </div>

      @if (loading()) { <div class="card"><div class="loading-block"><span class="spinner"></span> Loading…</div></div> }
      @else if (!items().length) {
        <div class="card">
          <div class="empty">
            <div class="big"><app-icon name="truck" [size]="30" /></div>
            No items yet — add one for the first thing you order.
          </div>
        </div>
      }
      @else {
        <div class="card">
          <div class="filter-bar">
            <select class="select w-filter" [ngModel]="supplierFilter()" (ngModelChange)="supplierFilter.set($event)" aria-label="Filter by supplier">
              <option value="">All suppliers</option>
              @for (s of suppliers(); track s.id) { <option [value]="s.id">{{ s.name }}</option> }
            </select>
            <span class="soft count">{{ visible().length }} of {{ items().length }}</span>
          </div>
          <div class="table-wrap">
            <table class="data">
              <caption class="sr-only">Purchasable items</caption>
              <thead>
                <tr>
                  <th scope="col">Item</th>
                  <th scope="col">Supplier</th>
                  <th scope="col">Bought in</th>
                  <th scope="col">Becomes</th>
                  <th scope="col">Status</th>
                  <th scope="col"><span class="sr-only">Actions</span></th>
                </tr>
              </thead>
              <tbody>
                @for (i of visible(); track i.id) {
                  <tr>
                    <td>
                      <button type="button" class="row-open" [disabled]="!canWrite" (click)="openEdit(i)"><b>{{ i.name }}</b></button>
                      @if (i.sku) { <div class="soft mono">{{ i.sku }}</div> }
                    </td>
                    <td>{{ supplierName(i.supplierId) }}</td>
                    <td><span class="unit-chip">{{ i.unit }}</span></td>
                    <td>
                      {{ ingredientName(i.ingredientId) }}
                      <div class="soft conv"><span class="unit-chip ghost">{{ i.unit }}</span> → <span class="unit-chip ghost">{{ baseUnitOf(i.ingredientId) }}</span></div>
                    </td>
                    <td><span class="badge dot" [class]="i.active ? 'badge-green' : 'badge-gray'">{{ i.active ? 'Active' : 'Inactive' }}</span></td>
                    <td class="row-actions">
                      @if (canWrite) {
                        <button class="btn btn-sm btn-icon" (click)="remove(i)" [attr.aria-label]="'Delete ' + i.name"><app-icon name="trash" [size]="15" /></button>
                      }
                    </td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        </div>
      }
    </div>

    @if (editing()) {
      <app-modal [title]="form.id ? 'Edit item' : 'New item'" (close)="editing.set(null)">
        <div class="two-col">
          <div class="field">
            <label for="item-supplier">Supplier <span class="req" aria-hidden="true">*</span></label>
            <select id="item-supplier" class="select" [(ngModel)]="form.supplierId" [disabled]="!!form.id" required>
              <option value="" disabled>Choose a supplier</option>
              @for (s of suppliers(); track s.id) { <option [value]="s.id">{{ s.name }}</option> }
            </select>
          </div>
          <div class="field">
            <label for="item-ingredient">Ingredient <span class="req" aria-hidden="true">*</span></label>
            <select id="item-ingredient" class="select" [ngModel]="form.ingredientId" (ngModelChange)="onIngredientChange($event)" [disabled]="!!form.id" required>
              <option value="" disabled>Choose an ingredient</option>
              @for (i of ingredients(); track i.id) { <option [value]="i.id">{{ i.name }} ({{ i.baseUnit }})</option> }
            </select>
          </div>
        </div>
        @if (form.id) { <div class="hint pin">Supplier and ingredient are fixed — they define what this item is. Create a second item to point elsewhere.</div> }

        <div class="field"><label for="item-name">Name <span class="req" aria-hidden="true">*</span></label><input id="item-name" class="input" [(ngModel)]="form.name" placeholder="Arabica Dak Lak" required /></div>

        <div class="two-col">
          <div class="field">
            <label for="item-unit">Bought in <span class="req" aria-hidden="true">*</span></label>
            <select id="item-unit" class="select" [(ngModel)]="form.unit" [disabled]="!form.ingredientId" required>
              <option value="" disabled>Choose a unit</option>
              @for (u of availableUnits(); track u.value) { <option [value]="u.value">{{ u.label }}</option> }
            </select>
            @if (conversionHint()) { <div class="hint">{{ conversionHint() }}</div> }
          </div>
          <div class="field"><label for="item-sku">Supplier SKU</label><input id="item-sku" class="input" [(ngModel)]="form.sku" placeholder="Optional" maxlength="64" /></div>
        </div>

        <label class="checkbox"><input type="checkbox" [(ngModel)]="form.active" /> Active</label>
        <div footer>
          <button class="btn btn-outline" (click)="editing.set(null)">Cancel</button>
          <button class="btn btn-primary" (click)="save()" [disabled]="saving()">@if (saving()) { <span class="spinner"></span> } Save</button>
        </div>
      </app-modal>
    }
  `,
  styles: [`
    .filter-bar { display: flex; align-items: center; gap: .7rem; padding: 0 0 .8rem; }
    .w-filter { max-width: 240px; }
    .count { font-size: 12px; }

    /* A unit is a token, not prose — monospace keeps "kg" and "ml" scannable down a column. */
    .unit-chip {
      font-family: var(--font-mono); font-size: 11.5px; color: var(--text-muted);
      background: var(--surface-2); border: 1px solid var(--hairline);
      border-radius: 5px; padding: 1px .35rem;
    }
    .unit-chip.ghost { background: transparent; }
    .conv { display: flex; align-items: center; gap: .3rem; margin-top: .15rem; font-size: 11.5px; }
    .mono { font-family: var(--font-mono); font-size: 11.5px; }
    .hint.pin { margin: -.4rem 0 .8rem; font-size: 12px; color: var(--text-muted); }
  `],
})
export class ItemsComponent {
  auth = inject(AuthService);
  private api = inject(InventoryService);
  private toast = inject(ToastService);
  private confirm = inject(ConfirmService);

  items = signal<ItemResponse[]>([]);
  suppliers = signal<SupplierResponse[]>([]);
  ingredients = signal<IngredientResponse[]>([]);
  loading = signal(true);
  saving = signal(false);
  editing = signal<ItemResponse | null>(null);
  supplierFilter = signal('');
  form: { id?: string } & ItemRequest = { name: '', sku: '', supplierId: '', ingredientId: '', unit: '', active: true };

  visible = computed(() => {
    const supplierId = this.supplierFilter();
    return supplierId ? this.items().filter((i) => i.supplierId === supplierId) : this.items();
  });

  /** Only units of the ingredient's own dimension — kg for grams, never litres. */
  availableUnits = computed(() => unitsFor(this.baseUnitOf(this.form.ingredientId)));

  constructor() { this.load(); }

  get canWrite() { return this.auth.can('inventory:write'); }

  load() {
    this.loading.set(true);
    this.api.listSuppliers().subscribe({ next: (s) => this.suppliers.set(s) });
    this.api.listIngredients().subscribe({ next: (i) => this.ingredients.set(i) });
    this.api.listItems().subscribe({
      next: (items) => { this.items.set(items); this.loading.set(false); },
      error: () => this.loading.set(false),
    });
  }

  supplierName(id: string) { return this.suppliers().find((s) => s.id === id)?.name ?? '—'; }
  ingredientName(id: string) { return this.ingredients().find((i) => i.id === id)?.name ?? '—'; }
  baseUnitOf(id: string | undefined) { return this.ingredients().find((i) => i.id === id)?.baseUnit; }

  /** States the conversion in words, so a wrong pick is visible before it is saved. */
  conversionHint() {
    const base = this.baseUnitOf(this.form.ingredientId);
    if (!base || !this.form.unit) return '';
    return this.form.unit === base
      ? `Bought and counted in ${base} — no conversion.`
      : `Ordered in ${this.form.unit}, counted in ${base}. Receiving converts.`;
  }

  onIngredientChange(ingredientId: string) {
    this.form.ingredientId = ingredientId;
    // Default to the ingredient's own unit: always valid, and one click from the unit
    // most things are actually bought in.
    this.form.unit = this.baseUnitOf(ingredientId) ?? '';
  }

  openNew() {
    this.form = { name: '', sku: '', supplierId: this.supplierFilter(), ingredientId: '', unit: '', active: true };
    this.editing.set({} as ItemResponse);
  }

  openEdit(i: ItemResponse) {
    if (!this.canWrite) return;
    this.form = {
      id: i.id, name: i.name, sku: i.sku ?? '', supplierId: i.supplierId,
      ingredientId: i.ingredientId, unit: i.unit, active: i.active, version: i.version,
    };
    this.editing.set(i);
  }

  save() {
    if (!this.form.supplierId || !this.form.ingredientId || !this.form.name || !this.form.unit) {
      this.toast.error('Supplier, ingredient, name and unit are required');
      return;
    }
    this.saving.set(true);
    const { id, ...body } = this.form;
    const req = id ? this.api.updateItem(id, body) : this.api.createItem(body);
    req.subscribe({
      next: () => { this.saving.set(false); this.editing.set(null); this.toast.success('Item saved'); this.load(); },
      error: () => this.saving.set(false),
    });
  }

  async remove(i: ItemResponse) {
    const ok = await this.confirm.ask({ title: `Delete ${i.name}?`, danger: true, confirmText: 'Delete' });
    if (!ok) return;
    this.api.deleteItem(i.id).subscribe({ next: () => { this.toast.success('Deleted'); this.load(); } });
  }
}
