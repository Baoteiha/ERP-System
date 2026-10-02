import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { InventoryService } from '../../core/api.services';
import { AuthService } from '../../core/auth.service';
import { ToastService } from '../../core/toast.service';
import { afterSave } from '../../core/save-handler';
import { ConfirmService } from '../../core/confirm.service';
import { ModalComponent } from '../../shared/modal';
import { IconComponent } from '../../shared/icon';
import { StatusBadgeComponent } from '../../shared/status-badge';
import { IngredientRequest, IngredientResponse } from '../../core/models';
import { UNIT_GROUPS } from '../../core/units';

@Component({
  selector: 'app-ingredients',
  standalone: true,
  imports: [FormsModule, ModalComponent, IconComponent, StatusBadgeComponent],
  template: `
    <div class="page">
      <div class="page-head">
        <div><h1>Ingredients</h1><div class="sub">Raw materials consumed by recipes. Company-wide master data.</div></div>
        @if (auth.can('inventory:write')) { <button class="btn btn-primary" (click)="openNew()"><app-icon name="plus" [size]="16" /> New ingredient</button> }
      </div>

      @if (loading()) { <div class="card"><div class="loading-block"><span class="spinner"></span> Loading…</div></div> }
      @else if (!items().length) {
        <div class="card"><div class="empty"><div class="big"><app-icon name="flask" [size]="30" /></div>No ingredients yet — stock your first shelf.</div></div>
      }
      @else {
        <div class="card">
          <div class="table-wrap">
            <table class="data">
              <caption class="sr-only">Ingredients</caption>
              <thead>
                <tr>
                  <th scope="col">Name</th><th scope="col">Category</th><th scope="col">Base unit</th><th scope="col">Status</th>
                  <th scope="col"><span class="sr-only">Actions</span></th>
                </tr>
              </thead>
              <tbody>
                @for (i of items(); track i.id) {
                  <tr [class.muted]="!i.active">
                    <td>
                      @if (canWrite) { <button type="button" class="row-open" (click)="openEdit(i)">{{ i.name }}</button> }
                      @else { <b>{{ i.name }}</b> }
                    </td>
                    <td>{{ i.category || 'Uncategorized' }}</td>
                    <td><span class="unit-chip">{{ i.baseUnit }}</span></td>
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

    @if (editing()) {
      <app-modal variant="drawer" [title]="form.id ? 'Edit ingredient' : 'New ingredient'" (close)="editing.set(null)">
        <div class="field"><label for="ing-name">Name <span class="req" aria-hidden="true">*</span></label><input id="ing-name" class="input" [(ngModel)]="form.name" placeholder="Whole milk" required /></div>
        <div class="two-col">
          <div class="field">
            <label for="ing-base-unit">Base unit <span class="req" aria-hidden="true">*</span></label>
            <select id="ing-base-unit" class="select" [(ngModel)]="form.baseUnit" required>
              <option value="" disabled>Choose a unit</option>
              @for (g of unitGroups; track g.label) {
                <optgroup [label]="g.label">
                  @for (u of g.units; track u.value) { <option [value]="u.value">{{ u.label }}</option> }
                </optgroup>
              }
            </select>
            <div class="hint">Count stock at the finest level you sell — one can, not one case.</div>
          </div>
          <div class="field"><label for="ing-category">Category</label><input id="ing-category" class="input" [(ngModel)]="form.category" placeholder="Dairy" /></div>
        </div>
        <label class="checkbox"><input type="checkbox" [(ngModel)]="form.active" /> Active</label>
        <div footer>
          <button class="btn btn-outline" (click)="editing.set(null)">Cancel</button>
          <button class="btn btn-primary" (click)="save()" [disabled]="saving()">@if (saving()) { <span class="spinner"></span> } Save</button>
        </div>
      </app-modal>
    }
  `,
})
export class IngredientsComponent {
  auth = inject(AuthService);
  private api = inject(InventoryService);
  private toast = inject(ToastService);
  private confirm = inject(ConfirmService);

  items = signal<IngredientResponse[]>([]);
  loading = signal(true);
  editing = signal<IngredientResponse | null>(null);
  saving = signal(false);
  form: { id?: string } & IngredientRequest = { name: '', baseUnit: '', category: '', active: true };
  readonly unitGroups = UNIT_GROUPS;

  constructor() { this.load(); }

  get canWrite() { return this.auth.can('inventory:write'); }

  load() {
    this.loading.set(true);
    this.api.listIngredients().subscribe({
      next: (i) => { this.items.set(i); this.loading.set(false); },
      error: () => this.loading.set(false),
    });
  }

  openNew() { this.form = { name: '', baseUnit: '', category: '', active: true }; this.editing.set({} as IngredientResponse); }
  openEdit(i: IngredientResponse) { this.form = { id: i.id, name: i.name, baseUnit: i.baseUnit, category: i.category, active: i.active, version: i.version }; this.editing.set(i); }

  save() {
    if (!this.form.name || !this.form.baseUnit) { this.toast.error('Name and base unit are required'); return; }
    this.saving.set(true);
    const { id, ...body } = this.form;
    const req = id ? this.api.updateIngredient(id, body) : this.api.createIngredient(body);
    req.subscribe(afterSave(this.saving, () => { this.editing.set(null); this.toast.success('Ingredient saved'); this.load(); }));
  }

  async remove(i: IngredientResponse) {
    const ok = await this.confirm.ask({ title: `Delete ${i.name}?`, danger: true, confirmText: 'Delete' });
    if (!ok) return;
    this.api.deleteIngredient(i.id).subscribe({ next: () => { this.toast.success('Deleted'); this.load(); } });
  }
}
