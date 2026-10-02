import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { CatalogService, InventoryService, OrganizationService } from '../../core/api.services';
import { AuthService } from '../../core/auth.service';
import { ToastService } from '../../core/toast.service';
import { ConfirmService } from '../../core/confirm.service';
import { ModalComponent } from '../../shared/modal';
import { IconComponent } from '../../shared/icon';
import { StatusBadgeComponent } from '../../shared/status-badge';
import { afterSave } from '../../core/save-handler';
import { MoneyPipe } from '../../core/util';
import { ProductEditorComponent } from './product-editor';
import {
  BranchResponse, CategoryResponse, IngredientResponse, ModifierGroupResponse, ProductResponse,
} from '../../core/models';

@Component({
  selector: 'app-products',
  standalone: true,
  imports: [FormsModule, ModalComponent, MoneyPipe, IconComponent, ProductEditorComponent, StatusBadgeComponent],
  template: `
    <div class="page">
      <div class="page-head">
        <div><h1>Products</h1><div class="sub">Menu items with pricing, recipes and modifier options.</div></div>
        @if (auth.can('catalog:write')) { <button class="btn btn-primary" (click)="openNew()"><app-icon name="plus" [size]="16" /> New product</button> }
      </div>

      @if (loading()) { <div class="card"><div class="loading-block"><span class="spinner"></span> Loading…</div></div> }
      @else if (!items().length) {
        <div class="card"><div class="empty"><div class="big"><app-icon name="bean" [size]="30" /></div>No products yet.</div></div>
      }
      @else {
        <div class="pill-row filter-row" role="group" aria-label="Filter by category">
          <button type="button" class="pill" [class.active]="activeFilter() === 'all'" [attr.aria-pressed]="activeFilter() === 'all'" (click)="filter.set('all')">All</button>
          @for (g of grouped(); track g.id) {
            <button type="button" class="pill" [class.active]="activeFilter() === g.id" [attr.aria-pressed]="activeFilter() === g.id" (click)="filter.set(g.id)">{{ g.name }}</button>
          }
        </div>
        <div class="card">
          <div class="table-wrap">
            <table class="data">
              <caption class="sr-only">Products</caption>
              <thead>
                <tr>
                  <th scope="col">Product</th><th scope="col">Category</th><th scope="col" class="num">Price</th>
                  <th scope="col">Status</th><th scope="col"><span class="sr-only">Actions</span></th>
                </tr>
              </thead>
              <tbody>
                @for (p of visible(); track p.id) {
                  <tr [class.muted]="!p.active">
                    <td>
                      @if (canWrite) { <button type="button" class="row-open" (click)="openManage(p)">{{ p.name }}</button> }
                      @else { <b>{{ p.name }}</b> }
                      <div class="sku">{{ p.sku }}</div>
                    </td>
                    <td>{{ categoryName(p.categoryId) }}</td>
                    <td class="num">{{ p.basePrice | money }}</td>
                    <td><app-status-badge size="xs" [active]="p.active" /></td>
                    <td>
                      @if (canWrite) {
                        <div class="row-actions">
                          <button type="button" class="btn btn-sm btn-ghost btn-icon" (click)="openManage(p)" [attr.aria-label]="'Edit ' + p.name"><app-icon name="edit" [size]="15" /></button>
                          <button type="button" class="btn btn-sm btn-ghost btn-icon" (click)="remove(p)" [attr.aria-label]="'Delete ' + p.name"><app-icon name="trash" [size]="15" /></button>
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

    <!-- Create -->
    @if (creating()) {
      <app-modal variant="drawer" title="New product" (close)="creating.set(false)">
        <form id="prod-create-form" (ngSubmit)="create()">
        <div class="field"><label for="prod-category">Category <span class="req" aria-hidden="true">*</span></label>
          <select id="prod-category" class="select" name="categoryId" [(ngModel)]="nf.categoryId" required>
            <option value="" disabled>Select…</option>
            @for (c of categories(); track c.id) { <option [value]="c.id">{{ c.name }}</option> }
          </select>
        </div>
        <div class="field"><label for="prod-name">Name <span class="req" aria-hidden="true">*</span></label><input id="prod-name" class="input" name="name" [(ngModel)]="nf.name" placeholder="Cappuccino" required /></div>
        <div class="two-col">
          <div class="field"><label for="prod-sku">SKU <span class="req" aria-hidden="true">*</span></label><input id="prod-sku" class="input" name="sku" [(ngModel)]="nf.sku" placeholder="CAP-01" required /></div>
          <div class="field"><label for="prod-price">Base price</label><input id="prod-price" class="input" type="number" min="0" name="basePrice" [(ngModel)]="nf.basePrice" /></div>
        </div>
        <div class="field"><label for="prod-desc">Description</label><textarea id="prod-desc" class="input" name="description" [(ngModel)]="nf.description"></textarea></div>
        </form>
        <div footer>
          <button type="button" class="btn btn-outline" (click)="creating.set(false)">Cancel</button>
          <button type="submit" form="prod-create-form" class="btn btn-primary" [disabled]="saving()">@if (saving()) { <span class="spinner"></span> } Create</button>
        </div>
      </app-modal>
    }

    <!-- Manage -->
    @if (managing(); as p) {
      <app-product-editor [product]="p" [categories]="categories()" [ingredients]="ingredients()"
                          [modifierGroups]="modifierGroups()" [branches]="branches()"
                          (closed)="managing.set(null)" (saved)="onSaved($event)" />
    }
  `,
  styles: [`
    .filter-row { margin-bottom: 1rem; }
    .sku { font-family: var(--font-mono); font-size: 11px; color: var(--text-muted); }
  `],
})
export class ProductsComponent {
  auth = inject(AuthService);
  private api = inject(CatalogService);
  private inv = inject(InventoryService);
  private org = inject(OrganizationService);
  private toast = inject(ToastService);
  private confirm = inject(ConfirmService);

  items = signal<ProductResponse[]>([]);
  categories = signal<CategoryResponse[]>([]);
  ingredients = signal<IngredientResponse[]>([]);
  modifierGroups = signal<ModifierGroupResponse[]>([]);
  branches = signal<BranchResponse[]>([]);
  /** Category filter: 'all' or a category id (also 'uncategorized'). */
  filter = signal<string>('all');
  loading = signal(true);
  saving = signal(false);

  creating = signal(false);
  managing = signal<ProductResponse | null>(null);

  nf = { categoryId: '', name: '', sku: '', basePrice: '0', description: '' };

  categoryName = (id: string) => this.categories().find((c) => c.id === id)?.name ?? 'Uncategorised';

  get canWrite() { return this.auth.can('catalog:write'); }

  /** Products grouped by category (catalog order), orphans last. */
  grouped = computed(() => {
    const cats = this.categories();
    const prods = this.items();
    const groups = cats
      .map((c) => ({ id: c.id, name: c.name, products: prods.filter((p) => p.categoryId === c.id) }))
      .filter((g) => g.products.length > 0);
    const known = new Set(cats.map((c) => c.id));
    const orphans = prods.filter((p) => !known.has(p.categoryId));
    if (orphans.length) groups.push({ id: 'uncategorized', name: 'Uncategorised', products: orphans });
    return groups;
  });

  /** Products matching the category filter. */
  /** The filter in effect: a category that has since vanished falls back to 'all'. */
  activeFilter = computed(() => (this.grouped().some((g) => g.id === this.filter()) ? this.filter() : 'all'));
  visible = computed(() => {
    const f = this.activeFilter();
    return f === 'all' ? this.grouped().flatMap((g) => g.products) : (this.grouped().find((g) => g.id === f)?.products ?? []);
  });

  constructor() {
    this.load();
    this.api.listCategories().subscribe({ next: (c) => this.categories.set(c) });
    this.api.listModifierGroups().subscribe({ next: (g) => this.modifierGroups.set(g) });
    if (this.auth.can('inventory:read')) this.inv.listIngredients().subscribe({ next: (i) => this.ingredients.set(i) });
    if (this.auth.can('branch:read')) this.org.listBranches().subscribe({ next: (p) => this.branches.set(p.content) });
  }

  load() {
    this.loading.set(true);
    this.api.listProducts().subscribe({
      next: (p) => { this.items.set(p.content); this.loading.set(false); },
      error: () => this.loading.set(false),
    });
  }

  openNew() { this.nf = { categoryId: '', name: '', sku: '', basePrice: '0', description: '' }; this.creating.set(true); }
  create() {
    if (!this.nf.categoryId || !this.nf.name || !this.nf.sku) { this.toast.error('Category, name and SKU are required'); return; }
    this.saving.set(true);
    this.api.createProduct({ ...this.nf, basePrice: String(this.nf.basePrice || '0') }).subscribe(
      afterSave(this.saving, () => { this.creating.set(false); this.toast.success('Product created'); this.load(); }));
  }

  openManage(p: ProductResponse) { this.managing.set(p); }
  /** The editor saved details or modifier groups: show the product as the server has it, and refresh the wall. */
  onSaved(p: ProductResponse) { this.managing.set(p); this.load(); }

  async remove(p: ProductResponse) {
    const ok = await this.confirm.ask({ title: `Delete ${p.name}?`, danger: true, confirmText: 'Delete' });
    if (!ok) return;
    this.api.deleteProduct(p.id).subscribe({ next: () => { this.toast.success('Deleted'); this.load(); } });
  }
}
