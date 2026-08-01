import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { CatalogService } from '../../core/api.services';
import { AuthService } from '../../core/auth.service';
import { ToastService } from '../../core/toast.service';
import { ConfirmService } from '../../core/confirm.service';
import { ModalComponent } from '../../shared/modal';
import { IconComponent } from '../../shared/icon';
import { CategoryRequest, CategoryResponse } from '../../core/models';

@Component({
  selector: 'app-categories',
  standalone: true,
  imports: [FormsModule, ModalComponent, IconComponent],
  template: `
    <div class="page">
      <div class="page-head">
        <div><h1>Categories</h1><div class="sub">Menu groupings that order products on the POS.</div></div>
        @if (auth.can('catalog:write')) { <button class="btn btn-primary" (click)="openNew()"><app-icon name="plus" [size]="16" /> New category</button> }
      </div>

      @if (loading()) { <div class="card"><div class="loading-block"><span class="spinner"></span> Loading…</div></div> }
      @else if (!items().length) {
        <div class="card"><div class="empty"><div class="big"><app-icon name="tag" [size]="30" /></div>No categories yet — hang your first tag.</div></div>
      }
      @else {
        <div class="rail-wall niche">
          <div class="tag-grid" role="list">
            @for (c of items(); track c.id) {
              <div class="tag-slot" role="listitem">
                <div class="rail shelf-board" aria-hidden="true"></div>
                <div class="tag-hang reveal-zone">
                  <button type="button" class="swing-tag tag-body obj-btn" [class.static]="!canWrite"
                          (click)="canWrite && openEdit(c)" [tabindex]="canWrite ? null : -1"
                          [attr.aria-label]="canWrite ? 'Edit ' + c.name : null" [class.dusty]="!c.active">
                    <span class="order-chip">#{{ c.displayOrder }}</span>
                    <b class="tag-name">{{ c.name }}</b>
                    <span class="badge dot tag-status" [class]="c.active ? 'badge-green' : 'badge-gray'">{{ c.active ? 'Active' : 'Inactive' }}</span>
                  </button>
                  @if (canWrite) {
                    <button type="button" class="btn btn-sm btn-icon obj-del tag-del" (click)="remove(c)"
                            [attr.aria-label]="'Delete ' + c.name"><app-icon name="trash" [size]="15" /></button>
                  }
                </div>
              </div>
            }
          </div>
        </div>
      }
    </div>

    @if (editing()) {
      <app-modal [title]="form.id ? 'Edit category' : 'New category'" (close)="editing.set(null)">
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
  styles: [`
    .rail-wall { padding: 1.7rem 1.8rem 2.4rem; }
    .tag-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(190px, 1fr)); column-gap: 0; row-gap: 2.4rem; }
    .tag-slot { display: flex; flex-direction: column; min-width: 0; }
    .rail { width: 100%; height: 12px; }
    .tag-slot:first-child .rail { border-top-left-radius: 6px; border-bottom-left-radius: 7px; }
    .tag-slot:last-child .rail { border-top-right-radius: 6px; border-bottom-right-radius: 7px; }
    .tag-hang { position: relative; display: flex; justify-content: center; }
    .tag-body {
      display: flex; flex-direction: column; align-items: center; gap: .45rem;
      width: 152px; margin-top: 22px; padding: 1.7rem .8rem .95rem;
    }
    /* hand-hung: slight alternating lean that straightens on hover */
    .tag-slot:nth-child(3n) .tag-body { rotate: -1.4deg; }
    .tag-slot:nth-child(3n+1) .tag-body { rotate: 1.1deg; }
    .tag-body:not(.static):hover { rotate: 0deg; }
    .order-chip {
      font-family: var(--font-mono); font-size: 10.5px; color: var(--paper-ink-soft);
      background: rgba(255,255,255,.55); border: 1px solid var(--paper-line);
      border-radius: 5px; padding: 0 .4rem;
    }
    .tag-name { font-size: 14px; font-weight: 650; color: var(--paper-ink); text-align: center; overflow-wrap: anywhere; }
    .tag-status { height: 20px; font-size: 10.5px; padding: 0 .5rem; }
    .tag-del { top: 8px; right: calc(50% - 88px); }
  `],
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
