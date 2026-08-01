import { DatePipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { StaffService } from '../../core/api.services';
import { AuthService } from '../../core/auth.service';
import { EmployeeResponse, ShiftResponse, ShiftStatus } from '../../core/models';
import { ToastService } from '../../core/toast.service';
import { ConfirmService } from '../../core/confirm.service';
import { MoneyPipe } from '../../core/util';
import { ModalComponent } from '../../shared/modal';
import { IconComponent } from '../../shared/icon';

@Component({
  selector: 'app-shifts',
  standalone: true,
  imports: [FormsModule, DatePipe, MoneyPipe, ModalComponent, IconComponent],
  template: `
    <div class="page">
      <div class="page-head">
        <div><h1>Shifts</h1><div class="sub">Schedule and time clock for the active branch.</div></div>
        @if (auth.can('staff:write')) { <button class="btn btn-primary" (click)="openNew()"><app-icon name="plus" [size]="16" /> Schedule shift</button> }
      </div>

      <div class="week-bar">
        <div class="week-nav">
          <button class="btn btn-sm btn-outline" (click)="moveWeek(-1)">‹ Prev</button>
          <button class="btn btn-sm btn-outline" (click)="goToday()">Today</button>
          <button class="btn btn-sm btn-outline" (click)="moveWeek(1)">Next ›</button>
        </div>
        <div class="week-label">{{ weekStart() | date:'EEE d MMM' }} – {{ weekEnd() | date:'EEE d MMM' }}</div>
      </div>

      @if (loading()) { <div class="loading-block"><span class="spinner"></span> Loading…</div> }
      @else if (shifts().length === 0) {
        <div class="card"><div class="empty"><div class="big"><app-icon name="clock" [size]="30" /></div>No shifts this week — schedule the first one.</div></div>
      }
      @else {
        @for (day of days(); track day.key) {
          <section class="day">
            <h2 class="day-head">{{ day.date | date:'EEEE' }} · {{ day.date | date:'d MMM' }}</h2>
            @if (day.shifts.length === 0) { <div class="muted no-shifts">No shifts</div> }
            @else {
              <div class="card">
                @for (s of day.shifts; track s.id) {
                  <div class="shift-row">
                    <div class="shift-main">
                      <b>{{ s.employeeName }}</b>
                      <span class="soft time">{{ s.scheduledStart | date:'HH:mm' }}–{{ s.scheduledEnd | date:'HH:mm' }}</span>
                      <span class="badge dot" [class]="statusClass(s.status)">{{ statusLabel(s.status) }}</span>
                      @if (s.clockInAt || s.clockOutAt) {
                        <span class="soft">
                          @if (s.clockInAt) { in {{ s.clockInAt | date:'HH:mm' }} }
                          @if (s.clockInAt && s.clockOutAt) { · }
                          @if (s.clockOutAt) { out {{ s.clockOutAt | date:'HH:mm' }} }
                        </span>
                      }
                      @if (s.laborCost) { <span class="num">{{ s.laborCost | money }}</span> }
                      @if (s.note) { <span class="soft note">{{ s.note }}</span> }
                    </div>
                    <div class="shift-actions">
                      @if (s.status === 'SCHEDULED') {
                        @if (auth.can('staff:clock')) { <button class="btn btn-sm btn-tinted" (click)="clockIn(s)" [disabled]="saving()">Clock in</button> }
                        @if (auth.can('staff:write')) {
                          <button class="btn btn-sm btn-ghost" (click)="openReschedule(s)" [disabled]="saving()">Edit</button>
                          <button class="btn btn-sm btn-ghost" (click)="cancel(s)" [disabled]="saving()">Cancel</button>
                        }
                      }
                      @if (s.status === 'IN_PROGRESS') {
                        @if (auth.can('staff:clock')) { <button class="btn btn-sm btn-primary" (click)="clockOut(s)" [disabled]="saving()">Clock out</button> }
                      }
                    </div>
                  </div>
                }
              </div>
            }
          </section>
        }
      }
    </div>

    @if (modalOpen()) {
      <app-modal [title]="editing() ? 'Reschedule shift' : 'Schedule shift'" (close)="modalOpen.set(false)">
        @if (!editing()) {
          <div class="field">
            <label for="shift-employee">Employee <span class="req" aria-hidden="true">*</span></label>
            <select id="shift-employee" class="select" [(ngModel)]="form.employeeId" required>
              <option value="" disabled>Select…</option>
              @for (e of activeEmployees(); track e.id) { <option [value]="e.id">{{ e.fullName }}</option> }
            </select>
          </div>
        }
        <div class="field"><label for="shift-date">Date <span class="req" aria-hidden="true">*</span></label><input id="shift-date" class="input" type="date" [(ngModel)]="form.date" required /></div>
        <div class="two-col">
          <div class="field"><label for="shift-start">Start time <span class="req" aria-hidden="true">*</span></label><input id="shift-start" class="input" type="time" [(ngModel)]="form.start" required /></div>
          <div class="field"><label for="shift-end">End time <span class="req" aria-hidden="true">*</span></label><input id="shift-end" class="input" type="time" [(ngModel)]="form.end" required /></div>
        </div>
        <div class="field"><label for="shift-note">Note</label><input id="shift-note" class="input" maxlength="500" [(ngModel)]="form.note" /></div>
        <div footer>
          <button class="btn btn-outline" (click)="modalOpen.set(false)">Cancel</button>
          <button class="btn btn-primary" (click)="save()" [disabled]="saving()">
            @if (saving()) { <span class="spinner"></span> } {{ editing() ? 'Save' : 'Schedule' }}
          </button>
        </div>
      </app-modal>
    }
  `,
  styles: [`
    .week-bar { display: flex; align-items: center; justify-content: space-between; gap: 1rem; margin-bottom: 1rem; }
    .week-nav { display: flex; gap: .4rem; }
    .week-label { font-weight: 600; color: var(--text-soft); }
    .day { margin-bottom: 1rem; }
    .day-head { font-size: .95rem; margin: 0 0 .5rem; }
    .no-shifts { font-size: .85rem; padding: .15rem 0 .35rem; }
    .shift-row { display: flex; align-items: center; justify-content: space-between; gap: 1rem; padding: .55rem 0; border-bottom: 1px solid var(--hairline); }
    .shift-row:last-child { border-bottom: none; }
    .shift-main { display: flex; align-items: center; gap: .7rem; flex-wrap: wrap; min-width: 0; }
    .time { font-variant-numeric: tabular-nums; }
    .note { max-width: 220px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .shift-actions { display: flex; gap: .4rem; flex-shrink: 0; }
    .two-col { display: grid; grid-template-columns: 1fr 1fr; gap: 0 1rem; }
  `],
})
export class ShiftsComponent {
  auth = inject(AuthService);
  private staff = inject(StaffService);
  private toast = inject(ToastService);
  private confirm = inject(ConfirmService);

  shifts = signal<ShiftResponse[]>([]);
  employees = signal<EmployeeResponse[]>([]);
  loading = signal(true);
  saving = signal(false);
  modalOpen = signal(false);
  editing = signal<ShiftResponse | null>(null);
  weekStart = signal<Date>(this.snapToMonday(new Date()));

  form = { employeeId: '', date: '', start: '', end: '', note: '' };

  weekEnd = computed(() => this.addDays(this.weekStart(), 6));
  activeEmployees = computed(() => this.employees().filter((e) => e.active));

  days = computed(() => {
    const start = this.weekStart();
    const all = this.shifts();
    return Array.from({ length: 7 }, (_, i) => {
      const date = this.addDays(start, i);
      const key = this.dayKey(date);
      return { date, key, shifts: all.filter((s) => this.dayKey(new Date(s.scheduledStart)) === key) };
    });
  });

  constructor() {
    this.load();
    this.staff.listEmployees().subscribe({ next: (items) => this.employees.set(items) });
  }

  private snapToMonday(d: Date): Date {
    const day = new Date(d.getFullYear(), d.getMonth(), d.getDate());
    const offset = (day.getDay() + 6) % 7; // Mon=0 … Sun=6
    day.setDate(day.getDate() - offset);
    return day;
  }
  private addDays(d: Date, n: number): Date {
    return new Date(d.getFullYear(), d.getMonth(), d.getDate() + n);
  }
  private dayKey(d: Date): string {
    return `${d.getFullYear()}-${d.getMonth() + 1}-${d.getDate()}`;
  }

  load() {
    this.loading.set(true);
    const from = this.weekStart();
    const to = this.addDays(from, 7);
    this.staff.listShifts(from.toISOString(), to.toISOString()).subscribe({
      next: (items) => { this.shifts.set(items); this.loading.set(false); },
      error: () => this.loading.set(false),
    });
  }

  moveWeek(delta: number) { this.weekStart.set(this.addDays(this.weekStart(), delta * 7)); this.load(); }
  goToday() { this.weekStart.set(this.snapToMonday(new Date())); this.load(); }

  statusClass(s: ShiftStatus) {
    if (s === 'SCHEDULED') return 'badge-blue';
    if (s === 'IN_PROGRESS') return 'badge-amber';
    if (s === 'COMPLETED') return 'badge-green';
    return 'badge-gray';
  }
  statusLabel(s: ShiftStatus) {
    if (s === 'SCHEDULED') return 'Scheduled';
    if (s === 'IN_PROGRESS') return 'In progress';
    if (s === 'COMPLETED') return 'Completed';
    return 'Cancelled';
  }

  openNew() {
    this.editing.set(null);
    this.form = { employeeId: '', date: '', start: '', end: '', note: '' };
    this.modalOpen.set(true);
  }

  openReschedule(s: ShiftResponse) {
    this.editing.set(s);
    const start = new Date(s.scheduledStart);
    const end = new Date(s.scheduledEnd);
    this.form = {
      employeeId: s.employeeId,
      date: this.toDateInput(start),
      start: this.toTimeInput(start),
      end: this.toTimeInput(end),
      note: s.note ?? '',
    };
    this.modalOpen.set(true);
  }

  private pad(n: number) { return String(n).padStart(2, '0'); }
  private toDateInput(d: Date) { return `${d.getFullYear()}-${this.pad(d.getMonth() + 1)}-${this.pad(d.getDate())}`; }
  private toTimeInput(d: Date) { return `${this.pad(d.getHours())}:${this.pad(d.getMinutes())}`; }

  save() {
    const editing = this.editing();
    if (!editing && !this.form.employeeId) { this.toast.error('Pick an employee'); return; }
    if (!this.form.date || !this.form.start || !this.form.end) { this.toast.error('Date, start and end time are required'); return; }
    const start = new Date(`${this.form.date}T${this.form.start}`);
    const end = new Date(`${this.form.date}T${this.form.end}`);
    if (end <= start) { this.toast.error('End time must be after start time'); return; }
    const body = { scheduledStart: start.toISOString(), scheduledEnd: end.toISOString(), note: this.form.note || undefined };
    this.saving.set(true);
    const req = editing
      ? this.staff.rescheduleShift(editing.id, body)
      : this.staff.scheduleShift({ employeeId: this.form.employeeId, ...body });
    req.subscribe({
      next: () => {
        this.saving.set(false);
        this.modalOpen.set(false);
        this.toast.success(editing ? 'Shift rescheduled' : 'Shift scheduled');
        this.load();
      },
      error: () => this.saving.set(false),
    });
  }

  clockIn(s: ShiftResponse) {
    this.saving.set(true);
    this.staff.clockIn(s.id).subscribe({
      next: () => { this.saving.set(false); this.toast.success('Clocked in'); this.load(); },
      error: () => this.saving.set(false),
    });
  }

  clockOut(s: ShiftResponse) {
    this.saving.set(true);
    this.staff.clockOut(s.id).subscribe({
      next: () => { this.saving.set(false); this.toast.success('Clocked out'); this.load(); },
      error: () => this.saving.set(false),
    });
  }

  async cancel(s: ShiftResponse) {
    const ok = await this.confirm.ask({
      title: 'Cancel this shift?',
      message: `Cancels ${s.employeeName}'s shift. This cannot be undone.`,
      danger: true, confirmText: 'Cancel shift',
    });
    if (!ok) return;
    this.saving.set(true);
    this.staff.cancelShift(s.id).subscribe({
      next: () => { this.saving.set(false); this.toast.success('Shift cancelled'); this.load(); },
      error: () => this.saving.set(false),
    });
  }
}
