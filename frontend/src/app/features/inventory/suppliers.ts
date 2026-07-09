import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { InventoryService } from '../../core/api.services';
import { AuthService } from '../../core/auth.service';
import { ConfirmService } from '../../core/confirm.service';
import { SupplierRequest, SupplierResponse } from '../../core/models';
import { ToastService } from '../../core/toast.service';
import { ModalComponent } from '../../shared/modal';

@Component({
  selector: 'app-suppliers',
  standalone: true,
  imports: [FormsModule, ModalComponent],
  template: `
    <div class="page">
      <div class="page-head">
        <div><h1>Suppliers</h1><div class="sub">Vendor contacts for branch purchasing.</div></div>
        @if (auth.can('inventory:write')) { <button class="btn btn-primary" (click)="openNew()">＋ New supplier</button> }
      </div>

      <div class="card">
        @if (loading()) { <div class="loading-block"><span class="spinner"></span> Loading…</div> }
        @else {
          <div class="table-wrap">
            <table class="data">
              <thead><tr><th>Name</th><th>Contact</th><th>Address</th><th>Status</th><th></th></tr></thead>
              <tbody>
                @for (s of items(); track s.id) {
                  <tr>
                    <td><b>{{ s.name }}</b></td>
                    <td>
                      <div>{{ s.contactPhone || '—' }}</div>
                      <div class="soft">{{ s.contactEmail || '' }}</div>
                    </td>
                    <td class="soft">{{ s.address || '—' }}</td>
                    <td><span class="badge" [class]="s.active ? 'badge-green' : 'badge-gray'">{{ s.active ? 'Active' : 'Inactive' }}</span></td>
                    <td class="row-actions">
                      @if (auth.can('inventory:write')) {
                        <button class="btn btn-sm btn-ghost" (click)="openEdit(s)">Edit</button>
                        <button class="btn btn-sm btn-ghost" (click)="remove(s)">🗑</button>
                      }
                    </td>
                  </tr>
                } @empty { <tr><td colspan="5"><div class="empty"><div class="big">📦</div>No suppliers yet</div></td></tr> }
              </tbody>
            </table>
          </div>
        }
      </div>
    </div>

    @if (editing()) {
      <app-modal [title]="form.id ? 'Edit supplier' : 'New supplier'" (close)="editing.set(null)">
        <div class="field"><label>Name</label><input class="input" [(ngModel)]="form.name" placeholder="Acme Foods" /></div>
        <div class="two-col">
          <div class="field"><label>Phone</label><input class="input" [(ngModel)]="form.contactPhone" /></div>
          <div class="field"><label>Email</label><input class="input" [(ngModel)]="form.contactEmail" type="email" /></div>
        </div>
        <div class="field"><label>Address</label><textarea class="input" [(ngModel)]="form.address" rows="3"></textarea></div>
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

  load() {
    this.loading.set(true);
    this.api.listSuppliers().subscribe({
      next: (items) => { this.items.set(items); this.loading.set(false); },
      error: () => this.loading.set(false),
    });
  }

  openNew() { this.form = { name: '', contactPhone: '', contactEmail: '', address: '', active: true }; this.editing.set({} as SupplierResponse); }
  openEdit(s: SupplierResponse) { this.form = { id: s.id, name: s.name, contactPhone: s.contactPhone, contactEmail: s.contactEmail, address: s.address, active: s.active }; this.editing.set(s); }

  save() {
    if (!this.form.name) { this.toast.error('Supplier name is required'); return; }
    this.saving.set(true);
    const { id, ...body } = this.form;
    const req = id ? this.api.updateSupplier(id, body) : this.api.createSupplier(body);
    req.subscribe({
      next: () => { this.saving.set(false); this.editing.set(null); this.toast.success('Supplier saved'); this.load(); },
      error: () => this.saving.set(false),
    });
  }

  async remove(s: SupplierResponse) {
    const ok = await this.confirm.ask({ title: `Delete ${s.name}?`, danger: true, confirmText: 'Delete' });
    if (!ok) return;
    this.api.deleteSupplier(s.id).subscribe({ next: () => { this.toast.success('Deleted'); this.load(); } });
  }
}
