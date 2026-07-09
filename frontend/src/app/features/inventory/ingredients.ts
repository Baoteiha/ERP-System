import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { InventoryService } from '../../core/api.services';
import { AuthService } from '../../core/auth.service';
import { ToastService } from '../../core/toast.service';
import { ConfirmService } from '../../core/confirm.service';
import { ModalComponent } from '../../shared/modal';
import { IngredientRequest, IngredientResponse } from '../../core/models';

@Component({
  selector: 'app-ingredients',
  standalone: true,
  imports: [FormsModule, ModalComponent],
  template: `
    <div class="page">
      <div class="page-head">
        <div><h1>Ingredients</h1><div class="sub">Raw materials consumed by recipes. Company-wide master data.</div></div>
        @if (auth.can('inventory:write')) { <button class="btn btn-primary" (click)="openNew()">＋ New ingredient</button> }
      </div>

      <div class="card">
        @if (loading()) { <div class="loading-block"><span class="spinner"></span> Loading…</div> }
        @else {
          <div class="table-wrap">
            <table class="data">
              <thead><tr><th>Name</th><th>Base unit</th><th>Category</th><th>Status</th><th></th></tr></thead>
              <tbody>
                @for (i of items(); track i.id) {
                  <tr>
                    <td><b>{{ i.name }}</b></td>
                    <td><span class="badge badge-gray">{{ i.baseUnit }}</span></td>
                    <td class="soft">{{ i.category || '—' }}</td>
                    <td><span class="badge dot" [class]="i.active ? 'badge-green' : 'badge-gray'">{{ i.active ? 'Active' : 'Inactive' }}</span></td>
                    <td class="row-actions">
                      @if (auth.can('inventory:write')) {
                        <button class="btn btn-sm btn-ghost" (click)="openEdit(i)">Edit</button>
                        <button class="btn btn-sm btn-ghost" (click)="remove(i)">🗑</button>
                      }
                    </td>
                  </tr>
                } @empty { <tr><td colspan="5"><div class="empty"><div class="big">🧂</div>No ingredients yet</div></td></tr> }
              </tbody>
            </table>
          </div>
        }
      </div>
    </div>

    @if (editing()) {
      <app-modal [title]="form.id ? 'Edit ingredient' : 'New ingredient'" (close)="editing.set(null)">
        <div class="field"><label>Name</label><input class="input" [(ngModel)]="form.name" placeholder="Whole milk" /></div>
        <div class="two-col">
          <div class="field"><label>Base unit</label><input class="input" [(ngModel)]="form.baseUnit" placeholder="ml / g / pc" maxlength="16" /></div>
          <div class="field"><label>Category</label><input class="input" [(ngModel)]="form.category" placeholder="Dairy" /></div>
        </div>
        <label class="checkbox"><input type="checkbox" [(ngModel)]="form.active" /> Active</label>
        <div footer>
          <button class="btn btn-outline" (click)="editing.set(null)">Cancel</button>
          <button class="btn btn-primary" (click)="save()" [disabled]="saving()">@if (saving()) { <span class="spinner"></span> } Save</button>
        </div>
      </app-modal>
    }
  `,
  styles: [`.two-col { display: grid; grid-template-columns: 1fr 1fr; gap: 0 1rem; }`],
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

  constructor() { this.load(); }

  load() {
    this.loading.set(true);
    this.api.listIngredients().subscribe({
      next: (i) => { this.items.set(i); this.loading.set(false); },
      error: () => this.loading.set(false),
    });
  }

  openNew() { this.form = { name: '', baseUnit: '', category: '', active: true }; this.editing.set({} as IngredientResponse); }
  openEdit(i: IngredientResponse) { this.form = { id: i.id, name: i.name, baseUnit: i.baseUnit, category: i.category, active: i.active }; this.editing.set(i); }

  save() {
    if (!this.form.name || !this.form.baseUnit) { this.toast.error('Name and base unit are required'); return; }
    this.saving.set(true);
    const { id, ...body } = this.form;
    const req = id ? this.api.updateIngredient(id, body) : this.api.createIngredient(body);
    req.subscribe({
      next: () => { this.saving.set(false); this.editing.set(null); this.toast.success('Ingredient saved'); this.load(); },
      error: () => this.saving.set(false),
    });
  }

  async remove(i: IngredientResponse) {
    const ok = await this.confirm.ask({ title: `Delete ${i.name}?`, danger: true, confirmText: 'Delete' });
    if (!ok) return;
    this.api.deleteIngredient(i.id).subscribe({ next: () => { this.toast.success('Deleted'); this.load(); } });
  }
}
