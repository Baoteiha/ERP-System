import { stubLocalStorage } from '../../core/test-storage';
import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { MenuProduct } from '../../core/models';
import { PosComponent } from './pos';

const money = (amount: string) => ({ amount, currency: 'VND' });
const latte: MenuProduct = { id: 'p1', categoryId: 'c1', name: 'Latte', sku: 'LAT-1', effectivePrice: money('28000'), modifierGroups: [] };
const mocha: MenuProduct = { id: 'p2', categoryId: 'c1', name: 'Mocha', sku: 'MOC-1', effectivePrice: money('30000'), modifierGroups: [] };

function quoteOf(grandTotal: string) {
  return { id: null, status: 'OPEN', currency: 'VND', taxRate: '0', subtotal: grandTotal, discountTotal: '0', taxTotal: '0', grandTotal, lines: [] };
}

describe('PosComponent', () => {
  let ctrl: HttpTestingController;
  let pos: PosComponent;
  let fixture: ReturnType<typeof TestBed.createComponent<PosComponent>>;

  const isPost = (url: string) => (r: { method: string; url: string }) => r.method === 'POST' && r.url === url;

  /** Lets the debounce elapse and returns the quote request it sent. */
  function nextQuote(): TestRequest {
    TestBed.tick();
    vi.advanceTimersByTime(250);
    return ctrl.expectOne(isPost('/api/v1/orders/quote'));
  }

  beforeEach(() => {
    vi.useFakeTimers();
    stubLocalStorage();
    TestBed.configureTestingModule({
      providers: [provideZonelessChangeDetection(), provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    ctrl = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(PosComponent);
    pos = fixture.componentInstance;
    ctrl.expectOne('/api/v1/categories').flush([]);
    ctrl.expectOne('/api/v1/menu').flush([latte, mocha]);
  });

  afterEach(() => vi.useRealTimers());

  it('filters the tiles as the search changes', () => {
    expect(pos.filteredProducts().length).toBe(2);
    pos.search.set('moc');
    expect(pos.filteredProducts().map((p) => p.name)).toEqual(['Mocha']);
  });

  it('shows the branch price on the tile, not the base price', () => {
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toMatch(/28[.,]000/);
  });

  it('updates the total from the quote when a line discount changes', () => {
    pos.addProduct(latte);
    nextQuote().flush(quoteOf('28000'));
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.grand b').textContent).toMatch(/28[.,]000/);

    pos.setLineDiscount(0, '5000');
    const quote = nextQuote();
    expect(quote.request.body.lines[0].lineDiscount).toBe('5000');
    quote.flush(quoteOf('23000'));
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.grand b').textContent).toMatch(/23[.,]000/);
    expect(fixture.nativeElement.querySelector('.btn-primary').textContent).toMatch(/23[.,]000/);
  });

  it('quotes once per pause and drops a quote that a newer change superseded', () => {
    pos.addProduct(latte);
    const first = nextQuote();
    pos.setOrderDiscount('1000');
    const second = nextQuote();

    expect(first.cancelled).toBe(true);
    second.flush(quoteOf('27000'));
    expect(pos.displayQuote()?.grandTotal).toBe('27000');
  });

  it('does not pay until the quote matches the cart', () => {
    pos.addProduct(latte);
    nextQuote().flush(quoteOf('28000'));
    expect(pos.payable()).toBe(true);

    pos.changeQty(0, 1);
    expect(pos.payable()).toBe(false);
  });

  it('skips the payment call when the till completed the order at creation', () => {
    pos.addProduct(latte);
    nextQuote().flush(quoteOf('0'));
    pos.submit();
    const order = { id: 'o1', status: 'COMPLETED', grandTotal: '0', currency: 'VND' };
    ctrl.expectOne(isPost('/api/v1/orders')).flush(order);

    ctrl.expectNone('/api/v1/orders/o1/payments');
    expect(pos.createdOrder()).toEqual(order as never);
    expect(pos.saving()).toBe(false);
  });

  it('retries only the payment after a payment failure, never a second create', () => {
    pos.addProduct(latte);
    nextQuote().flush(quoteOf('28000'));
    pos.submit();
    const create = ctrl.expectOne(isPost('/api/v1/orders'));
    expect(create.request.body.clientRequestId).toMatch(/^[0-9a-f-]{36}$/);
    create.flush({ id: 'o1', status: 'OPEN', grandTotal: '28000', currency: 'VND' });
    ctrl.expectOne('/api/v1/orders/o1/payments').flush({ code: 'X' }, { status: 500, statusText: 'Server Error' });
    expect(pos.saving()).toBe(false);

    pos.submit();

    ctrl.expectNone(isPost('/api/v1/orders'));
    const retry = ctrl.expectOne('/api/v1/orders/o1/payments');
    retry.flush({ id: 'o1', status: 'COMPLETED', grandTotal: '28000', currency: 'VND' });
    expect(pos.createdOrder()?.status).toBe('COMPLETED');
  });

  it('resends a failed create under the same request id', () => {
    pos.addProduct(latte);
    nextQuote().flush(quoteOf('28000'));
    pos.submit();
    const first = ctrl.expectOne(isPost('/api/v1/orders'));
    const id = first.request.body.clientRequestId;
    first.flush({ code: 'X' }, { status: 500, statusText: 'Server Error' });

    pos.submit();

    expect(ctrl.expectOne(isPost('/api/v1/orders')).request.body.clientRequestId).toBe(id);
  });

  it('starts a fresh request id once the cart changes after a failure', () => {
    pos.addProduct(latte);
    nextQuote().flush(quoteOf('28000'));
    pos.submit();
    const first = ctrl.expectOne(isPost('/api/v1/orders'));
    first.flush({ code: 'X' }, { status: 500, statusText: 'Server Error' });

    pos.addProduct(mocha);
    nextQuote().flush(quoteOf('58000'));
    pos.submit();

    expect(ctrl.expectOne(isPost('/api/v1/orders')).request.body.clientRequestId).not.toBe(first.request.body.clientRequestId);
  });

  it('cancels the unpaid order when the cart changes after a failed payment', () => {
    pos.addProduct(latte);
    nextQuote().flush(quoteOf('28000'));
    pos.submit();
    ctrl.expectOne(isPost('/api/v1/orders')).flush({ id: 'o1', status: 'OPEN', grandTotal: '28000', currency: 'VND' });
    ctrl.expectOne('/api/v1/orders/o1/payments').flush({ code: 'X' }, { status: 500, statusText: 'Server Error' });

    pos.addProduct(mocha);
    TestBed.tick();

    const cancel = ctrl.expectOne(isPost('/api/v1/orders/o1/cancel'));
    cancel.flush({ id: 'o1', status: 'CANCELLED', grandTotal: '28000', currency: 'VND' });
    ctrl.expectNone(isPost('/api/v1/orders'));
    vi.advanceTimersByTime(250);
    ctrl.expectOne(isPost('/api/v1/orders/quote')).flush(quoteOf('58000'));
    pos.submit();
    ctrl.expectOne(isPost('/api/v1/orders'));
  });
});
