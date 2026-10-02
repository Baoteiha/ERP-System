import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { CatalogService } from '../../core/api.services';
import { AuthService } from '../../core/auth.service';
import { ToastService } from '../../core/toast.service';
import { afterSave } from '../../core/save-handler';
import { ConfirmService } from '../../core/confirm.service';
import { ModalComponent } from '../../shared/modal';
import { IconComponent } from '../../shared/icon';
import { StatusBadgeComponent } from '../../shared/status-badge';
import { CategoryRequest, CategoryResponse } from '../../core/models';

@Component({
  selector: 'app-categories',
  standalone: true,
  imports: [FormsModule, ModalComponent, IconComponent, StatusBadgeComponent],
  template: `
    <div class="page">
      <div class="page-head">
        <div><h1>Categories</h1><div class="sub">Menu groupings that order products on the POS.</div></div>
        @if (auth.can('catalog:write')) { <button class="btn btn-primary" (click)="openNew()"><app-icon name="plus" [size]="16" /> New category</button> }
      </div>

      @if (loading()) { <div class="card"><div class="loading-block"><span class="spinner"></span> Loading…</div></div> }
      @else if (!items().length) {
        <div class="card"><div class="empty"><div class="big"><app-icon name="tag" [size]="30" /></div>No categories yet.</div></div>
      }
      @else {
        <div class="card">
          <div class="table-wrap">
            <table class="data">
              <caption class="sr-only">Categories</caption>
              <thead>
                <tr>
                  <th scope="col" class="num">Order</th><th scope="col">Name</th><th scope="col">Status</th>
                  <th scope="col"><span class="sr-only">Actions</span></th>
                </tr>
              </thead>
              <tbody>
                @for (c of items(); track c.id) {
                  <tr [class.muted]="!c.active">
                    <td class="num">{{ c.displayOrder }}</td>
                    <td>
                      @if (canWrite) { <button type="button" class="row-open" (click)="openEdit(c)">{{ c.name }}</button> }
                      @else { <b>{{ c.name }}</b> }
                    </td>
                    <td><app-status-badge size="sm" [active]="c.active" /></td>
                    <td>
                      @if (canWrite) {
                        <div class="row-actions">
                          <button type="button" class="btn btn-sm btn-ghost btn-icon" (click)="openEdit(c)" [attr.aria-label]="'Edit ' + c.name"><app-icon name="edit" [size]="15" /></button>
                          <button type="button" class="btn btn-sm btn-ghost btn-icon" (click)="remove(c)" [attr.aria-label]="'Delete ' + c.name"><app-icon name="trash" [size]="15" /></button>
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
      <app-modal variant="drawer" [title]="form.id ? 'Edit category' : 'New category'" (close)="editing.set(null)">
        <div class="field"><label for="cat-name">Name <span class="req" aria-hidden="true">*</span></label><input id="cat-name" class="input" [(ngModel)]="form.name" placeholder="Espresso Drinks" required /></div>
        <div class="field"><label for="cat-order">Display order</label><input id="cat-order" class="input" type="number" [(ngModel)]="form.displayOrder" /></div>
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

  get canWrite() { return this.auth.can('catalog:write'); }

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
    req.subscribe(afterSave(this.saving, () => { this.editing.set(null); this.toast.success('Category saved'); this.load(); }));
  }

  async remove(c: CategoryResponse) {
    const ok = await this.confirm.ask({ title: `Delete ${c.name}?`, danger: true, confirmText: 'Delete' });
    if (!ok) return;
    this.api.deleteCategory(c.id).subscribe({ next: () => { this.toast.success('Deleted'); this.load(); } });
  }
}
