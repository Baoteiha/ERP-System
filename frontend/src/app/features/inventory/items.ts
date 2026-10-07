import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { InventoryService } from '../../core/api.services';
import { AuthService } from '../../core/auth.service';
import { ConfirmService } from '../../core/confirm.service';
import { IngredientResponse, ItemResponse, SupplierResponse } from '../../core/models';
import { ToastService } from '../../core/toast.service';
import { QtyPipe } from '../../core/util';
import { IconComponent } from '../../shared/icon';
import { StatusBadgeComponent } from '../../shared/status-badge';
import { ItemFormComponent } from './item-form';

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
  imports: [FormsModule, IconComponent, StatusBadgeComponent, ItemFormComponent, QtyPipe],
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
                  <tr [class.muted]="!i.active">
                    <td>
                      <button type="button" class="row-open" [disabled]="!canWrite" (click)="openEdit(i)">{{ i.name }}</button>
                      @if (i.sku) { <div class="soft mono">{{ i.sku }}</div> }
                    </td>
                    <td>{{ supplierName(i.supplierId) }}</td>
                    <td>
                      @if (i.packLabel) { <span class="unit-chip">{{ i.packLabel }}</span> <span class="soft">({{ i.packSize | qty }} {{ i.unit }})</span> }
                      @else { <span class="unit-chip">{{ i.unit }}</span> }
                    </td>
                    <td>
                      {{ ingredientName(i.ingredientId) }}
                      <div class="soft conv"><span class="unit-chip ghost">{{ i.packLabel || i.unit }}</span> → <span class="unit-chip ghost">{{ baseUnitOf(i.ingredientId) }}</span></div>
                    </td>
                    <td><app-status-badge size="sm" [active]="i.active" /></td>
                    <td>
                      @if (canWrite) {
                        <div class="row-actions">
                          <button type="button" class="btn btn-sm btn-ghost btn-icon" (click)="openEdit(i)" [attr.aria-label]="'Edit ' + i.name"><app-icon name="edit" [size]="15" /></button>
                          <button type="button" class="btn btn-sm btn-ghost btn-icon" (click)="remove(i)" [attr.aria-label]="'Delete ' + i.name"><app-icon name="trash" [size]="15" /></button>
                        </div>
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

    @if (editing(); as e) {
      <app-item-form [suppliers]="suppliers()" [ingredients]="ingredients()" [item]="e.item" [supplierId]="e.item ? '' : supplierFilter()"
                     (saved)="editing.set(null); load()" (closed)="editing.set(null)" />
    }
  `,
  styles: [`
    .filter-bar { display: flex; align-items: center; gap: .7rem; padding: .8rem 1.35rem; border-bottom: 1px solid var(--border); }
    .w-filter { max-width: 240px; }
    .count { font-size: 12px; }

    .unit-chip.ghost { background: transparent; }
    .conv { display: flex; align-items: center; gap: .3rem; margin-top: .15rem; font-size: 11.5px; }
    .mono { font-family: var(--font-mono); font-size: 11.5px; }
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
  editing = signal<{ item: ItemResponse | null } | null>(null);
  supplierFilter = signal('');

  visible = computed(() => {
    const supplierId = this.supplierFilter();
    return supplierId ? this.items().filter((i) => i.supplierId === supplierId) : this.items();
  });

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

  openNew() { this.editing.set({ item: null }); }

  openEdit(i: ItemResponse) { if (this.canWrite) this.editing.set({ item: i }); }

  async remove(i: ItemResponse) {
    const ok = await this.confirm.ask({ title: `Delete ${i.name}?`, danger: true, confirmText: 'Delete' });
    if (!ok) return;
    this.api.deleteItem(i.id).subscribe({ next: () => { this.toast.success('Deleted'); this.load(); } });
  }
}
