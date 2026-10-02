import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpRequest, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { stubLocalStorage } from '../../core/test-storage';
import { AuthService } from '../../core/auth.service';
import { MeResponse } from '../../core/models';
import { DashboardComponent, pct } from './dashboard';

describe('pct', () => {
  it('is the change against the previous value', () => expect(pct(120, 100)).toBeCloseTo(0.2));
  it('is null when there is nothing to compare with', () => expect(pct(50, 0)).toBeNull());
  it('is negative for a fall', () => expect(pct(80, 100)).toBeCloseTo(-0.2));
});

describe('DashboardComponent', () => {
  const summary = (net: string, orders: number) => ({
    orderCount: orders, grossSales: net, discountTotal: '0', netSales: net, taxTotal: '0', grandTotal: net,
    cogs: '0', laborCost: '0', grossMargin: net, netMargin: net,
  });
  const page = { content: [], page: 0, size: 25, totalElements: 0, totalPages: 0 };
  const isReport = (r: HttpRequest<unknown>) => r.url.includes('/reports/');
  let ctrl: HttpTestingController;
  let auth: AuthService;

  /** Signs in with these permissions at branches b1 and b2, then renders the dashboard. */
  function start(permissions: string[]) {
    stubLocalStorage();
    TestBed.configureTestingModule({
      providers: [provideZonelessChangeDetection(), provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    });
    ctrl = TestBed.inject(HttpTestingController);
    auth = TestBed.inject(AuthService);
    auth.me.set({
      userId: 'u', email: 'a@b.c', companyId: 'c', branchIds: ['b1', 'b2'], roles: [], permissions,
      permissionsByBranch: { b1: permissions, b2: permissions },
    } as unknown as MeResponse);
    auth.activeBranchId.set('b1');
    const f = TestBed.createComponent(DashboardComponent);
    f.detectChanges();
    TestBed.tick();
    return f;
  }
  const text = (f: ComponentFixture<DashboardComponent>) => f.nativeElement as HTMLElement;
  const values = (f: ComponentFixture<DashboardComponent>) =>
    [...text(f).querySelectorAll('.kpi-value')].map((e) => e.textContent?.trim());
  /** Answers the three report calls of one load; the matching requests are taken in order. */
  function answerReports(today: object, yesterday: object = summary('0', 0)) {
    const reqs = ctrl.match(isReport);
    expect(reqs.length).toBe(3);
    reqs.find((r) => r.request.url.endsWith('/summary') && !r.request.params.has('from'))!.flush(today);
    reqs.find((r) => r.request.url.endsWith('/summary') && r.request.params.has('from'))!.flush(yesterday);
    reqs.find((r) => r.request.url.endsWith('/sales-by-day'))!.flush([]);
  }
  afterEach(() => ctrl.verify());

  it('shows the average order as a dash when there are no orders', () => {
    const f = start(['report:read']);
    answerReports(summary('0', 0));
    TestBed.tick(); f.detectChanges();
    expect(values(f)[2]).toBe('—');
  });

  it('shows the three fallback tiles without report:read', () => {
    const f = start(['sales:read', 'catalog:read', 'inventory:read']);
    ctrl.expectOne((r) => r.url === '/api/v1/orders').flush(page);
    ctrl.expectOne((r) => r.url === '/api/v1/products').flush(page);
    ctrl.expectOne('/api/v1/stock/low').flush([]);
    TestBed.tick(); f.detectChanges();
    const labels = [...text(f).querySelectorAll('.kpi-label')].map((e) => e.textContent);
    expect(labels).toEqual(['Recent orders', 'Active products', 'Low-stock items']);
    expect(ctrl.match(isReport).length).toBe(0);
  });

  it('sends exactly one new request per permitted section when the branch changes', () => {
    const f = start(['sales:read', 'report:read']);
    ctrl.expectOne((r) => r.url === '/api/v1/orders').flush(page);
    answerReports(summary('10', 1));
    auth.setActiveBranch('b2');
    TestBed.tick(); f.detectChanges();
    expect(ctrl.match((r) => r.url === '/api/v1/orders').length).toBe(1);
    expect(ctrl.match(isReport).length).toBe(3);
  });

  it('drops a late reply for the branch it left', () => {
    const f = start(['report:read']);
    const lateA = ctrl.match(isReport); // branch b1, still in flight
    auth.setActiveBranch('b2');
    TestBed.tick();
    answerReports(summary('200', 2));
    TestBed.tick(); f.detectChanges();
    expect(lateA.every((r) => r.cancelled)).toBe(true);
    expect(values(f)[0]).toContain('200');
  });

  it('shows an alert with Retry when the report calls fail, and reloads on Retry', () => {
    const f = start(['report:read']);
    ctrl.match(isReport)[0].flush('x', { status: 500, statusText: 'err' }); // the other two calls are cancelled with it
    TestBed.tick(); f.detectChanges();
    const alert = text(f).querySelector('.kpi-error[role="alert"]');
    expect(alert?.textContent).toContain("Couldn't load today's figures.");
    (alert!.querySelector('button') as HTMLButtonElement).click();
    expect(ctrl.match(isReport).length).toBe(3);
  });
});
