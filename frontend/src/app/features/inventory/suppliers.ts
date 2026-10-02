import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { InventoryService } from '../../core/api.services';
import { AuthService } from '../../core/auth.service';
import { ConfirmService } from '../../core/confirm.service';
import { SupplierRequest, SupplierResponse } from '../../core/models';
import { ToastService } from '../../core/toast.service';
import { afterSave } from '../../core/save-handler';
import { ModalComponent } from '../../shared/modal';
import { IconComponent } from '../../shared/icon';
import { StatusBadgeComponent } from '../../shared/status-badge';

@Component({
  selector: 'app-suppliers',
  standalone: true,
  imports: [FormsModule, ModalComponent, IconComponent, StatusBadgeComponent],
  template: `
    <div class="page">
      <div class="page-head">
        <div><h1>Suppliers</h1><div class="sub">Vendor contacts for branch purchasing.</div></div>
        @if (auth.can('inventory:write')) { <button class="btn btn-primary" (click)="openNew()"><app-icon name="plus" [size]="16" /> New supplier</button> }
      </div>

      @if (loading()) { <div class="card"><div class="loading-block"><span class="spinner"></span> Loading…</div></div> }
      @else if (!items().length) {
        <div class="card"><div class="empty"><div class="big"><app-icon name="boxes" [size]="30" /></div>No suppliers yet — schedule your first delivery.</div></div>
      }
      @else {
        <div class="card">
          <div class="table-wrap">
            <table class="data">
              <caption class="sr-only">Suppliers</caption>
              <thead>
                <tr>
                  <th scope="col">Name</th><th scope="col">Phone</th><th scope="col">Email</th><th scope="col">Status</th>
                  <th scope="col"><span class="sr-only">Actions</span></th>
                </tr>
              </thead>
              <tbody>
                @for (s of items(); track s.id) {
                  <tr [class.muted]="!s.active">
                    <td>
                      @if (canWrite) { <button type="button" class="row-open" (click)="openEdit(s)">{{ s.name }}</button> }
                      @else { <b>{{ s.name }}</b> }
                      @if (s.address) { <div class="soft addr">{{ s.address }}</div> }
                    </td>
                    <td>{{ s.contactPhone || '—' }}</td>
                    <td>{{ s.contactEmail || '—' }}</td>
                    <td><app-status-badge size="sm" [active]="s.active" /></td>
                    <td>
                      @if (canWrite) {
                        <div class="row-actions">
                          <button type="button" class="btn btn-sm btn-ghost btn-icon" (click)="openEdit(s)" [attr.aria-label]="'Edit ' + s.name"><app-icon name="edit" [size]="15" /></button>
                          <button type="button" class="btn btn-sm btn-ghost btn-icon" (click)="remove(s)" [attr.aria-label]="'Delete ' + s.name"><app-icon name="trash" [size]="15" /></button>
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
      <app-modal variant="drawer" [title]="form.id ? 'Edit supplier' : 'New supplier'" (close)="editing.set(null)">
        <div class="field"><label for="sup-name">Name <span class="req" aria-hidden="true">*</span></label><input id="sup-name" class="input" [(ngModel)]="form.name" placeholder="Acme Foods" required /></div>
        <div class="two-col">
          <div class="field"><label for="sup-phone">Phone</label><input id="sup-phone" class="input" [(ngModel)]="form.contactPhone" /></div>
          <div class="field"><label for="sup-email">Email</label><input id="sup-email" class="input" [(ngModel)]="form.contactEmail" type="email" /></div>
        </div>
        <div class="field"><label for="sup-address">Address</label><textarea id="sup-address" class="input" [(ngModel)]="form.address" rows="3"></textarea></div>
        <label class="checkbox"><input type="checkbox" [(ngModel)]="form.active" /> Active</label>
        <div footer>
          <button class="btn btn-outline" (click)="editing.set(null)">Cancel</button>
          <button class="btn btn-primary" (click)="save()" [disabled]="saving()">@if (saving()) { <span class="spinner"></span> } Save</button>
        </div>
      </app-modal>
    }
  `,
  styles: [`
    .addr { font-size: 12px; margin-top: .15rem; max-width: 36ch; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  `],
})
export class SuppliersComponent {
  auth = inject(AuthService);
  private api = inject(InventoryService);
  private toast = inject(ToastService);
  private confirm = inject(ConfirmService);

  items = signal<SupplierResponse[]>([]);
  loading = signal(true);
  editing = signal<SupplierResponse | null>(null);
  saving = signal(false);
  form: { id?: string } & SupplierRequest = { name: '', contactPhone: '', contactEmail: '', address: '', active: true };

  constructor() { this.load(); }

  get canWrite() { return this.auth.can('inventory:write'); }

  load() {
    this.loading.set(true);
    this.api.listSuppliers().subscribe({
      next: (items) => { this.items.set(items); this.loading.set(false); },
      error: () => this.loading.set(false),
    });
  }

  openNew() { this.form = { name: '', contactPhone: '', contactEmail: '', address: '', active: true }; this.editing.set({} as SupplierResponse); }
  openEdit(s: SupplierResponse) { this.form = { id: s.id, name: s.name, contactPhone: s.contactPhone, contactEmail: s.contactEmail, address: s.address, active: s.active, version: s.version }; this.editing.set(s); }

  save() {
    if (!this.form.name) { this.toast.error('Supplier name is required'); return; }
    this.saving.set(true);
    const { id, ...body } = this.form;
    const req = id ? this.api.updateSupplier(id, body) : this.api.createSupplier(body);
    req.subscribe(afterSave(this.saving, () => { this.editing.set(null); this.toast.success('Supplier saved'); this.load(); }));
  }

  async remove(s: SupplierResponse) {
    const ok = await this.confirm.ask({ title: `Delete ${s.name}?`, danger: true, confirmText: 'Delete' });
    if (!ok) return;
    this.api.deleteSupplier(s.id).subscribe({ next: () => { this.toast.success('Deleted'); this.load(); } });
  }
}
