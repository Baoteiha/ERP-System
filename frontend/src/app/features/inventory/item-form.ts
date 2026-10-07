import { Component, OnInit, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { InventoryService } from '../../core/api.services';
import { IngredientResponse, ItemResponse, SupplierResponse } from '../../core/models';
import { ToastService } from '../../core/toast.service';
import { afterSave } from '../../core/save-handler';
import { unitsFor } from '../../core/units';
import { ModalComponent } from '../../shared/modal';
import { packSentence } from './purchase-math';

export const PACK_SUGGESTIONS = ['hộp', 'thùng', 'bao', 'bịch', 'chai', 'lốc', 'gói'];

interface ItemDraft {
  id?: string; name: string; sku: string; supplierId: string; ingredientId: string; unit: string;
  packLabel: string; packSize: string; active: boolean; version?: number;
}

const blank = (): ItemDraft => ({ name: '', sku: '', supplierId: '', ingredientId: '', unit: '', packLabel: '', packSize: '', active: true });

/**
 * Item form: what a supplier sells, the unit it is bought in, and an optional pack, in one drawer.
 * The Items screen and the purchase forms' "+ New item" both open it.
 */
@Component({
  selector: 'app-item-form',
  standalone: true,
  imports: [FormsModule, ModalComponent],
  template: `
    <app-modal variant="drawer" [title]="form.id ? 'Edit item' : 'New item'" (close)="closed.emit()">
      <div class="two-col">
        <div class="field">
          <label for="item-supplier">Supplier <span class="req" aria-hidden="true">*</span></label>
          <select id="item-supplier" class="select" [(ngModel)]="form.supplierId" [disabled]="!!form.id || !!supplierId()" required>
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
      @if (form.id) { <div class="hint pin">Supplier and ingredient are fixed: they define what this item is. Create a second item to point elsewhere.</div> }

      <div class="field"><label for="item-name">Name <span class="req" aria-hidden="true">*</span></label><input id="item-name" class="input" [(ngModel)]="form.name" placeholder="e.g. Ống hút giấy hộp 100" required /></div>

      <div class="two-col">
        <div class="field">
          <label for="item-unit">Bought in <span class="req" aria-hidden="true">*</span></label>
          <select id="item-unit" class="select" [ngModel]="form.unit" (ngModelChange)="onUnitChange($event)" [disabled]="!form.ingredientId" required>
            <option value="" disabled>Choose a unit</option>
            @for (u of availableUnits(); track u.value) { <option [value]="u.value">{{ u.label }}</option> }
          </select>
          @if (conversionHint()) { <div class="hint">{{ conversionHint() }}</div> }
        </div>
        <div class="field"><label for="item-sku">Supplier SKU</label><input id="item-sku" class="input" [(ngModel)]="form.sku" placeholder="Optional" maxlength="64" /></div>
      </div>

      <fieldset class="pack">
        <legend>Pack <span class="soft">(optional)</span></legend>
        <div class="two-col">
          <div class="field">
            <label for="item-pack-label">Pack name</label>
            <input id="item-pack-label" class="input" list="pack-suggestions" [(ngModel)]="form.packLabel" maxlength="32" placeholder="e.g. hộp" />
            <datalist id="pack-suggestions">@for (s of suggestions; track s) { <option [value]="s"></option> }</datalist>
          </div>
          <div class="field">
            <label for="item-pack-size">Each {{ form.packLabel.trim() || 'pack' }} holds</label>
            <div class="with-unit">
              <input id="item-pack-size" class="input" type="number" min="0" step="any" [(ngModel)]="form.packSize" [class.needs]="packNeedsSize" (ngModelChange)="packNeedsSize = false" />
              <span class="unit">{{ form.unit }}</span>
            </div>
            @if (packNeedsSize) { <div class="hint warn">The unit changed: enter the pack size again in {{ form.unit }}.</div> }
          </div>
        </div>
        @if (packHint()) { <div class="hint">→ {{ packHint() }}</div> }
        @if (form.id) { <div class="hint soft">Applies to new orders. Existing orders keep their pack.</div> }
      </fieldset>

      <label class="checkbox"><input type="checkbox" [(ngModel)]="form.active" /> Active</label>
      <div footer>
        <button class="btn btn-outline" (click)="closed.emit()">Cancel</button>
        <button class="btn btn-primary" (click)="save()" [disabled]="saving()">@if (saving()) { <span class="spinner"></span> } Save</button>
      </div>
    </app-modal>
  `,
  styles: [`
    .hint.pin { margin: -.4rem 0 .8rem; font-size: 12px; color: var(--text-muted); }
    .pack { border: 1px solid var(--border); border-radius: var(--radius); padding: .6rem .9rem .3rem; margin: 0 0 1rem; }
    .pack legend { padding: 0 .3rem; font-size: 13px; font-weight: 600; }
    .with-unit { display: flex; align-items: center; gap: .4rem; }
    .unit { color: var(--text-muted); font-size: 12px; white-space: nowrap; }
    .needs { border-color: var(--danger); }
    .warn { color: var(--danger); }
  `],
})
export class ItemFormComponent implements OnInit {
  suppliers = input.required<SupplierResponse[]>();
  ingredients = input.required<IngredientResponse[]>();
  /** The item to edit, or null to create one. */
  item = input<ItemResponse | null>(null);
  /** On create, the supplier the new item belongs to; the purchase forms pass theirs, and it is kept. */
  supplierId = input<string>('');
  saved = output<ItemResponse>();
  closed = output<void>();

  private api = inject(InventoryService);
  private toast = inject(ToastService);
  readonly suggestions = PACK_SUGGESTIONS;
  saving = signal(false);
  packNeedsSize = false;
  form: ItemDraft = blank();

  ngOnInit() {
    const i = this.item();
    this.form = i
      ? { id: i.id, name: i.name, sku: i.sku ?? '', supplierId: i.supplierId, ingredientId: i.ingredientId, unit: i.unit,
          packLabel: i.packLabel ?? '', packSize: i.packSize != null ? String(i.packSize) : '', active: i.active, version: i.version }
      : { ...blank(), supplierId: this.supplierId() };
  }

  baseUnitOf(id: string | undefined) { return this.ingredients().find((i) => i.id === id)?.baseUnit; }

  /** Only units of the ingredient's own dimension: kg for grams, never litres. A plain method, because `form` is not a signal. */
  availableUnits() { return unitsFor(this.baseUnitOf(this.form.ingredientId)); }

  onIngredientChange(ingredientId: string) {
    this.form.ingredientId = ingredientId;
    // The ingredient's own unit is always valid, and one click from the unit most things are bought in.
    this.onUnitChange(this.baseUnitOf(ingredientId) ?? '');
  }

  /** A pack size is counted in the unit, so a new unit makes the old number wrong: ask for it again. */
  onUnitChange(unit: string) {
    if (unit !== this.form.unit && this.form.packSize) {
      this.form.packSize = '';
      this.packNeedsSize = true;
    }
    this.form.unit = unit;
  }

  /** States the conversion in words, so a wrong pick is visible before it is saved. */
  conversionHint() {
    const base = this.baseUnitOf(this.form.ingredientId);
    if (!base || !this.form.unit) return '';
    return this.form.unit === base ? `Bought and counted in ${base}, no conversion.` : `Ordered in ${this.form.unit}, counted in ${base}.`;
  }

  packHint() {
    const base = this.baseUnitOf(this.form.ingredientId);
    const label = this.form.packLabel.trim();
    if (!base || !this.form.unit || !label || !(Number(this.form.packSize) > 0)) return '';
    return `Ordered in ${label} · ${packSentence({ unit: this.form.unit, packLabel: label, packSize: this.form.packSize }, base)} · stock counts ${base}`;
  }

  save() {
    const f = this.form;
    if (!f.supplierId || !f.ingredientId || !f.name.trim() || !f.unit) {
      this.toast.error('Supplier, ingredient, name and unit are required');
      return;
    }
    const label = f.packLabel.trim();
    const size = String(f.packSize ?? '').trim();
    if (!!label !== !!size || (size && !(Number(size) > 0))) {
      this.toast.error('A pack needs both a name and a size above 0');
      return;
    }
    this.saving.set(true);
    const body = { name: f.name.trim(), sku: f.sku, supplierId: f.supplierId, ingredientId: f.ingredientId, unit: f.unit,
      packLabel: label || null, packSize: label ? size : null, active: f.active, version: f.version };
    const req = f.id ? this.api.updateItem(f.id, body) : this.api.createItem(body);
    req.subscribe(afterSave(this.saving, (item: ItemResponse) => { this.toast.success('Item saved'); this.saved.emit(item); }));
  }
}
