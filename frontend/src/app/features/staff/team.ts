import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { StaffService } from '../../core/api.services';
import { AuthService } from '../../core/auth.service';
import { EmployeeResponse } from '../../core/models';
import { ToastService } from '../../core/toast.service';
import { afterSave } from '../../core/save-handler';
import { MoneyPipe } from '../../core/util';
import { ModalComponent } from '../../shared/modal';
import { IconComponent } from '../../shared/icon';
import { StatusBadgeComponent } from '../../shared/status-badge';

@Component({
  selector: 'app-team',
  standalone: true,
  imports: [FormsModule, MoneyPipe, ModalComponent, IconComponent, StatusBadgeComponent],
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
              <thead><tr><th scope="col">Name</th><th scope="col">Position</th>@if (showPay()) { <th scope="col" class="right">Hourly rate</th><th scope="col">Contact</th> }<th scope="col">Status</th><th scope="col"><span class="sr-only">Actions</span></th></tr></thead>
              <tbody>
                @for (e of items(); track e.id) {
                  <tr>
                    <td><span class="name-cell"><span class="mini-coin" [class.dusty]="!e.active" aria-hidden="true">{{ e.fullName.slice(0, 2).toUpperCase() }}</span><b>{{ e.fullName }}</b></span></td>
                    <td class="soft">{{ e.position || '—' }}</td>
                    @if (showPay()) {
                      <td class="num">{{ e.hourlyRate | money }} <span class="muted">/h</span></td>
                      <td>
                        <div>{{ e.phone || '—' }}</div>
                        @if (e.email) { <div class="soft">{{ e.email }}</div> }
                      </td>
                    }
                    <td><app-status-badge [active]="e.active" /></td>
                    <td class="row-actions">
                      @if (auth.can('staff:write')) { <button class="btn btn-sm btn-ghost" (click)="openEdit(e)">Edit</button> }
                      @if (auth.can('staff:write')) { <button class="btn btn-sm btn-ghost" (click)="openPin(e)">Set PIN</button> }
                    </td>
                  </tr>
                } @empty { <tr><td [attr.colspan]="showPay() ? 6 : 4"><div class="empty"><div class="big"><app-icon name="users" [size]="30" /></div>No employees yet — add your team.</div></td></tr> }
              </tbody>
            </table>
          </div>
        }
      </div>
    </div>

    @if (pinTarget(); as target) {
      <app-modal [title]="'Set PIN'" (close)="pinTarget.set(null)">
        <p class="soft">{{ target.fullName }} uses this PIN to clock in and out. Setting a new one replaces the old.</p>
        <div class="field">
          <label for="emp-pin">PIN <span class="req" aria-hidden="true">*</span></label>
          <input id="emp-pin" class="input" type="password" inputmode="numeric" autocomplete="off" maxlength="6" [(ngModel)]="pin" (keydown.enter)="savePin()" />
          <div class="hint">4 to 6 digits</div>
        </div>
        <div footer>
          <button class="btn btn-outline" (click)="pinTarget.set(null)">Cancel</button>
          <button class="btn btn-primary" (click)="savePin()" [disabled]="saving()">
            @if (saving()) { <span class="spinner"></span> } Save PIN
          </button>
        </div>
      </app-modal>
    }

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

  // The API sends rate and contact details only to staff:write holders.
  showPay = computed(() => this.auth.can('staff:write'));
  items = signal<EmployeeResponse[]>([]);
  loading = signal(true);
  saving = signal(false);
  modalOpen = signal(false);
  editing = signal<EmployeeResponse | null>(null);
  pinTarget = signal<EmployeeResponse | null>(null);
  pin = '';

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

  openPin(e: EmployeeResponse) {
    this.pin = '';
    this.pinTarget.set(e);
  }

  savePin() {
    const target = this.pinTarget();
    if (!target) return;
    if (!/^\d{4,6}$/.test(this.pin)) { this.toast.error('PIN must be 4 to 6 digits'); return; }
    this.saving.set(true);
    this.staff.setEmployeePin(target.id, this.pin).subscribe(afterSave(this.saving, () => { this.pinTarget.set(null); this.toast.success('PIN saved'); }));
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
