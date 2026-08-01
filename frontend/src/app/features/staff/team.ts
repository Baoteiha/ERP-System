import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { StaffService } from '../../core/api.services';
import { AuthService } from '../../core/auth.service';
import { EmployeeResponse } from '../../core/models';
import { ToastService } from '../../core/toast.service';
import { MoneyPipe } from '../../core/util';
import { ModalComponent } from '../../shared/modal';
import { IconComponent } from '../../shared/icon';

@Component({
  selector: 'app-team',
  standalone: true,
  imports: [FormsModule, MoneyPipe, ModalComponent, IconComponent],
  template: `
    <div class="page">
      <div class="page-head">
        <div><h1>Team</h1><div class="sub">Employees, positions and hourly rates. Company-wide.</div></div>
        @if (auth.can('staff:write')) { <button class="btn btn-primary" (click)="openNew()"><app-icon name="plus" [size]="16" /> New employee</button> }
      </div>

      <div class="card">
        @if (loading()) { <div class="loading-block"><span class="spinner"></span> Loading…</div> }
        @else {
          <div class="table-wrap">
            <table class="data">
              <caption class="sr-only">Employees with position, hourly rate, contact details and status</caption>
              <thead><tr><th scope="col">Name</th><th scope="col">Position</th><th scope="col" class="right">Hourly rate</th><th scope="col">Contact</th><th scope="col">Status</th><th scope="col"><span class="sr-only">Actions</span></th></tr></thead>
              <tbody>
                @for (e of items(); track e.id) {
                  <tr>
                    <td><span class="name-cell"><span class="mini-coin" [class.dusty]="!e.active" aria-hidden="true">{{ e.fullName.slice(0, 2).toUpperCase() }}</span><b>{{ e.fullName }}</b></span></td>
                    <td class="soft">{{ e.position || '—' }}</td>
                    <td class="num">{{ e.hourlyRate | money }} <span class="muted">/h</span></td>
                    <td>
                      <div>{{ e.phone || '—' }}</div>
                      @if (e.email) { <div class="soft">{{ e.email }}</div> }
                    </td>
                    <td><span class="badge dot" [class]="e.active ? 'badge-green' : 'badge-gray'">{{ e.active ? 'Active' : 'Inactive' }}</span></td>
                    <td class="row-actions">
                      @if (auth.can('staff:write')) { <button class="btn btn-sm btn-ghost" (click)="openEdit(e)">Edit</button> }
                    </td>
                  </tr>
                } @empty { <tr><td colspan="6"><div class="empty"><div class="big"><app-icon name="users" [size]="30" /></div>No employees yet — add your team.</div></td></tr> }
              </tbody>
            </table>
          </div>
        }
      </div>
    </div>

    @if (modalOpen()) {
      <app-modal [title]="editing() ? 'Edit employee' : 'New employee'" (close)="modalOpen.set(false)">
        <div class="field"><label for="emp-fullname">Full name <span class="req" aria-hidden="true">*</span></label><input id="emp-fullname" class="input" [(ngModel)]="form.fullName" required /></div>
        <div class="field"><label for="emp-position">Position</label><input id="emp-position" class="input" [(ngModel)]="form.position" placeholder="Barista" /></div>
        <div class="field">
          <label for="emp-rate">Hourly rate</label>
          <input id="emp-rate" class="input" type="number" min="0" [(ngModel)]="form.hourlyRate" />
          <div class="hint">Per hour, in VND</div>
        </div>
        <div class="two-col">
          <div class="field"><label for="emp-phone">Phone</label><input id="emp-phone" class="input" [(ngModel)]="form.phone" /></div>
          <div class="field"><label for="emp-email">Email</label><input id="emp-email" class="input" type="email" [(ngModel)]="form.email" /></div>
        </div>
        @if (editing()) {
          <div class="field">
            <label class="check-label" for="emp-active"><input id="emp-active" type="checkbox" [(ngModel)]="form.active" /> Active</label>
          </div>
        }
        <div footer>
          <button class="btn btn-outline" (click)="modalOpen.set(false)">Cancel</button>
          <button class="btn btn-primary" (click)="save()" [disabled]="saving()">
            @if (saving()) { <span class="spinner"></span> } {{ editing() ? 'Save' : 'Create' }}
          </button>
        </div>
      </app-modal>
    }
  `,
  styles: [`
    .two-col { display: grid; grid-template-columns: 1fr 1fr; gap: 0 1rem; }
    .check-label { display: inline-flex; align-items: center; gap: .5rem; cursor: pointer; }
  `],
})
export class TeamComponent {
  auth = inject(AuthService);
  private staff = inject(StaffService);
  private toast = inject(ToastService);

  items = signal<EmployeeResponse[]>([]);
  loading = signal(true);
  saving = signal(false);
  modalOpen = signal(false);
  editing = signal<EmployeeResponse | null>(null);

  form = { fullName: '', position: '', hourlyRate: 0, phone: '', email: '', active: true };

  constructor() {
    this.load();
  }

  load() {
    this.loading.set(true);
    this.staff.listEmployees().subscribe({
      next: (items) => { this.items.set(items); this.loading.set(false); },
      error: () => this.loading.set(false),
    });
  }

  openNew() {
    this.editing.set(null);
    this.form = { fullName: '', position: '', hourlyRate: 0, phone: '', email: '', active: true };
    this.modalOpen.set(true);
  }

  openEdit(e: EmployeeResponse) {
    this.editing.set(e);
    this.form = {
      fullName: e.fullName,
      position: e.position ?? '',
      hourlyRate: Number(e.hourlyRate?.amount ?? 0),
      phone: e.phone ?? '',
      email: e.email ?? '',
      active: e.active,
    };
    this.modalOpen.set(true);
  }

  save() {
    if (!this.form.fullName.trim()) { this.toast.error('Full name is required'); return; }
    const body = {
      fullName: this.form.fullName.trim(),
      position: this.form.position || undefined,
      hourlyRate: String(this.form.hourlyRate || '0'),
      phone: this.form.phone || undefined,
      email: this.form.email || undefined,
      active: this.form.active,
    };
    this.saving.set(true);
    const editing = this.editing();
    const req = editing ? this.staff.updateEmployee(editing.id, body) : this.staff.createEmployee(body);
    req.subscribe({
      next: () => {
        this.saving.set(false);
        this.modalOpen.set(false);
        this.toast.success(editing ? 'Employee updated' : 'Employee created');
        this.load();
      },
      error: () => this.saving.set(false),
    });
  }
}
