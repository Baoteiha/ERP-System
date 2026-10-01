import { stubLocalStorage } from '../../core/test-storage';
import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
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

  it('keys the movement icons by the real movement types', () => {
    expect(stock.pinIcon('PURCHASE_RECEIPT')).toBe('truck');
    expect(stock.pinIcon('SALE_DEPLETION')).toBe('receipt');
    expect(stock.pinIcon('SALE_REVERSAL')).toBe('receipt');
    expect(stock.pinIcon('ADJUSTMENT')).toBe('sliders');
    expect(stock.pinIcon('WASTE')).toBe('trash');
    expect(stock.pinClass('PURCHASE_RECEIPT')).toBe('pin-green');
    expect(stock.pinClass('SALE_DEPLETION')).toBe('pin-azure');
  });
});
