import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { CatalogService } from '../../core/api.services';
import { AuthService } from '../../core/auth.service';
import { ToastService } from '../../core/toast.service';
import { ConfirmService } from '../../core/confirm.service';
import { ModalComponent } from '../../shared/modal';
import { CategoryRequest, CategoryResponse } from '../../core/models';

@Component({
  selector: 'app-categories',
  standalone: true,
  imports: [FormsModule, ModalComponent],
  template: `
    <div class="page">
      <div class="page-head">
        <div><h1>Categories</h1><div class="sub">Menu groupings that order products on the POS.</div></div>
        @if (auth.can('catalog:write')) { <button class="btn btn-primary" (click)="openNew()">＋ New category</button> }
      </div>

      <div class="card">
        @if (loading()) { <div class="loading-block"><span class="spinner"></span> Loading…</div> }
        @else {
          <div class="table-wrap">
            <table class="data">
              <thead><tr><th style="width:90px">Order</th><th>Name</th><th>Status</th><th></th></tr></thead>
              <tbody>
                @for (c of items(); track c.id) {
                  <tr>
                    <td class="mono">{{ c.displayOrder }}</td>
                    <td><b>{{ c.name }}</b></td>
                    <td><span class="badge dot" [class]="c.active ? 'badge-green' : 'badge-gray'">{{ c.active ? 'Active' : 'Inactive' }}</span></td>
                    <td class="row-actions">
                      @if (auth.can('catalog:write')) {
                        <button class="btn btn-sm btn-ghost" (click)="openEdit(c)">Edit</button>
                        <button class="btn btn-sm btn-ghost" (click)="remove(c)">🗑</button>
                      }
                    </td>
                  </tr>
                } @empty { <tr><td colspan="4"><div class="empty"><div class="big">🏷</div>No categories yet</div></td></tr> }
              </tbody>
            </table>
          </div>
        }
      </div>
    </div>

    @if (editing()) {
      <app-modal [title]="form.id ? 'Edit category' : 'New category'" (close)="editing.set(null)">
        <div class="field"><label>Name</label><input class="input" [(ngModel)]="form.name" placeholder="Espresso Drinks" /></div>
        <div class="field"><label>Display order</label><input class="input" type="number" [(ngModel)]="form.displayOrder" /></div>
        <label class="checkbox"><input type="checkbox" [(ngModel)]="form.active" /> Active</label>
        <div footer>
          <button class="btn btn-outline" (click)="editing.set(null)">Cancel</button>
          <button class="btn btn-primary" (click)="save()" [disabled]="saving()">@if (saving()) { <span class="spinner"></span> } Save</button>
        </div>
      </app-modal>
    }
  `,
})
export class CategoriesComponent {
  auth = inject(AuthService);
  private api = inject(CatalogService);
  private toast = inject(ToastService);
  private confirm = inject(ConfirmService);

  items = signal<CategoryResponse[]>([]);
  loading = signal(true);
  editing = signal<CategoryResponse | null>(null);
  saving = signal(false);
  form: { id?: string } & CategoryRequest = { name: '', displayOrder: 0, active: true };

  constructor() { this.load(); }

  load() {
    this.loading.set(true);
    this.api.listCategories().subscribe({
      next: (c) => { this.items.set([...c].sort((a, b) => a.displayOrder - b.displayOrder)); this.loading.set(false); },
      error: () => this.loading.set(false),
    });
  }

  openNew() { this.form = { name: '', displayOrder: this.items().length, active: true }; this.editing.set({} as CategoryResponse); }
  openEdit(c: CategoryResponse) { this.form = { id: c.id, name: c.name, displayOrder: c.displayOrder, active: c.active }; this.editing.set(c); }

  save() {
    if (!this.form.name) { this.toast.error('Name is required'); return; }
    this.saving.set(true);
    const { id, ...body } = this.form;
    const req = id ? this.api.updateCategory(id, body) : this.api.createCategory(body);
    req.subscribe({
      next: () => { this.saving.set(false); this.editing.set(null); this.toast.success('Category saved'); this.load(); },
      error: () => this.saving.set(false),
    });
  }

  async remove(c: CategoryResponse) {
    const ok = await this.confirm.ask({ title: `Delete ${c.name}?`, danger: true, confirmText: 'Delete' });
    if (!ok) return;
    this.api.deleteCategory(c.id).subscribe({ next: () => { this.toast.success('Deleted'); this.load(); } });
  }
}
