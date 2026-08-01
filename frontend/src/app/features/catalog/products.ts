import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { CatalogService, InventoryService, OrganizationService } from '../../core/api.services';
import { AuthService } from '../../core/auth.service';
import { ToastService } from '../../core/toast.service';
import { ConfirmService } from '../../core/confirm.service';
import { ModalComponent } from '../../shared/modal';
import { IconComponent } from '../../shared/icon';
import { MoneyPipe } from '../../core/util';
import {
  BranchAvailabilityResponse, BranchResponse, CategoryResponse, ConsumptionLine,
  IngredientResponse, ModifierGroupResponse, ProductResponse,
} from '../../core/models';

type Tab = 'details' | 'recipe' | 'modifiers' | 'availability';

@Component({
  selector: 'app-products',
  standalone: true,
  imports: [FormsModule, ModalComponent, MoneyPipe, IconComponent],
  template: `
    <div class="page">
      <div class="page-head">
        <div><h1>Products</h1><div class="sub">Menu items with pricing, recipes and modifier options.</div></div>
        @if (auth.can('catalog:write')) { <button class="btn btn-primary" (click)="openNew()"><app-icon name="plus" [size]="16" /> New product</button> }
      </div>

      @if (loading()) { <div class="card"><div class="loading-block"><span class="spinner"></span> Loading…</div></div> }
      @else if (!items().length) {
        <div class="card"><div class="empty"><div class="big"><app-icon name="bean" [size]="30" /></div>No products yet — write your first menu card.</div></div>
      }
      @else {
        <div class="menu-wall niche">
          @for (g of grouped(); track g.id) {
            <section class="menu-sec" [attr.aria-labelledby]="'cat-h-' + g.id">
              <div class="cat-head">
                <span class="chalk script" [id]="'cat-h-' + g.id">{{ g.name }}</span>
                <span class="cat-rule" aria-hidden="true"></span>
              </div>
              <div class="menu-row" role="list">
                @for (p of g.products; track p.id) {
                  <div class="mc-slot" role="listitem">
                    <div class="mc-wrap reveal-zone">
                      <button type="button" class="menu-card paper-card obj-btn" [class.static]="!canWrite" [class.dusty]="!p.active"
                              (click)="canWrite && openManage(p)" [tabindex]="canWrite ? null : -1"
                              [attr.aria-label]="canWrite ? 'Manage ' + p.name : null">
                        <span class="mc-art" aria-hidden="true"><app-icon [name]="catIcon(g.name)" [size]="28" [stroke]="1.5" /></span>
                        <b class="mc-name">{{ p.name }}</b>
                        @if (p.description) { <span class="mc-desc">{{ p.description }}</span> }
                        <span class="leader-row mc-price-row">
                          <span class="mc-sku">{{ p.sku }}</span>
                          <span class="dots" aria-hidden="true"></span>
                          <span class="mc-price">{{ p.basePrice | money }}</span>
                        </span>
                        <span class="mc-foot">
                          <span class="badge dot mc-status" [class]="p.active ? 'badge-green' : 'badge-gray'">{{ p.active ? 'Active' : 'Inactive' }}</span>
                          @if (canWrite) { <span class="mc-go">Manage <app-icon name="chevron" [size]="12" /></span> }
                        </span>
                      </button>
                      @if (canWrite) {
                        <button type="button" class="btn btn-sm btn-icon obj-del mc-del" (click)="remove(p)"
                                [attr.aria-label]="'Delete ' + p.name"><app-icon name="trash" [size]="15" /></button>
                      }
                    </div>
                    <div class="contact-shadow mc-cast" aria-hidden="true"></div>
                    <div class="board shelf-board" aria-hidden="true"></div>
                  </div>
                }
              </div>
            </section>
          }
        </div>
      }
    </div>

    <!-- Create -->
    @if (creating()) {
      <app-modal title="New product" (close)="creating.set(false)">
        <div class="field"><label for="prod-category">Category <span class="req" aria-hidden="true">*</span></label>
          <select id="prod-category" class="select" [(ngModel)]="nf.categoryId" required>
            <option value="" disabled>Select…</option>
            @for (c of categories(); track c.id) { <option [value]="c.id">{{ c.name }}</option> }
          </select>
        </div>
        <div class="field"><label for="prod-name">Name <span class="req" aria-hidden="true">*</span></label><input id="prod-name" class="input" [(ngModel)]="nf.name" placeholder="Cappuccino" required /></div>
        <div class="two-col">
          <div class="field"><label for="prod-sku">SKU <span class="req" aria-hidden="true">*</span></label><input id="prod-sku" class="input" [(ngModel)]="nf.sku" placeholder="CAP-01" required /></div>
          <div class="field"><label for="prod-price">Base price</label><input id="prod-price" class="input" type="number" min="0" [(ngModel)]="nf.basePrice" /></div>
        </div>
        <div class="field"><label for="prod-desc">Description</label><textarea id="prod-desc" class="input" [(ngModel)]="nf.description"></textarea></div>
        <div footer>
          <button class="btn btn-outline" (click)="creating.set(false)">Cancel</button>
          <button class="btn btn-primary" (click)="create()" [disabled]="saving()">@if (saving()) { <span class="spinner"></span> } Create</button>
        </div>
      </app-modal>
    }

    <!-- Manage -->
    @if (managing(); as p) {
      <app-modal [title]="p.name" [width]="680" [hasFooter]="false" (close)="managing.set(null)">
        <div class="tabs" role="tablist">
          <button role="tab" [attr.aria-selected]="tab() === 'details'" [class.active]="tab() === 'details'" (click)="tab.set('details')">Details</button>
          <button role="tab" [attr.aria-selected]="tab() === 'recipe'" [class.active]="tab() === 'recipe'" (click)="tab.set('recipe')">Recipe</button>
          <button role="tab" [attr.aria-selected]="tab() === 'modifiers'" [class.active]="tab() === 'modifiers'" (click)="tab.set('modifiers')">Modifier groups</button>
          <button role="tab" [attr.aria-selected]="tab() === 'availability'" [class.active]="tab() === 'availability'" (click)="tab.set('availability')">Availability</button>
        </div>

        @switch (tab()) {
          @case ('details') {
            <div class="field"><label for="prod-e-category">Category</label>
              <select id="prod-e-category" class="select" [(ngModel)]="ef.categoryId">
                @for (c of categories(); track c.id) { <option [value]="c.id">{{ c.name }}</option> }
              </select>
            </div>
            <div class="field"><label for="prod-e-name">Name</label><input id="prod-e-name" class="input" [(ngModel)]="ef.name" /></div>
            <div class="two-col">
              <div class="field"><label for="prod-e-sku">SKU</label><input id="prod-e-sku" class="input" [(ngModel)]="ef.sku" /></div>
              <div class="field"><label for="prod-e-price">Base price</label><input id="prod-e-price" class="input" type="number" min="0" [(ngModel)]="ef.basePrice" /></div>
            </div>
            <div class="field"><label for="prod-e-desc">Description</label><textarea id="prod-e-desc" class="input" [(ngModel)]="ef.description"></textarea></div>
            <label class="checkbox"><input type="checkbox" [(ngModel)]="ef.active" /> Active</label>
            <div class="drawer-foot"><button class="btn btn-primary" (click)="saveDetails()" [disabled]="saving()">Save details</button></div>
          }

          @case ('recipe') {
            <p class="soft">Ingredients consumed per unit sold — drives COGS on completion.</p>
            @if (!recipeLoaded()) {
              <div class="recipe-empty"><span class="spinner"></span> Loading recipe…</div>
            } @else {
              @for (l of recipe(); track $index) {
                <div class="delta-row">
                  <select class="select" [(ngModel)]="l.ingredientId" aria-label="Ingredient">
                    <option value="" disabled>Ingredient…</option>
                    @for (ing of ingredients(); track ing.id) { <option [value]="ing.id">{{ ing.name }}</option> }
                  </select>
                  <input class="input w-qty" type="number" [(ngModel)]="l.quantity" placeholder="Qty" aria-label="Quantity" />
                  <input class="input w-unit" [(ngModel)]="l.unit" placeholder="unit" aria-label="Unit" />
                  <button class="btn btn-icon btn-ghost" (click)="removeLine($index)" aria-label="Remove ingredient"><app-icon name="close" [size]="16" /></button>
                </div>
              } @empty { <div class="recipe-empty">No recipe yet.</div> }
            }
            @if (ingredients().length) { <button class="btn btn-sm btn-outline" (click)="addLine()"><app-icon name="plus" [size]="15" /> Add ingredient</button> }
            @else { <div class="muted note-sm">Ingredient list needs inventory:read.</div> }
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
            @else if (!branches().length) {
              <div class="empty">No branches available — branch list needs branch:read.</div>
            }
            @else {
              <div class="table-wrap">
                <table class="data">
                  <caption class="sr-only">Per-branch availability and price overrides</caption>
                  <thead><tr><th scope="col">Branch</th><th scope="col">Available</th><th scope="col">Price override</th><th scope="col"><span class="sr-only">Actions</span></th></tr></thead>
                  <tbody>
                    @for (b of branches(); track b.id) {
                      <tr>
                        <td>{{ b.name }}</td>
                        <td><label class="checkbox"><input type="checkbox" [(ngModel)]="availMap[b.id].available" [attr.aria-label]="'Available at ' + b.name" /></label></td>
                        <td><input class="input price-ov" type="number" min="0" [(ngModel)]="availMap[b.id].priceOverride" placeholder="—" [attr.aria-label]="'Price override at ' + b.name" /></td>
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
    /* ---- menu wall ---- */
    .menu-wall { padding: 1.7rem 1.8rem 2rem; display: grid; gap: 1.8rem; }
    .cat-head { display: flex; align-items: center; gap: 1rem; margin-bottom: .95rem; }
    .chalk {
      display: inline-block; font-size: 21px; padding: .15rem 1rem .3rem; rotate: -1deg;
      background: linear-gradient(180deg, var(--nav-bg-2), var(--nav-bg)); color: #fff8f0;
      border-radius: 8px; box-shadow: 0 2px 5px rgba(30,18,8,.35), inset 0 1px 0 rgba(255,255,255,.08);
    }
    .cat-rule { flex: 1; border-bottom: 1px solid var(--hairline); }
    /* fixed-width slots: cards stay compact and shelves hug the row of cards
       instead of one card stretching across a sparse category */
    .menu-row { display: grid; grid-template-columns: repeat(auto-fill, 248px); justify-content: start; column-gap: 0; row-gap: 2rem; }
    .mc-slot { display: flex; flex-direction: column; align-items: center; min-width: 0; }
    .mc-wrap { position: relative; width: 100%; display: flex; justify-content: center; }
    .menu-card {
      position: relative; display: flex; flex-direction: column; align-items: stretch; gap: .35rem; text-align: left;
      width: 92%; min-height: 122px; max-height: 152px; padding: .85rem .9rem .7rem; border-radius: 10px;
    }
    .mc-art { position: absolute; top: .55rem; right: .65rem; color: color-mix(in srgb, var(--brand) 30%, transparent); pointer-events: none; }
    .mc-name { padding-right: 34px; }
    .mc-name { font-size: 14.5px; font-weight: 650; color: var(--paper-ink); line-height: 1.25; }
    .mc-desc {
      font-size: 12px; color: var(--paper-ink-soft); line-height: 1.4;
      display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden;
    }
    .mc-price-row { margin-top: auto; }
    .mc-sku { font-family: var(--font-mono); font-size: 10.5px; color: var(--paper-ink-soft); }
    .mc-price { font-family: var(--font-mono); font-weight: 700; font-variant-numeric: tabular-nums; color: var(--paper-accent); }
    .mc-foot { display: flex; align-items: center; justify-content: space-between; gap: .5rem; margin-top: .2rem; }
    .mc-status { height: 19px; font-size: 10px; padding: 0 .45rem; }
    .mc-go { display: inline-flex; align-items: center; gap: .15rem; font-size: 11.5px; font-weight: 600; color: var(--paper-ink-soft); }
    .menu-card:not(.static):hover .mc-go { color: var(--paper-accent); }
    .mc-del { top: 6px; right: calc(5% + 4px); }
    .mc-cast { margin: -4px 0 -5px; }
    .mc-slot .board { width: 100%; }
    .mc-slot:first-child .board { border-top-left-radius: 6px; border-bottom-left-radius: 8px; }
    .mc-slot:last-child .board { border-top-right-radius: 6px; border-bottom-right-radius: 8px; }

    /* ---- manage/create modals ---- */
    .delta-row { display: flex; gap: .5rem; margin-bottom: .5rem; align-items: center; }
    .w-qty { max-width: 110px; }
    .w-unit { max-width: 90px; }
    .note-sm { font-size: 12px; }
    .price-ov { max-width: 130px; min-height: 38px; }
    .recipe-empty { display: flex; align-items: center; gap: .5rem; margin: .75rem 0; padding: .85rem 1rem; border: 1px dashed var(--border-strong); border-radius: var(--radius-sm); color: var(--text-soft); background: var(--surface-2); }
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
  recipeLoaded = signal(false);
  chosenGroups = signal<Set<string>>(new Set());
  availMap: Record<string, { available: boolean; priceOverride: string | null }> = {};

  categoryName = (id: string) => this.categories().find((c) => c.id === id)?.name ?? '—';

  get canWrite() { return this.auth.can('catalog:write'); }

  /** Card art from the category name (products have no image field). */
  catIcon(catName: string): string {
    const c = (catName || '').toLowerCase();
    if (/tea|matcha|herb/.test(c)) return 'leaf';
    if (/milk|dairy|smoothie|juice/.test(c)) return 'cup';
    if (/bean|retail|pack/.test(c)) return 'bean';
    if (/food|pastry|cake|snack|basket/.test(c)) return 'basket';
    return 'coffee';
  }

  /** Products grouped by category (catalog order), orphans last. */
  grouped = computed(() => {
    const cats = this.categories();
    const prods = this.items();
    const groups = cats
      .map((c) => ({ id: c.id, name: c.name, products: prods.filter((p) => p.categoryId === c.id) }))
      .filter((g) => g.products.length > 0);
    const known = new Set(cats.map((c) => c.id));
    const orphans = prods.filter((p) => !known.has(p.categoryId));
    if (orphans.length) groups.push({ id: 'uncategorized', name: 'Uncategorized', products: orphans });
    return groups;
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
    this.recipeLoaded.set(false);
    this.api.getRecipe(p.id).subscribe({
      next: (r) => { this.recipe.set(r.lines ?? []); this.recipeLoaded.set(true); },
      error: () => { this.recipe.set([]); this.recipeLoaded.set(true); },
    });
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
