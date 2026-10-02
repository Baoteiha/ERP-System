import { stubLocalStorage } from '../../core/test-storage';
import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Router } from '@angular/router';
import { AuthService } from '../../core/auth.service';
import { StockComponent } from './stock';

const url = (u: string) => (r: { url: string }) => r.url === u;

describe('StockComponent', () => {
  let ctrl: HttpTestingController;
  let stock: StockComponent;

  /** Answers the three requests a (re)load sends. */
  function flushLoad(stockRows: unknown[] = []) {
    ctrl.expectOne('/api/v1/ingredients').flush([]);
    ctrl.expectOne('/api/v1/stock').flush(stockRows);
    ctrl.expectOne(url('/api/v1/stock/movements')).flush({ content: [], page: 0, size: 30, totalElements: 0, totalPages: 0 });
  }

  beforeEach(() => {
    stubLocalStorage();
    TestBed.configureTestingModule({
      providers: [provideZonelessChangeDetection(), provideHttpClient(), provideHttpClientTesting()],
    });
    ctrl = TestBed.inject(HttpTestingController);
    TestBed.inject(AuthService).activeBranchId.set('b1');
    stock = TestBed.createComponent(StockComponent).componentInstance;
    TestBed.tick();
    flushLoad();
  });

  afterEach(() => ctrl.verify());

  it('reloads the stock and movements when the active branch changes', () => {
    TestBed.inject(AuthService).setActiveBranch('b2');
    TestBed.tick();

    flushLoad([{ ingredientId: 'i1', branchId: 'b2', quantityOnHand: '5', reorderLevel: '1', avgUnitCost: { amount: '1', currency: 'VND' }, belowReorderLevel: false }]);
    expect(stock.stock().length).toBe(1);
  });

  it('ignores a late answer from the branch it left, and clears the old rows at once', () => {
    const auth = TestBed.inject(AuthService);
    const row = (branchId: string, qty: string) => ({ ingredientId: 'i1', branchId, quantityOnHand: qty, reorderLevel: '1', avgUnitCost: { amount: '1', currency: 'VND' }, belowReorderLevel: false });
    const empty = { content: [], page: 0, size: 30, totalElements: 0, totalPages: 0 };
    stock.stock.set([row('b1', '9')]);
    stock.load(); // branch A's load, still in flight
    const lateA = ctrl.expectOne('/api/v1/stock');
    ctrl.expectOne('/api/v1/ingredients').flush([]);

    auth.setActiveBranch('b2');
    TestBed.tick();
    expect(stock.stock()).toEqual([]);
    ctrl.expectOne('/api/v1/ingredients').flush([]);
    ctrl.expectOne('/api/v1/stock').flush([row('b2', '3')]);
    ctrl.expectOne(url('/api/v1/stock/movements')).flush(empty);

    lateA.flush([row('b1', '9')]);

    expect(stock.stock().map((s) => s.quantityOnHand)).toEqual(['3']);
  });

  it('keys the movement icons by the real movement types', () => {
    expect(stock.pinIcon('PURCHASE_RECEIPT')).toBe('truck');
    expect(stock.pinIcon('SALE_DEPLETION')).toBe('receipt');
    expect(stock.pinIcon('SALE_REVERSAL')).toBe('receipt');
    expect(stock.pinIcon('ADJUSTMENT')).toBe('sliders');
    expect(stock.pinIcon('WASTE')).toBe('trash');
    expect(stock.typeClass('PURCHASE_RECEIPT')).toBe('badge-green');
    expect(stock.typeClass('SALE_DEPLETION')).toBe('badge-blue');
  });
});

describe('StockComponent route intent', () => {
  let router: Router;
  const setup = (perms: string[], param: string) => {
    stubLocalStorage();
    TestBed.configureTestingModule({
      providers: [provideZonelessChangeDetection(), provideHttpClient(), provideHttpClientTesting()],
    });
    const auth = TestBed.inject(AuthService);
    auth.me.set({ userId: 'u', email: 'a@b.c', companyId: 'co', branchIds: ['b1'], roles: [], permissions: perms, permissionsByBranch: { b1: perms } });
    auth.setActiveBranch('b1');
    router = TestBed.inject(Router);
    const nav = vi.spyOn(router, 'navigate').mockResolvedValue(true);
    const f = TestBed.createComponent(StockComponent);
    f.componentRef.setInput('action', param);
    TestBed.tick();
    return { f, nav, ctrl: TestBed.inject(HttpTestingController) };
  };

  it('opens the dialog once with the permission, and clears the param', () => {
    const { f, nav, ctrl } = setup(['inventory:write'], 'adjust');
    expect(f.componentInstance.mode() === 'adjust').toBe(true);
    expect(nav).toHaveBeenCalledWith([], { queryParams: { action: null }, queryParamsHandling: 'merge', replaceUrl: true });
    ctrl.match(() => true);
    void f;
  });

  it('does not open without the permission, but still clears the param', () => {
    const { nav, f, ctrl } = setup([], 'adjust');
    expect(f.componentInstance.mode() === 'adjust').toBe(false);
    expect(nav).toHaveBeenCalledWith([], { queryParams: { action: null }, queryParamsHandling: 'merge', replaceUrl: true });
    ctrl.match(() => true);
    void f;
  });
});

describe('StockComponent units', () => {
  it('shows quantities with the ingredient base unit and separators', () => {
    stubLocalStorage();
    TestBed.configureTestingModule({
      providers: [provideZonelessChangeDetection(), provideHttpClient(), provideHttpClientTesting()],
    });
    const ctrl = TestBed.inject(HttpTestingController);
    TestBed.inject(AuthService).activeBranchId.set('b1');
    const fixture = TestBed.createComponent(StockComponent);
    TestBed.tick();
    ctrl.expectOne('/api/v1/ingredients').flush([{ id: 'i1', name: 'Sữa tươi', baseUnit: 'ml', active: true }]);
    ctrl.expectOne('/api/v1/stock').flush([{ ingredientId: 'i1', branchId: 'b1', quantityOnHand: '28240', reorderLevel: '10000', avgUnitCost: { amount: '1', currency: 'VND' }, belowReorderLevel: false }]);
    ctrl.expectOne(url('/api/v1/stock/movements')).flush({
      content: [{ id: 'm1', ingredientId: 'i1', type: 'ADJUSTMENT', quantity: '1500', unitCost: { amount: '0', currency: 'VND' }, occurredAt: '2026-10-02T10:00:00Z' }],
      page: 0, size: 30, totalElements: 1, totalPages: 1,
    });
    fixture.detectChanges();
    const text = (fixture.nativeElement as HTMLElement).textContent!.replace(/\s+/g, ' ');
    expect(text).toContain('28.240 ml');
    expect(text).toContain('10.000 ml');
    expect(text).toContain('1.500 ml');
    ctrl.verify();
  });
});
