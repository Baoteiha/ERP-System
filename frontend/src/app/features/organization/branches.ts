import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { OrganizationService } from '../../core/api.services';
import { AuthService } from '../../core/auth.service';
import { ToastService } from '../../core/toast.service';
import { ConfirmService } from '../../core/confirm.service';
import { ModalComponent } from '../../shared/modal';
import { IconComponent } from '../../shared/icon';
import { BranchRequest, BranchResponse, CompanyResponse } from '../../core/models';

@Component({
  selector: 'app-branches',
  standalone: true,
  imports: [FormsModule, ModalComponent, IconComponent],
  template: `
    <div class="page">
      <div class="page-head">
        <div><h1>Branches</h1><div class="sub">Physical outlets. A branch id scopes all operational data.</div></div>
        @if (auth.can('branch:write')) {
          <button class="btn btn-primary" (click)="openNew()"><app-icon name="plus" [size]="16" /> New branch</button>
        }
      </div>

      <div class="card">
        @if (loading()) { <div class="loading-block"><span class="spinner"></span> Loading…</div> }
        @else {
          <div class="table-wrap">
            <table class="data">
              <caption class="sr-only">Branches with code, address, phone and status</caption>
              <thead><tr><th scope="col">Name</th><th scope="col">Code</th><th scope="col">Address</th><th scope="col">Phone</th><th scope="col">Status</th><th scope="col"><span class="sr-only">Actions</span></th></tr></thead>
              <tbody>
                @for (b of items(); track b.id) {
                  <tr>
                    <td><span class="name-cell"><span class="mini-coin" aria-hidden="true"><app-icon name="store" [size]="14" /></span><b>{{ b.name }}</b></span></td>
                    <td><span class="badge badge-gray">{{ b.code }}</span></td>
                    <td class="soft">{{ b.address || '—' }}</td>
                    <td class="soft">{{ b.phone || '—' }}</td>
                    <td>
                      <span class="badge dot" [class.badge-green]="b.active" [class.badge-gray]="!b.active">
                        {{ b.active ? 'Active' : 'Inactive' }}
                      </span>
                    </td>
                    <td class="row-actions">
                      @if (auth.can('branch:write')) {
                        <button class="btn btn-sm btn-ghost" (click)="openEdit(b)">Edit</button>
                        <button class="btn btn-sm btn-ghost btn-icon" (click)="remove(b)" [attr.aria-label]="'Delete ' + b.name"><app-icon name="trash" [size]="16" /></button>
                      }
                    </td>
                  </tr>
                } @empty { <tr><td colspan="6"><div class="empty"><div class="big"><app-icon name="pin" [size]="30" /></div>No branches yet</div></td></tr> }
              </tbody>
            </table>
          </div>
        }
      </div>
    </div>

    @if (editing()) {
      <app-modal [title]="form.id ? 'Edit branch' : 'New branch'" (close)="editing.set(null)">
        <div class="field">
          <label for="br-company">Company <span class="req" aria-hidden="true">*</span></label>
          <select id="br-company" class="select" [(ngModel)]="form.companyId" required>
            <option value="" disabled>Select company…</option>
            @for (c of companies(); track c.id) { <option [value]="c.id">{{ c.name }}</option> }
          </select>
        </div>
        <div class="two-col">
          <div class="field"><label for="br-name">Name <span class="req" aria-hidden="true">*</span></label><input id="br-name" class="input" [(ngModel)]="form.name" placeholder="Downtown" required /></div>
          <div class="field"><label for="br-code">Code <span class="req" aria-hidden="true">*</span></label><input id="br-code" class="input" [(ngModel)]="form.code" placeholder="DT01" maxlength="32" required /></div>
        </div>
        <div class="field"><label for="br-address">Address</label><input id="br-address" class="input" [(ngModel)]="form.address" /></div>
        <div class="two-col">
          <div class="field"><label for="br-phone">Phone</label><input id="br-phone" class="input" [(ngModel)]="form.phone" /></div>
          <div class="field"><label for="br-active">Status</label>
            <label class="checkbox status-check"><input id="br-active" type="checkbox" [(ngModel)]="form.active" /> Active</label>
          </div>
        </div>
        <div footer>
          <button class="btn btn-outline" (click)="editing.set(null)">Cancel</button>
          <button class="btn btn-primary" (click)="save()" [disabled]="saving()">
            @if (saving()) { <span class="spinner"></span> } Save
          </button>
        </div>
      </app-modal>
    }
  `,
  styles: [`
    .two-col { display: grid; grid-template-columns: 1fr 1fr; gap: 0 1rem; }
    .status-check { height: 40px; }
  `],
})
export class BranchesComponent {
  auth = inject(AuthService);
  private api = inject(OrganizationService);
  private toast = inject(ToastService);
  private confirm = inject(ConfirmService);

  items = signal<BranchResponse[]>([]);
  companies = signal<CompanyResponse[]>([]);
  loading = signal(true);
  editing = signal<BranchResponse | null>(null);
  saving = signal(false);
  form: { id?: string } & BranchRequest = this.blank();

  constructor() {
    this.load();
    this.api.listCompanies().subscribe({ next: (p) => this.companies.set(p.content) });
  }

  private blank(): { id?: string } & BranchRequest {
    return { companyId: '', name: '', code: '', address: '', phone: '', active: true };
  }

  load() {
    this.loading.set(true);
    this.api.listBranches().subscribe({
      next: (p) => { this.items.set(p.content); this.loading.set(false); },
      error: () => this.loading.set(false),
    });
  }

  openNew() {
    this.form = this.blank();
    if (this.companies().length === 1) this.form.companyId = this.companies()[0].id;
    this.editing.set({} as BranchResponse);
  }
  openEdit(b: BranchResponse) {
    this.form = { id: b.id, companyId: b.companyId, name: b.name, code: b.code, address: b.address, phone: b.phone, active: b.active };
    this.editing.set(b);
  }

  save() {
    if (!this.form.companyId || !this.form.name || !this.form.code) { this.toast.error('Company, name and code are required'); return; }
    this.saving.set(true);
    const { id, ...body } = this.form;
    const req = id ? this.api.updateBranch(id, body) : this.api.createBranch(body);
    req.subscribe({
      next: () => { this.saving.set(false); this.editing.set(null); this.toast.success('Branch saved'); this.load(); },
      error: () => this.saving.set(false),
    });
  }

  async remove(b: BranchResponse) {
    const ok = await this.confirm.ask({ title: `Delete ${b.name}?`, danger: true, confirmText: 'Delete' });
    if (!ok) return;
    this.api.deleteBranch(b.id).subscribe({ next: () => { this.toast.success('Deleted'); this.load(); } });
  }
}
