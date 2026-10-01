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

      @if (openShifts().length > 0) {
        <section class="card open-panel" aria-label="Open shifts">
          <h2 class="day-head">Open shifts</h2>
          @for (s of openShifts(); track s.id) {
            <div class="shift-row">
              <div class="shift-main">
                <b>{{ s.employeeName }}</b>
                <span class="badge dot badge-amber">In progress</span>
                @if (s.clockInAt) { <span class="soft">in since {{ s.clockInAt | date:'EEE d MMM HH:mm' }}</span> }
              </div>
              <div class="shift-actions">
                @if (auth.can('staff:clock')) { <button class="btn btn-sm btn-primary" (click)="askPin(s, 'out')" [disabled]="saving()">Clock out</button> }
                @if (auth.can('staff:write')) { <button class="btn btn-sm btn-ghost" (click)="openAdjust(s)" [disabled]="saving()">Adjust</button> }
              </div>
            </div>
          }
        </section>
      }

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
                        @if (auth.can('staff:clock')) { <button class="btn btn-sm btn-tinted" (click)="askPin(s, 'in')" [disabled]="saving()">Clock in</button> }
                        @if (auth.can('staff:write')) {
                          <button class="btn btn-sm btn-ghost" (click)="openReschedule(s)" [disabled]="saving()">Edit</button>
                          <button class="btn btn-sm btn-ghost" (click)="cancel(s)" [disabled]="saving()">Cancel</button>
                        }
                      }
                      @if (s.status === 'IN_PROGRESS') {
                        @if (auth.can('staff:clock')) { <button class="btn btn-sm btn-primary" (click)="askPin(s, 'out')" [disabled]="saving()">Clock out</button> }
                      }
                      @if (s.status === 'IN_PROGRESS' || s.status === 'COMPLETED') {
                        @if (auth.can('staff:write')) { <button class="btn btn-sm btn-ghost" (click)="openAdjust(s)" [disabled]="saving()">Adjust</button> }
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

    @if (punching(); as p) {
      <app-modal [title]="p.action === 'in' ? 'Clock in' : 'Clock out'" (close)="punching.set(null)">
        <p class="soft">{{ p.shift.employeeName }}: enter your PIN.</p>
        <div class="field">
          <label for="punch-pin">PIN</label>
          <input id="punch-pin" class="input" type="password" inputmode="numeric" autocomplete="off" maxlength="6" [(ngModel)]="pin" (keydown.enter)="confirmPunch()" />
        </div>
        <div footer>
          <button class="btn btn-outline" (click)="punching.set(null)">Cancel</button>
          <button class="btn btn-primary" (click)="confirmPunch()" [disabled]="saving()">
            @if (saving()) { <span class="spinner"></span> } {{ p.action === 'in' ? 'Clock in' : 'Clock out' }}
          </button>
        </div>
      </app-modal>
    }

    @if (adjusting(); as target) {
      <app-modal [title]="'Adjust shift'" (close)="adjusting.set(null)">
        <p class="soft">Correct {{ target.employeeName }}'s punch times. Labour cost is recalculated at the rate captured when they clocked in.</p>
        <div class="field"><label for="adj-in">Clock in</label><input id="adj-in" class="input" type="datetime-local" [(ngModel)]="adjustForm.clockIn" /></div>
        <div class="field"><label for="adj-out">Clock out <span class="req" aria-hidden="true">*</span></label><input id="adj-out" class="input" type="datetime-local" [(ngModel)]="adjustForm.clockOut" required /></div>
        <div footer>
          <button class="btn btn-outline" (click)="adjusting.set(null)">Cancel</button>
          <button class="btn btn-primary" (click)="saveAdjust()" [disabled]="saving()">
            @if (saving()) { <span class="spinner"></span> } Save
          </button>
        </div>
      </app-modal>
    }
  `,
  styles: [`
    .open-panel { margin-bottom: 1rem; padding: .75rem 1rem; }
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
  openShifts = signal<ShiftResponse[]>([]);
  adjusting = signal<ShiftResponse | null>(null);
  punching = signal<{ shift: ShiftResponse; action: 'in' | 'out' } | null>(null);
  pin = '';
  adjustForm = { clockIn: '', clockOut: '' };
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
    this.loadOpen();
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

  loadOpen() {
    this.staff.openShifts().subscribe({ next: (items) => this.openShifts.set(items) });
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
    let end = new Date(`${this.form.date}T${this.form.end}`);
    if (end.getTime() === start.getTime()) { this.toast.error('End time must differ from start time'); return; }
    // An end time earlier than the start is an overnight shift ending the next day.
    if (end < start) end = new Date(end.getFullYear(), end.getMonth(), end.getDate() + 1, end.getHours(), end.getMinutes());
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
        this.loadOpen();
      },
      error: () => this.saving.set(false),
    });
  }

  openAdjust(s: ShiftResponse) {
    this.adjusting.set(s);
    this.adjustForm = {
      clockIn: s.clockInAt ? this.toLocalInput(new Date(s.clockInAt)) : '',
      clockOut: s.clockOutAt ? this.toLocalInput(new Date(s.clockOutAt)) : '',
    };
  }

  private toLocalInput(d: Date) { return `${this.toDateInput(d)}T${this.toTimeInput(d)}`; }

  saveAdjust() {
    const target = this.adjusting();
    if (!target) return;
    if (!this.adjustForm.clockOut) { this.toast.error('Clock-out time is required'); return; }
    const body = {
      clockIn: this.adjustForm.clockIn ? new Date(this.adjustForm.clockIn).toISOString() : undefined,
      clockOut: new Date(this.adjustForm.clockOut).toISOString(),
    };
    this.saving.set(true);
    this.staff.adjustShift(target.id, body).subscribe({
      next: () => { this.saving.set(false); this.adjusting.set(null); this.toast.success('Shift adjusted'); this.load(); this.loadOpen(); },
      error: () => this.saving.set(false),
    });
  }

  /** Asks for the PIN only when the employee has one; otherwise punches straight away. */
  askPin(shift: ShiftResponse, action: 'in' | 'out') {
    if (!shift.employeeHasPin) { this.punch(shift, action); return; }
    this.pin = '';
    this.punching.set({ shift, action });
  }

  confirmPunch() {
    const target = this.punching();
    if (target) this.punch(target.shift, target.action, this.pin.trim() || undefined);
  }

  private punch(shift: ShiftResponse, action: 'in' | 'out', pin?: string) {
    const req = action === 'in' ? this.staff.clockIn(shift.id, pin) : this.staff.clockOut(shift.id, pin);
    this.saving.set(true);
    req.subscribe({
      next: () => {
        this.saving.set(false);
        this.punching.set(null);
        this.toast.success(action === 'in' ? 'Clocked in' : 'Clocked out');
        this.load();
        this.loadOpen();
      },
      // The modal stays open so a mistyped PIN can be corrected; the error toast explains.
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
      next: () => { this.saving.set(false); this.toast.success('Shift cancelled'); this.load(); this.loadOpen(); },
      error: () => this.saving.set(false),
    });
  }
}
