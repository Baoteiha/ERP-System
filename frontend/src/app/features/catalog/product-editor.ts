import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, computed, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { CatalogService } from '../../core/api.services';
import { AuthService } from '../../core/auth.service';
import { ToastService } from '../../core/toast.service';
import { afterSave } from '../../core/save-handler';
import { ModalComponent } from '../../shared/modal';
import { IconComponent } from '../../shared/icon';
import { unitsFor } from '../../core/units';
import {
  BranchAvailabilityResponse, BranchResponse, CategoryResponse, ConsumptionLine,
  IngredientResponse, ModifierGroupResponse, ProductResponse,
} from '../../core/models';

type Tab = 'details' | 'recipe' | 'modifiers' | 'availability';

/** The product manager: details, recipe, modifier groups and per-branch availability, one tab each. */
@Component({
  selector: 'app-product-editor',
  standalone: true,
  imports: [FormsModule, ModalComponent, IconComponent],
  template: `
    <app-modal variant="drawer" [title]="product().name" [width]="640" [hasFooter]="false" (close)="closed.emit()">
      <div class="tabs" role="tablist">
        <button role="tab" [attr.aria-selected]="tab() === 'details'" [class.active]="tab() === 'details'" (click)="tab.set('details')">Details</button>
        <button role="tab" [attr.aria-selected]="tab() === 'recipe'" [class.active]="tab() === 'recipe'" (click)="tab.set('recipe')">Recipe</button>
        <button role="tab" [attr.aria-selected]="tab() === 'modifiers'" [class.active]="tab() === 'modifiers'" (click)="tab.set('modifiers')">Modifier groups</button>
        <button role="tab" [attr.aria-selected]="tab() === 'availability'" [class.active]="tab() === 'availability'" (click)="tab.set('availability')">Availability</button>
      </div>

      @switch (tab()) {
        @case ('details') {
          <form (ngSubmit)="saveDetails()">
          <div class="field"><label for="prod-e-category">Category <span class="req" aria-hidden="true">*</span></label>
            <select id="prod-e-category" class="select" name="categoryId" [(ngModel)]="ef.categoryId" required>
              @for (c of categories(); track c.id) { <option [value]="c.id">{{ c.name }}</option> }
            </select>
          </div>
          <div class="field"><label for="prod-e-name">Name <span class="req" aria-hidden="true">*</span></label><input id="prod-e-name" class="input" name="name" [(ngModel)]="ef.name" required /></div>
          <div class="two-col">
            <div class="field"><label for="prod-e-sku">SKU <span class="req" aria-hidden="true">*</span></label><input id="prod-e-sku" class="input" name="sku" [(ngModel)]="ef.sku" required /></div>
            <div class="field"><label for="prod-e-price">Base price</label><input id="prod-e-price" class="input" type="number" min="0" name="basePrice" [(ngModel)]="ef.basePrice" /></div>
          </div>
          <div class="field"><label for="prod-e-desc">Description</label><textarea id="prod-e-desc" class="input" name="description" [(ngModel)]="ef.description"></textarea></div>
          <label class="checkbox"><input type="checkbox" name="active" [(ngModel)]="ef.active" /> Active</label>
          <div class="drawer-foot"><button type="submit" class="btn btn-primary" [disabled]="saving()">Save details</button></div>
          </form>
        }

        @case ('recipe') {
          <p class="soft">Ingredients consumed per unit sold — drives COGS on completion.</p>
          <form (ngSubmit)="saveRecipe()">
          @if (!recipeLoaded()) {
            <div class="recipe-empty"><span class="spinner"></span> Loading recipe…</div>
          } @else if (recipeError()) {
            <div class="recipe-empty recipe-error" role="alert">Could not load the recipe. Close and reopen this product to try again — saving is disabled so the existing recipe is not overwritten.</div>
          } @else {
            @for (l of recipe(); track $index) {
              <div class="delta-row">
                <select class="select" [name]="'ingredient-' + $index" [(ngModel)]="l.ingredientId" (ngModelChange)="onIngredientPicked(l)" aria-label="Ingredient" required>
                  <option value="" disabled>Ingredient…</option>
                  @for (ing of ingredients(); track ing.id) { <option [value]="ing.id">{{ ing.name }} ({{ ing.baseUnit }})</option> }
                </select>
                <input class="input w-qty" type="number" min="0" step="0.001" [name]="'quantity-' + $index" [(ngModel)]="l.quantity" placeholder="Qty" aria-label="Quantity" required />
                <select class="select w-unit" [name]="'unit-' + $index" [(ngModel)]="l.unit" aria-label="Unit" required>
                  <option value="" disabled>Unit…</option>
                  @for (u of unitOptions(l); track u.value) { <option [value]="u.value">{{ u.value }}</option> }
                </select>
                <button type="button" class="btn btn-icon btn-ghost" (click)="removeLine($index)" aria-label="Remove ingredient"><app-icon name="close" [size]="16" /></button>
              </div>
            } @empty { <div class="recipe-empty">No recipe yet.</div> }
          }
          @if (ingredients().length) { <button type="button" class="btn btn-sm btn-outline" (click)="addLine()" [disabled]="recipeError() || !recipeLoaded()"><app-icon name="plus" [size]="15" /> Add ingredient</button> }
          @else { <div class="muted note-sm">Ingredient list needs inventory:read.</div> }
          @if (recipeProblem(); as problem) { <div class="note-sm field-error" role="alert">{{ problem }}</div> }
          <div class="drawer-foot"><button type="submit" class="btn btn-primary" [disabled]="saving() || !recipeLoaded() || recipeError() || !!recipeProblem()">Save recipe</button></div>
          </form>
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
          @else if (!availabilityBranches().length) {
            <div class="empty">No branches to show — needs branch:read, and catalog:read at a branch.</div>
          }
          @else {
            <div class="table-wrap">
              <table class="data">
                <caption class="sr-only">Per-branch availability and price overrides</caption>
                <thead><tr><th scope="col">Branch</th><th scope="col">Available</th><th scope="col">Price override</th><th scope="col"><span class="sr-only">Actions</span></th></tr></thead>
                <tbody>
                  @for (b of availabilityBranches(); track b.id) {
                    <tr>
                      <td>{{ b.name }}</td>
                      <td><label class="checkbox"><input type="checkbox" [(ngModel)]="availMap[b.id].available" [attr.aria-label]="'Available at ' + b.name" /></label></td>
                      <td><input class="input price-ov" type="number" min="0" [(ngModel)]="availMap[b.id].priceOverride" placeholder="—" [attr.aria-label]="'Price override at ' + b.name" /></td>
                      <td class="right"><button class="btn btn-sm btn-outline" (click)="saveAvail(b.id)" [disabled]="saving() || !auth.canAt(b.id, 'catalog:write')">Save</button></td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
          }
        }
      }
    </app-modal>
  `,
  styles: [`
    /* ---- manage/create modals ---- */
    .delta-row { display: flex; gap: .5rem; margin-bottom: .5rem; align-items: center; }
    .w-qty { max-width: 110px; }
    .w-unit { max-width: 90px; }
    .note-sm { font-size: 12px; }
    .recipe-error, .field-error { color: var(--red); }
    .field-error { margin-top: .5rem; }
    .price-ov { max-width: 130px; min-height: 38px; }
    .recipe-empty { display: flex; align-items: center; gap: .5rem; margin: .75rem 0; padding: .85rem 1rem; border: 1px dashed var(--border-strong); border-radius: var(--radius-sm); color: var(--text-soft); background: var(--surface-2); }
    .mg-item { align-items: flex-start; padding: .5rem 0; border-bottom: 1px solid var(--border); }
    .mg-item span { display: flex; flex-direction: column; line-height: 1.3; }
    .mg-item small { color: var(--text-muted); font-size: 11.5px; }
    .drawer-foot { position: sticky; bottom: 0; z-index: 2; display: flex; justify-content: flex-end; gap: .6rem; margin: 1.4rem -1.4rem -1.3rem; padding: 1rem 1.4rem; background: var(--surface-1); border-top: 1px solid var(--border); }
  `],
})
export class ProductEditorComponent implements OnInit {
  auth = inject(AuthService);
  private api = inject(CatalogService);
  private toast = inject(ToastService);

  product = input.required<ProductResponse>();
  categories = input.required<CategoryResponse[]>();
  ingredients = input.required<IngredientResponse[]>();
  modifierGroups = input.required<ModifierGroupResponse[]>();
  branches = input.required<BranchResponse[]>();
  closed = output<void>();
  /** Details or modifier groups were saved: the product as the server now has it. */
  saved = output<ProductResponse>();

  /** the server answers for and changes availability only where the user's role there reads / writes the catalog */
  availabilityBranches = computed(() => this.branches().filter((b) => this.auth.canAt(b.id, 'catalog:read')));
  saving = signal(false);
  loadingAvail = signal(false);
  tab = signal<Tab>('details');

  ef = { categoryId: '', name: '', sku: '', basePrice: '0', description: '', active: true };
  recipe = signal<ConsumptionLine[]>([]);
  recipeLoaded = signal(false);
  /** A failed load, as opposed to a product that simply has no recipe yet. */
  recipeError = signal(false);
  private recipeVersion?: number;
  chosenGroups = signal<Set<string>>(new Set());
  availMap: Record<string, { available: boolean; priceOverride: string | null }> = {};

  ngOnInit() {
    const p = this.product();
    this.ef = { categoryId: p.categoryId, name: p.name, sku: p.sku, basePrice: p.basePrice.amount, description: p.description ?? '', active: p.active };
    this.chosenGroups.set(new Set(p.modifierGroupIds));
    this.api.getRecipe(p.id).subscribe({
      next: (r) => { this.recipe.set(r.lines ?? []); this.recipeVersion = r.version; this.recipeLoaded.set(true); },
      error: (e: HttpErrorResponse) => {
        // 404 is the API's way of saying "no recipe yet"; anything else must not look like an empty recipe
        this.recipe.set([]);
        this.recipeError.set(e.status !== 404);
        this.recipeLoaded.set(true);
      },
    });
    this.buildAvailMap(p);
  }

  saveDetails() {
    const p = this.product();
    if (!this.ef.categoryId || !this.ef.name || !this.ef.sku) { this.toast.error('Category, name and SKU are required'); return; }
    this.saving.set(true);
    this.api.updateProduct(p.id, { ...this.ef, basePrice: String(this.ef.basePrice || '0') }).subscribe(
      afterSave(this.saving, (up) => { this.toast.success('Details saved'); this.saved.emit(up); }));
  }

  addLine() { this.recipe.update((r) => [...r, { ingredientId: '', quantity: '', unit: '' }]); }
  removeLine(i: number) { this.recipe.update((r) => r.filter((_, idx) => idx !== i)); }
  /** Units of the picked ingredient's own dimension; none picked yet offers them all. */
  unitOptions(l: ConsumptionLine) { return unitsFor(this.ingredients().find((i) => i.id === l.ingredientId)?.baseUnit); }
  onIngredientPicked(l: ConsumptionLine) {
    const base = this.ingredients().find((i) => i.id === l.ingredientId)?.baseUnit;
    if (base) l.unit = base;
  }
  /** Why Save is blocked, if it is: a half-filled line would otherwise be dropped silently. */
  // a method, not a computed: ngModel edits the line objects in place, which a signal cannot see
  recipeProblem(): string | null {
    return this.recipe().some((l) => !l.ingredientId || !l.quantity || !l.unit)
      ? 'Every ingredient needs a quantity and a unit — fill in or remove the incomplete lines.'
      : null;
  }
  saveRecipe() {
    if (!this.recipeLoaded() || this.recipeError() || this.recipeProblem()) return;
    const lines = this.recipe().map((l) => ({ ...l, quantity: String(l.quantity) }));
    this.saving.set(true);
    this.api.setRecipe(this.product().id, { lines, version: this.recipeVersion }).subscribe(
      afterSave(this.saving, (r) => { this.recipeVersion = r.version; this.toast.success('Recipe saved'); }));
  }

  toggleGroup(id: string) {
    const s = new Set(this.chosenGroups());
    if (!s.delete(id)) s.add(id);
    this.chosenGroups.set(s);
  }
  saveGroups() {
    this.saving.set(true);
    this.api.setModifierGroups(this.product().id, { modifierGroupIds: [...this.chosenGroups()] }).subscribe(
      afterSave(this.saving, (up) => { this.toast.success('Modifier groups saved'); this.saved.emit(up); }));
  }

  private buildAvailMap(p: ProductResponse) {
    this.availMap = {};
    for (const b of this.availabilityBranches()) this.availMap[b.id] = { available: true, priceOverride: null };
    if (!this.availabilityBranches().length) return;
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
    const row = this.availMap[branchId];
    this.saving.set(true);
    this.api.setAvailability(this.product().id, {
      branchId, available: row.available,
      // 0 is a real override (free at this branch); only an empty box means "no override"
      priceOverride: row.priceOverride !== null && row.priceOverride !== '' ? String(row.priceOverride) : null,
    }).subscribe(afterSave(this.saving, () => this.toast.success('Availability saved')));
  }
}
