import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { CatalogService, InventoryService, OrganizationService } from '../../core/api.services';
import { AuthService } from '../../core/auth.service';
import { ToastService } from '../../core/toast.service';
import { ConfirmService } from '../../core/confirm.service';
import { ModalComponent } from '../../shared/modal';
import { MoneyPipe } from '../../core/util';
import {
  BranchAvailabilityResponse, BranchResponse, CategoryResponse, ConsumptionLine,
  IngredientResponse, ModifierGroupResponse, ProductResponse,
} from '../../core/models';

type Tab = 'details' | 'recipe' | 'modifiers' | 'availability';

@Component({
  selector: 'app-products',
  standalone: true,
  imports: [FormsModule, ModalComponent, MoneyPipe],
  template: `
    <div class="page">
      <div class="page-head">
        <div><h1>Products</h1><div class="sub">Menu items with pricing, recipes and modifier options.</div></div>
        @if (auth.can('catalog:write')) { <button class="btn btn-primary" (click)="openNew()">＋ New product</button> }
      </div>

      <div class="card">
        @if (loading()) { <div class="loading-block"><span class="spinner"></span> Loading…</div> }
        @else {
          <div class="table-wrap">
            <table class="data">
              <thead><tr><th>Product</th><th>SKU</th><th>Category</th><th class="num">Base price</th><th>Status</th><th></th></tr></thead>
              <tbody>
                @for (p of items(); track p.id) {
                  <tr>
                    <td><b>{{ p.name }}</b>@if (p.description) { <div class="soft" style="font-size:12px">{{ p.description }}</div> }</td>
                    <td><span class="badge badge-gray">{{ p.sku }}</span></td>
                    <td class="soft">{{ categoryName(p.categoryId) }}</td>
                    <td class="num">{{ p.basePrice | money }}</td>
                    <td><span class="badge dot" [class]="p.active ? 'badge-green' : 'badge-gray'">{{ p.active ? 'Active' : 'Inactive' }}</span></td>
                    <td class="row-actions">
                      @if (auth.can('catalog:write')) {
                        <button class="btn btn-sm btn-outline" (click)="openManage(p)">Manage</button>
                        <button class="btn btn-sm btn-ghost" (click)="remove(p)">🗑</button>
                      }
                    </td>
                  </tr>
                } @empty { <tr><td colspan="6"><div class="empty"><div class="big">🥤</div>No products yet</div></td></tr> }
              </tbody>
            </table>
          </div>
        }
      </div>
    </div>

    <!-- Create -->
    @if (creating()) {
      <app-modal title="New product" (close)="creating.set(false)">
        <div class="field"><label>Category</label>
          <select class="select" [(ngModel)]="nf.categoryId">
            <option value="" disabled>Select…</option>
            @for (c of categories(); track c.id) { <option [value]="c.id">{{ c.name }}</option> }
          </select>
        </div>
        <div class="field"><label>Name</label><input class="input" [(ngModel)]="nf.name" placeholder="Cappuccino" /></div>
        <div class="two-col">
          <div class="field"><label>SKU</label><input class="input" [(ngModel)]="nf.sku" placeholder="CAP-01" /></div>
          <div class="field"><label>Base price</label><input class="input" type="number" min="0" [(ngModel)]="nf.basePrice" /></div>
        </div>
        <div class="field"><label>Description</label><textarea class="input" [(ngModel)]="nf.description"></textarea></div>
        <div footer>
          <button class="btn btn-outline" (click)="creating.set(false)">Cancel</button>
          <button class="btn btn-primary" (click)="create()" [disabled]="saving()">@if (saving()) { <span class="spinner"></span> } Create</button>
        </div>
      </app-modal>
    }

    <!-- Manage -->
    @if (managing(); as p) {
      <app-modal [title]="p.name" [width]="680" [hasFooter]="false" (close)="managing.set(null)">
        <div class="tabs">
          <button [class.active]="tab() === 'details'" (click)="tab.set('details')">Details</button>
          <button [class.active]="tab() === 'recipe'" (click)="tab.set('recipe')">Recipe</button>
          <button [class.active]="tab() === 'modifiers'" (click)="tab.set('modifiers')">Modifier groups</button>
          <button [class.active]="tab() === 'availability'" (click)="tab.set('availability')">Availability</button>
        </div>

        @switch (tab()) {
          @case ('details') {
            <div class="field"><label>Category</label>
              <select class="select" [(ngModel)]="ef.categoryId">
                @for (c of categories(); track c.id) { <option [value]="c.id">{{ c.name }}</option> }
              </select>
            </div>
            <div class="field"><label>Name</label><input class="input" [(ngModel)]="ef.name" /></div>
            <div class="two-col">
              <div class="field"><label>SKU</label><input class="input" [(ngModel)]="ef.sku" /></div>
              <div class="field"><label>Base price</label><input class="input" type="number" min="0" [(ngModel)]="ef.basePrice" /></div>
            </div>
            <div class="field"><label>Description</label><textarea class="input" [(ngModel)]="ef.description"></textarea></div>
            <label class="checkbox"><input type="checkbox" [(ngModel)]="ef.active" /> Active</label>
            <div class="drawer-foot"><button class="btn btn-primary" (click)="saveDetails()" [disabled]="saving()">Save details</button></div>
          }

          @case ('recipe') {
            <p class="soft">Ingredients consumed per unit sold — drives COGS on completion.</p>
            @for (l of recipe(); track $index) {
              <div class="delta-row">
                <select class="select" [(ngModel)]="l.ingredientId">
                  <option value="" disabled>Ingredient…</option>
                  @for (ing of ingredients(); track ing.id) { <option [value]="ing.id">{{ ing.name }}</option> }
                </select>
                <input class="input" type="number" [(ngModel)]="l.quantity" placeholder="Qty" style="max-width:110px" />
                <input class="input" [(ngModel)]="l.unit" placeholder="unit" style="max-width:90px" />
                <button class="btn btn-icon btn-ghost" (click)="removeLine($index)">✕</button>
              </div>
            } @empty { <div class="muted" style="margin:.5rem 0">No recipe lines.</div> }
            @if (ingredients().length) { <button class="btn btn-sm btn-outline" (click)="addLine()">＋ Add ingredient</button> }
            @else { <div class="muted" style="font-size:12px">Ingredient list needs inventory:read.</div> }
            <div class="drawer-foot"><button class="btn btn-primary" (click)="saveRecipe()" [disabled]="saving()">Save recipe</button></div>
          }

          @case ('modifiers') {
            <p class="soft">Attach option groups offered for this product.</p>
            @for (g of modifierGroups(); track g.id) {
              <label class="checkbox mg-item">
                <input type="checkbox" [checked]="chosenGroups().has(g.id)" (change)="toggleGroup(g.id)" />
                <span><b>{{ g.name }}</b><small>select {{ g.minSelect }}–{{ g.maxSelect }} · {{ g.modifiers.length }} options</small></span>
              </label>
            } @empty { <div class="muted">No modifier groups defined.</div> }
            <div class="drawer-foot"><button class="btn btn-primary" (click)="saveGroups()" [disabled]="saving()">Save groups</button></div>
          }

          @case ('availability') {
            <p class="soft">Per-branch availability and optional price overrides.</p>
            @if (loadingAvail()) { <div class="loading-block"><span class="spinner"></span></div> }
            @else {
              <div class="table-wrap">
                <table class="data">
                  <thead><tr><th>Branch</th><th>Available</th><th>Price override</th><th></th></tr></thead>
                  <tbody>
                    @for (b of branches(); track b.id) {
                      <tr>
                        <td>{{ b.name }}</td>
                        <td><label class="checkbox"><input type="checkbox" [(ngModel)]="availMap[b.id].available" /></label></td>
                        <td><input class="input btn-sm" type="number" min="0" [(ngModel)]="availMap[b.id].priceOverride" placeholder="—" style="max-width:130px;height:32px" /></td>
                        <td class="right"><button class="btn btn-sm btn-outline" (click)="saveAvail(b.id)" [disabled]="saving()">Save</button></td>
                      </tr>
                    }
                  </tbody>
                </table>
              </div>
            }
          }
        }
      </app-modal>
    }
  `,
  styles: [`
    .two-col { display: grid; grid-template-columns: 1fr 1fr; gap: 0 1rem; }
    .tabs { display: flex; gap: .3rem; border-bottom: 1px solid var(--border); margin: -.3rem 0 1.2rem; }
    .tabs button { background: none; border: none; padding: .6rem .8rem; font: inherit; font-weight: 600; font-size: 13px;
      color: var(--text-muted); cursor: pointer; border-bottom: 2px solid transparent; margin-bottom: -1px; }
    .tabs button:hover { color: var(--text); }
    .tabs button.active { color: var(--brand); border-bottom-color: var(--brand); }
    .delta-row { display: flex; gap: .5rem; margin-bottom: .5rem; align-items: center; }
    .mg-item { align-items: flex-start; padding: .5rem 0; border-bottom: 1px solid var(--border); }
    .mg-item span { display: flex; flex-direction: column; line-height: 1.3; }
    .mg-item small { color: var(--text-muted); font-size: 11.5px; }
    .drawer-foot { display: flex; justify-content: flex-end; margin-top: 1.4rem; padding-top: 1rem; border-top: 1px solid var(--border); }
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
  loading = signal(true);
  saving = signal(false);
  loadingAvail = signal(false);

  creating = signal(false);
  managing = signal<ProductResponse | null>(null);
  tab = signal<Tab>('details');

  nf = { categoryId: '', name: '', sku: '', basePrice: '0', description: '' };
  ef = { categoryId: '', name: '', sku: '', basePrice: '0', description: '', active: true };
  recipe = signal<ConsumptionLine[]>([]);
  chosenGroups = signal<Set<string>>(new Set());
  availMap: Record<string, { available: boolean; priceOverride: string | null }> = {};

  categoryName = (id: string) => this.categories().find((c) => c.id === id)?.name ?? '—';

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
    this.api.createProduct({ ...this.nf, basePrice: String(this.nf.basePrice || '0') }).subscribe({
      next: () => { this.saving.set(false); this.creating.set(false); this.toast.success('Product created'); this.load(); },
      error: () => this.saving.set(false),
    });
  }

  openManage(p: ProductResponse) {
    this.managing.set(p);
    this.tab.set('details');
    this.ef = { categoryId: p.categoryId, name: p.name, sku: p.sku, basePrice: p.basePrice.amount, description: p.description ?? '', active: p.active };
    this.chosenGroups.set(new Set(p.modifierGroupIds));
    this.recipe.set([]);
    this.api.getRecipe(p.id).subscribe({ next: (r) => this.recipe.set(r.lines ?? []), error: () => {} });
    this.buildAvailMap(p);
  }

  saveDetails() {
    const p = this.managing(); if (!p) return;
    this.saving.set(true);
    this.api.updateProduct(p.id, { ...this.ef, basePrice: String(this.ef.basePrice || '0') }).subscribe({
      next: (up) => { this.saving.set(false); this.managing.set(up); this.toast.success('Details saved'); this.load(); },
      error: () => this.saving.set(false),
    });
  }

  addLine() { this.recipe.update((r) => [...r, { ingredientId: '', quantity: '', unit: '' }]); }
  removeLine(i: number) { this.recipe.update((r) => r.filter((_, idx) => idx !== i)); }
  saveRecipe() {
    const p = this.managing(); if (!p) return;
    const lines = this.recipe().filter((l) => l.ingredientId && l.quantity && l.unit).map((l) => ({ ...l, quantity: String(l.quantity) }));
    this.saving.set(true);
    this.api.setRecipe(p.id, { lines }).subscribe({
      next: () => { this.saving.set(false); this.toast.success('Recipe saved'); },
      error: () => this.saving.set(false),
    });
  }

  toggleGroup(id: string) {
    const s = new Set(this.chosenGroups());
    s.has(id) ? s.delete(id) : s.add(id);
    this.chosenGroups.set(s);
  }
  saveGroups() {
    const p = this.managing(); if (!p) return;
    this.saving.set(true);
    this.api.setModifierGroups(p.id, { modifierGroupIds: [...this.chosenGroups()] }).subscribe({
      next: (up) => { this.saving.set(false); this.managing.set(up); this.toast.success('Modifier groups saved'); this.load(); },
      error: () => this.saving.set(false),
    });
  }

  private buildAvailMap(p: ProductResponse) {
    this.availMap = {};
    for (const b of this.branches()) this.availMap[b.id] = { available: true, priceOverride: null };
    if (!this.branches().length) return;
    this.loadingAvail.set(true);
    this.api.availability(p.id).subscribe({
      next: (rows: BranchAvailabilityResponse[]) => {
        for (const r of rows) this.availMap[r.branchId] = { available: r.available, priceOverride: r.priceOverride?.amount ?? null };
        this.loadingAvail.set(false);
      },
      error: () => this.loadingAvail.set(false),
    });
  }
  saveAvail(branchId: string) {
    const p = this.managing(); if (!p) return;
    const row = this.availMap[branchId];
    this.saving.set(true);
    this.api.setAvailability(p.id, {
      branchId, available: row.available,
      priceOverride: row.priceOverride ? String(row.priceOverride) : null,
    }).subscribe({
      next: () => { this.saving.set(false); this.toast.success('Availability saved'); },
      error: () => this.saving.set(false),
    });
  }

  async remove(p: ProductResponse) {
    const ok = await this.confirm.ask({ title: `Delete ${p.name}?`, danger: true, confirmText: 'Delete' });
    if (!ok) return;
    this.api.deleteProduct(p.id).subscribe({ next: () => { this.toast.success('Deleted'); this.load(); } });
  }
}
