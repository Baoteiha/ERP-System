import { stubLocalStorage } from '../../core/test-storage';
import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { AuthService } from '../../core/auth.service';
import { ShiftResponse } from '../../core/models';
import { ShiftsComponent } from './shifts';

const shift = (employeeHasPin: boolean): ShiftResponse => ({
  id: 's1', branchId: 'b1', employeeId: 'e1', employeeName: 'An', status: 'SCHEDULED',
  scheduledStart: '2026-10-01T01:00:00Z', scheduledEnd: '2026-10-01T09:00:00Z', employeeHasPin,
});

describe('ShiftsComponent PIN prompt', () => {
  let ctrl: HttpTestingController;
  let shifts: ShiftsComponent;

  beforeEach(() => {
    stubLocalStorage();
    TestBed.configureTestingModule({
      providers: [provideZonelessChangeDetection(), provideHttpClient(), provideHttpClientTesting()],
    });
    ctrl = TestBed.inject(HttpTestingController);
    shifts = TestBed.createComponent(ShiftsComponent).componentInstance;
    TestBed.tick();
    ctrl.match((r) => r.url.startsWith('/api/v1/staff')).forEach((r) => r.flush([]));
  });

  it('clocks straight in, without a prompt, for an employee with no PIN', () => {
    shifts.askPin(shift(false), 'in');

    expect(shifts.punching()).toBeNull();
    const req = ctrl.expectOne('/api/v1/staff/shifts/s1/clock-in');
    expect(req.request.body).toEqual({});
    req.flush(shift(false));
    ctrl.match((r) => r.url.startsWith('/api/v1/staff')).forEach((r) => r.flush([]));
  });

  it('opens the PIN prompt for an employee with a PIN and sends it', () => {
    shifts.askPin(shift(true), 'out');

    expect(shifts.punching()?.action).toBe('out');
    ctrl.expectNone('/api/v1/staff/shifts/s1/clock-out');
    shifts.pin = '1234';
    shifts.confirmPunch();
    const req = ctrl.expectOne('/api/v1/staff/shifts/s1/clock-out');
    expect(req.request.body).toEqual({ pin: '1234' });
    req.flush(shift(true));
    ctrl.match((r) => r.url.startsWith('/api/v1/staff')).forEach((r) => r.flush([]));
  });
});

describe('ShiftsComponent on a branch switch', () => {
  it('reloads the week on screen, the open shifts and the employees for the new branch', () => {
    stubLocalStorage();
    TestBed.configureTestingModule({
      providers: [provideZonelessChangeDetection(), provideHttpClient(), provideHttpClientTesting()],
    });
    const ctrl = TestBed.inject(HttpTestingController);
    const auth = TestBed.inject(AuthService);
    auth.activeBranchId.set('b1');
    const shifts = TestBed.createComponent(ShiftsComponent).componentInstance;
    TestBed.tick();
    ctrl.match((r) => r.url.startsWith('/api/v1/staff')).forEach((r) => r.flush([]));
    shifts.moveWeek(1);
    const weekOf = (r: { url: string }) => r.url === '/api/v1/staff/shifts';
    const week = ctrl.expectOne(weekOf).request.params.get('from');
    ctrl.match((r) => r.url.startsWith('/api/v1/staff')).forEach((r) => r.flush([]));

    auth.setActiveBranch('b2');
    TestBed.tick();

    const reload = ctrl.expectOne(weekOf);
    expect(reload.request.params.get('from')).toBe(week);
    reload.flush([shift(false)]);
    ctrl.expectOne('/api/v1/staff/shifts/open').flush([]);
    ctrl.expectOne('/api/v1/staff/employees').flush([]);
    expect(shifts.shifts().map((s) => s.id)).toEqual(['s1']);
    ctrl.verify();
  });
});
