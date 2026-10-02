import { stubLocalStorage } from '../../core/test-storage';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { By } from '@angular/platform-browser';
import { Router } from '@angular/router';
import { AuthService } from '../../core/auth.service';
import { THREE_LOADER, WEBGL_SUPPORTED } from '../../three/webgl';
import { StockComponent } from './stock';
import { StockShelvesComponent } from './stock-shelves';

const url = (u: string) => (r: { url: string }) => r.url === u;
/** The table-only specs run as on a device without WebGL, so they never probe a canvas. */
const noWebGL = { provide: WEBGL_SUPPORTED, useValue: () => false };

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
      providers: [provideZonelessChangeDetection(), provideHttpClient(), provideHttpClientTesting(), noWebGL],
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
      providers: [provideZonelessChangeDetection(), provideHttpClient(), provideHttpClientTesting(), noWebGL],
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

describe('StockComponent 3D view', () => {
  let ctrl: HttpTestingController;
  const ingredient = { id: 'i1', companyId: 'co', name: 'Sữa tươi', baseUnit: 'ml', category: 'Dairy', active: true, version: 0 };
  const row = (branchId: string, qty = '5000') =>
    ({ ingredientId: 'i1', branchId, quantityOnHand: qty, reorderLevel: '1000', avgUnitCost: { amount: '1', currency: 'VND' }, belowReorderLevel: false });
  const empty = { content: [], page: 0, size: 30, totalElements: 0, totalPages: 0 };

  /** Opens the stock page for a user who may write stock; `remembered` is the view stored from an earlier visit. */
  function setup(opts: { webgl?: boolean; remembered?: string; blocked?: boolean; perms?: string[] } = {}) {
    stubLocalStorage();
    if (opts.remembered) localStorage.setItem('erpcafe.stockView', opts.remembered);
    if (opts.blocked) {
      // only the view key is blocked: AuthService needs storage for its own keys
      const store = localStorage;
      const guard = <T>(k: string, fn: () => T) => { if (k === 'erpcafe.stockView') throw new Error('blocked'); return fn(); };
      vi.stubGlobal('localStorage', {
        ...store,
        getItem: (k: string) => guard(k, () => store.getItem(k)),
        setItem: (k: string, v: string) => guard(k, () => store.setItem(k, v)),
      });
    }
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(), provideHttpClient(), provideHttpClientTesting(),
        { provide: WEBGL_SUPPORTED, useValue: () => opts.webgl ?? true },
        { provide: THREE_LOADER, useValue: () => new Promise(() => undefined) },
      ],
    });
    ctrl = TestBed.inject(HttpTestingController);
    const auth = TestBed.inject(AuthService);
    const perms = opts.perms ?? ['inventory:read', 'inventory:write'];
    auth.me.set({ userId: 'u', email: 'a@b.c', companyId: 'co', branchIds: ['b1', 'b2'], roles: [], permissions: perms, permissionsByBranch: { b1: perms, b2: perms } });
    auth.setActiveBranch('b1');
    const f = TestBed.createComponent(StockComponent);
    f.detectChanges();
    TestBed.tick();
    ctrl.expectOne('/api/v1/ingredients').flush([ingredient]);
    ctrl.expectOne('/api/v1/stock').flush([row('b1')]);
    ctrl.expectOne(url('/api/v1/stock/movements')).flush(empty);
    f.detectChanges();
    return f;
  }
  const el = (f: ComponentFixture<StockComponent>) => f.nativeElement as HTMLElement;
  const viewButtons = (f: ComponentFixture<StockComponent>) =>
    [...el(f).querySelectorAll<HTMLButtonElement>('.view-switch[role="group"][aria-label="Stock view"] button')];
  const shelves = (f: ComponentFixture<StockComponent>) => f.debugElement.query(By.directive(StockShelvesComponent));
  afterEach(() => ctrl.verify());

  it('switches to 3D with a pressed-state button, shows the shelves, and remembers the choice', () => {
    const f = setup();
    expect(viewButtons(f).map((b) => b.textContent?.trim())).toEqual(['Table', '3D']);
    expect(viewButtons(f).map((b) => b.getAttribute('aria-pressed'))).toEqual(['true', 'false']);
    expect(shelves(f)).toBeNull();

    viewButtons(f)[1].click();
    f.detectChanges();
    expect(f.componentInstance.stockView()).toBe('3d');
    expect(localStorage.getItem('erpcafe.stockView')).toBe('3d');
    expect(viewButtons(f).map((b) => b.getAttribute('aria-pressed'))).toEqual(['false', 'true']);
    expect(shelves(f)).not.toBeNull();
    expect(el(f).querySelector('table.data')).toBeNull();

    viewButtons(f)[0].click();
    f.detectChanges();
    expect(localStorage.getItem('erpcafe.stockView')).toBe('table');
    expect(shelves(f)).toBeNull();
    expect(el(f).querySelector('table.data')).not.toBeNull();
  });

  it('starts on the table when storage is blocked, and a switch to 3D still applies for the visit', () => {
    const f = setup({ blocked: true });
    expect(f.componentInstance.stockView()).toBe('table');
    viewButtons(f)[1].click();
    f.detectChanges();
    expect(f.componentInstance.stockView()).toBe('3d');
    expect(shelves(f)).not.toBeNull();
  });

  it('opens in 3D when 3D was the remembered view', () => {
    const f = setup({ remembered: '3d' });
    expect(f.componentInstance.stockView()).toBe('3d');
    expect(shelves(f)).not.toBeNull();
    expect((shelves(f).componentInstance as StockShelvesComponent).shelves()[0].containers[0].name).toBe('Sữa tươi');
  });

  it('hides 3D and shows the table with a note when the device has no WebGL, keeping the remembered choice', () => {
    const f = setup({ webgl: false, remembered: '3d' });
    expect(f.componentInstance.stockView()).toBe('table');
    expect(viewButtons(f).map((b) => b.textContent?.trim())).toEqual(['Table']);
    expect(el(f).querySelectorAll('table.data tbody tr').length).toBe(1);
    expect(el(f).querySelector('p.view-note')?.textContent).toContain("3D view isn't available on this device.");
    expect(localStorage.getItem('erpcafe.stockView')).toBe('3d');
  });

  it('opens the adjust drawer for the container a pick names, and shows its movements', () => {
    const f = setup({ remembered: '3d' });
    el(f).querySelector<HTMLButtonElement>('app-stock-shelves button.shelf-label')!.click();
    f.detectChanges();
    expect(el(f).querySelector('app-modal')).not.toBeNull();
    expect(f.componentInstance.mode()).toBe('adjust');
    expect(f.componentInstance.form.ingredientId).toBe('i1');
    expect(f.componentInstance.selectedIngredientId()).toBe('i1');
    ctrl.expectOne((r) => r.url === '/api/v1/stock/movements' && r.params.get('ingredientId') === 'i1').flush(empty);
  });

  it('only selects a picked container for a user who may not write stock', () => {
    const f = setup({ remembered: '3d', perms: ['inventory:read'] });
    el(f).querySelector<HTMLButtonElement>('app-stock-shelves button.shelf-label')!.click();
    f.detectChanges();
    expect(f.componentInstance.selectedIngredientId()).toBe('i1');
    expect(f.componentInstance.mode()).toBeNull();
    expect(el(f).querySelector('app-modal')).toBeNull();
    ctrl.expectOne((r) => r.url === '/api/v1/stock/movements' && r.params.get('ingredientId') === 'i1').flush(empty);
  });

  it('moves focus to the Table button when the 3D view it was on cannot start', () => {
    const f = setup();
    const d3 = viewButtons(f)[1];
    d3.focus();
    d3.click();
    f.detectChanges();
    (shelves(f).componentInstance as StockShelvesComponent).unavailable.emit();
    f.detectChanges();
    expect(viewButtons(f).length).toBe(1);
    expect(document.activeElement).toBe(viewButtons(f)[0]);
    expect(viewButtons(f)[0].getAttribute('aria-pressed')).toBe('true');
  });

  it('rebuilds the same shelves for the new branch when the branch changes in 3D', () => {
    const f = setup({ remembered: '3d' });
    const before = shelves(f).componentInstance as StockShelvesComponent;
    TestBed.inject(AuthService).setActiveBranch('b2');
    TestBed.tick();
    f.detectChanges();
    ctrl.expectOne('/api/v1/ingredients').flush([ingredient]);
    ctrl.expectOne('/api/v1/stock').flush([row('b2', '42')]);
    ctrl.expectOne(url('/api/v1/stock/movements')).flush(empty);
    f.detectChanges();
    const after = shelves(f).componentInstance as StockShelvesComponent;
    expect(after).toBe(before); // kept, not remounted
    expect(after.shelves()[0].containers[0].onHand).toBe(42);
  });

  it('falls back to the table with the note when the 3D view cannot start, keeping the remembered choice', () => {
    const f = setup({ remembered: '3d' });
    (shelves(f).componentInstance as StockShelvesComponent).unavailable.emit();
    f.detectChanges();
    expect(f.componentInstance.stockView()).toBe('table');
    expect(viewButtons(f).map((b) => b.textContent?.trim())).toEqual(['Table']);
    expect(el(f).querySelector('table.data')).not.toBeNull();
    expect(el(f).querySelector('p.view-note')?.textContent).toContain("3D view isn't available on this device.");
    expect(localStorage.getItem('erpcafe.stockView')).toBe('3d');
  });
});

describe('StockComponent units', () => {
  it('shows quantities with the ingredient base unit and separators', () => {
    stubLocalStorage();
    TestBed.configureTestingModule({
      providers: [provideZonelessChangeDetection(), provideHttpClient(), provideHttpClientTesting(), noWebGL],
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
