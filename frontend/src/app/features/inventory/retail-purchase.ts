import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { InventoryService } from '../../core/api.services';
import { AuthService } from '../../core/auth.service';
import { IngredientResponse, ItemResponse, SupplierResponse } from '../../core/models';
import { ToastService } from '../../core/toast.service';
import { afterSave } from '../../core/save-handler';
import { ItemFormComponent } from './item-form';
import { PurchaseLine, PurchaseLinesComponent, newPurchaseLine, purchaseLinesError } from './purchase-lines';

/**
 * Retail purchase: something bought and carried in, recorded in one save. It becomes a purchase order
 * already received, so reports, cost and price history count it like any other purchase.
 */
@Component({
  selector: 'app-retail-purchase',
  standalone: true,
  imports: [FormsModule, PurchaseLinesComponent, ItemFormComponent],
  template: `
    <div class="page">
      <div class="page-head">
        <div><h1>Retail purchase</h1><div class="sub">Bought and carried in: saved as received at once.</div></div>
      </div>
      <div class="card card-pad form">
        <div class="field">
          <label for="retail-supplier">Bought from <span class="req" aria-hidden="true">*</span></label>
          <div class="supplier-row">
            <select id="retail-supplier" class="select" [ngModel]="form.supplierId" (ngModelChange)="onSupplierChange($event)">
              <option value="">Select supplier</option>
              @for (s of suppliers(); track s.id) { <option [value]="s.id">{{ s.name }}</option> }
            </select>
            @if (canDeclare()) { <button type="button" class="btn btn-sm btn-ghost" (click)="addingSupplier.set(!addingSupplier())">+ New supplier</button> }
          </div>
          @if (addingSupplier()) {
            <div class="supplier-row">
              <input class="input" [(ngModel)]="newSupplierName" placeholder="Shop name" aria-label="New supplier name" />
              <button type="button" class="btn btn-sm btn-primary" (click)="createSupplier()" [disabled]="!newSupplierName.trim()">Add</button>
            </div>
          }
        </div>
        <app-purchase-lines [lines]="form.lines" [items]="supplierItems()" [ingredients]="ingredients()" [disabled]="!form.supplierId"
                            [canCreateItem]="canDeclare()" (newItem)="itemFormFor.set($event)" />
        <div class="field"><label for="retail-note">Note</label><input id="retail-note" class="input" [(ngModel)]="form.note" maxlength="255" placeholder="e.g. Ran out of milk at 9pm" /></div>
        <div class="actions"><button class="btn btn-primary" (click)="save()" [disabled]="saving()">@if (saving()) { <span class="spinner"></span> } Save &amp; receive</button></div>
      </div>
    </div>
    @if (itemFormFor() !== null) {
      <app-item-form [suppliers]="suppliers()" [ingredients]="ingredients()" [supplierId]="form.supplierId"
                     (saved)="onItemCreated($event)" (closed)="itemFormFor.set(null)" />
    }
  `,
  styles: [`
    .form { display: grid; gap: 1rem; max-width: 820px; }
    .supplier-row { display: flex; gap: .5rem; align-items: center; margin-top: .4rem; }
    .actions { display: flex; justify-content: flex-end; }
  `],
})
export class RetailPurchaseComponent {
  private api = inject(InventoryService);
  private auth = inject(AuthService);
  private toast = inject(ToastService);

  suppliers = signal<SupplierResponse[]>([]);
  ingredients = signal<IngredientResponse[]>([]);
  allItems = signal<ItemResponse[]>([]);
  saving = signal(false);
  itemFormFor = signal<number | null>(null);
  addingSupplier = signal(false);
  newSupplierName = '';
  form = { supplierId: '', note: '', lines: [newPurchaseLine()] as PurchaseLine[] };
  private chosenSupplier = signal('');
  supplierItems = computed(() => this.allItems().filter((i) => i.active && i.supplierId === this.chosenSupplier()));

  constructor() {
    this.api.listSuppliers().subscribe({ next: (s) => this.suppliers.set(s.filter((x) => x.active !== false)) });
    this.api.listIngredients().subscribe({ next: (i) => this.ingredients.set(i) });
    this.api.listItems().subscribe({ next: (i) => this.allItems.set(i) });
  }

  /** Only someone who may edit stock master data can declare a supplier or an item, and with it a pack. */
  canDeclare() { return this.auth.can('inventory:write'); }

  onSupplierChange(id: string) {
    this.form.supplierId = id;
    this.chosenSupplier.set(id);
    this.form.lines = [newPurchaseLine()];
  }

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

  save() {
    const problem = !this.form.supplierId ? 'Choose where it was bought' : purchaseLinesError(this.form.lines, this.supplierItems());
    if (problem) { this.toast.error(problem); return; }
    this.saving.set(true);
    this.api.retailPurchase({
      supplierId: this.form.supplierId, note: this.form.note.trim() || null,
      lines: this.form.lines.map((l) => ({ itemId: l.itemId, quantity: String(l.quantity), unitCost: String(l.unitCost) })),
    }).subscribe(afterSave(this.saving, () => {
      this.toast.success('Retail purchase saved, stock updated');
      this.form = { supplierId: this.form.supplierId, note: '', lines: [newPurchaseLine()] };
    }));
  }
}
