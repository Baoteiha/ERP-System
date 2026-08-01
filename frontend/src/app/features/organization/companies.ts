import { Component, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { OrganizationService } from '../../core/api.services';
import { AuthService } from '../../core/auth.service';
import { ToastService } from '../../core/toast.service';
import { ConfirmService } from '../../core/confirm.service';
import { ModalComponent } from '../../shared/modal';
import { IconComponent } from '../../shared/icon';
import { CompanyRequest, CompanyResponse } from '../../core/models';

@Component({
  selector: 'app-companies',
  standalone: true,
  imports: [FormsModule, ModalComponent, DatePipe, IconComponent],
  template: `
    <div class="page">
      <div class="page-head">
        <div><h1>Companies</h1><div class="sub">Top-level tenants that own branches and master data.</div></div>
        @if (auth.can('company:write')) {
          <button class="btn btn-primary" (click)="openNew()"><app-icon name="plus" [size]="16" /> New company</button>
        }
      </div>

      <div class="card">
        @if (loading()) { <div class="loading-block"><span class="spinner"></span> Loading…</div> }
        @else {
          <div class="table-wrap">
            <table class="data">
              <caption class="sr-only">Companies with code and creation date</caption>
              <thead><tr><th scope="col">Name</th><th scope="col">Code</th><th scope="col">Created</th><th scope="col"><span class="sr-only">Actions</span></th></tr></thead>
              <tbody>
                @for (c of items(); track c.id) {
                  <tr>
                    <td><b>{{ c.name }}</b></td>
                    <td><span class="badge badge-gray">{{ c.code }}</span></td>
                    <td class="soft">{{ c.createdAt | date:'mediumDate' }}</td>
                    <td class="row-actions">
                      @if (auth.can('company:write')) {
                        <button class="btn btn-sm btn-ghost" (click)="openEdit(c)">Edit</button>
                        <button class="btn btn-sm btn-ghost btn-icon" (click)="remove(c)" [attr.aria-label]="'Delete ' + c.name"><app-icon name="trash" [size]="16" /></button>
                      }
                    </td>
                  </tr>
                } @empty { <tr><td colspan="4"><div class="empty"><div class="big"><app-icon name="building" [size]="30" /></div>No companies yet</div></td></tr> }
              </tbody>
            </table>
          </div>
        }
      </div>
    </div>

    @if (editing()) {
      <app-modal [title]="form.id ? 'Edit company' : 'New company'" (close)="editing.set(null)">
        <div class="field">
          <label for="co-name">Name <span class="req" aria-hidden="true">*</span></label>
          <input id="co-name" class="input" [(ngModel)]="form.name" placeholder="Ess Coffee Co." required />
        </div>
        <div class="field">
          <label for="co-code">Code <span class="req" aria-hidden="true">*</span></label>
          <input id="co-code" class="input" [(ngModel)]="form.code" placeholder="ESS" maxlength="32" required />
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
})
export class CompaniesComponent {
  auth = inject(AuthService);
  private api = inject(OrganizationService);
  private toast = inject(ToastService);
  private confirm = inject(ConfirmService);

  items = signal<CompanyResponse[]>([]);
  loading = signal(true);
  editing = signal<CompanyResponse | null>(null);
  saving = signal(false);
  form: { id?: string } & CompanyRequest = { name: '', code: '' };

  constructor() { this.load(); }

  load() {
    this.loading.set(true);
    this.api.listCompanies().subscribe({
      next: (p) => { this.items.set(p.content); this.loading.set(false); },
      error: () => this.loading.set(false),
    });
  }

  openNew() { this.form = { name: '', code: '' }; this.editing.set({} as CompanyResponse); }
  openEdit(c: CompanyResponse) { this.form = { id: c.id, name: c.name, code: c.code }; this.editing.set(c); }

  save() {
    if (!this.form.name || !this.form.code) { this.toast.error('Name and code are required'); return; }
    this.saving.set(true);
    const body: CompanyRequest = { name: this.form.name, code: this.form.code };
    const req = this.form.id ? this.api.updateCompany(this.form.id, body) : this.api.createCompany(body);
    req.subscribe({
      next: () => { this.saving.set(false); this.editing.set(null); this.toast.success('Company saved'); this.load(); },
      error: () => this.saving.set(false),
    });
  }

  async remove(c: CompanyResponse) {
    const ok = await this.confirm.ask({ title: `Delete ${c.name}?`, message: 'This soft-deletes the company.', danger: true, confirmText: 'Delete' });
    if (!ok) return;
    this.api.deleteCompany(c.id).subscribe({ next: () => { this.toast.success('Deleted'); this.load(); } });
  }
}
