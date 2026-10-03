import { stubLocalStorage } from '../../core/test-storage';
import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { AuthService } from '../../core/auth.service';
import { SUPPRESS_ERROR_TOAST } from '../../core/http.interceptors';
import { BranchResponse, MeResponse, MenuProduct } from '../../core/models';
import { ToastService } from '../../core/toast.service';
import { BranchSwitcher } from '../../layout/branch-switch';
import { PosComponent } from './pos';

const money = (amount: string) => ({ amount, currency: 'VND' });
const latte: MenuProduct = { id: 'p1', categoryId: 'c1', name: 'Latte', sku: 'LAT-1', effectivePrice: money('28000'), modifierGroups: [], available: true };
const mocha: MenuProduct = { id: 'p2', categoryId: 'c1', name: 'Mocha', sku: 'MOC-1', effectivePrice: money('30000'), modifierGroups: [], available: true };

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
    TestBed.inject(AuthService).activeBranchId.set('b1');
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

  it('cancels the stale order silently, and a failed cancel does not stop the next sale', () => {
    pos.addProduct(latte);
    nextQuote().flush(quoteOf('28000'));
    pos.submit();
    ctrl.expectOne(isPost('/api/v1/orders')).flush({ id: 'o1', status: 'OPEN', grandTotal: '28000', currency: 'VND' });
    ctrl.expectOne('/api/v1/orders/o1/payments').flush({ code: 'X' }, { status: 500, statusText: 'Server Error' });

    pos.addProduct(mocha);
    TestBed.tick();
    const cancel = ctrl.expectOne(isPost('/api/v1/orders/o1/cancel'));
    expect(cancel.request.context.get(SUPPRESS_ERROR_TOAST)).toBe(true);
    cancel.flush({ code: 'X' }, { status: 500, statusText: 'Server Error' });

    vi.advanceTimersByTime(250);
    ctrl.expectOne(isPost('/api/v1/orders/quote')).flush(quoteOf('58000'));
    pos.submit();
    ctrl.expectOne(isPost('/api/v1/orders')).flush({ id: 'o2', status: 'OPEN', grandTotal: '58000', currency: 'VND' });
    ctrl.expectOne('/api/v1/orders/o2/payments').flush({ id: 'o2', status: 'COMPLETED', grandTotal: '58000', currency: 'VND' });
    expect(pos.createdOrder()?.id).toBe('o2');
  });

  describe('stock', () => {
    const el = () => fixture.nativeElement as HTMLElement;
    const croissant: MenuProduct = { id: 'p3', categoryId: 'c1', name: 'Croissant', sku: 'CRO-1', effectivePrice: money('35000'), modifierGroups: [], available: false };
    const oatLatte: MenuProduct = {
      ...latte, id: 'p4', name: 'Oat latte', sku: 'OAT-1', available: false,
      modifierGroups: [{ id: 'g1', name: 'Milk', minSelect: 0, maxSelect: 1, modifiers: [{ id: 'm1', name: 'Oat', priceDelta: money('5000') }] }],
    };
    const short = [{ ingredientId: 'i1', ingredientName: 'Bánh croissant', unit: 'pc', required: 101, onHand: 51 }];
    const tile = (name: string) => [...el().querySelectorAll<HTMLButtonElement>('.pos-tile')].find((t) => t.textContent?.includes(name))!;
    const withShortfalls = (total: string, stockShortfalls: unknown[]) => ({ ...quoteOf(total), stockShortfalls });

    function addAndQuote(stockShortfalls: unknown[]) {
      pos.addProduct(latte);
      nextQuote().flush(withShortfalls('28000', stockShortfalls));
      fixture.detectChanges();
    }

    it('shows an unavailable product as Out of stock, disabled, and adds nothing when clicked', () => {
      pos.products.set([latte, croissant]);
      fixture.detectChanges();

      const t = tile('Croissant');
      expect(t.textContent).toContain('Out of stock');
      expect(t.textContent?.toLowerCase()).toContain('out of stock');
      expect(t.disabled).toBe(true);
      expect(tile('Latte').disabled).toBe(false);
      t.click();
      expect(pos.cart()).toEqual([]);
    });

    it('keeps an unavailable product with modifiers addable, since a substitution may fit', () => {
      pos.products.set([oatLatte]);
      fixture.detectChanges();

      const t = tile('Oat latte');
      expect(t.textContent).toContain('Out of stock');
      expect(t.disabled).toBe(false);
      t.click();
      expect(pos.cart().length).toBe(1);
    });

    it('lists the shortfalls in an alert and disables Pay with its reason linked', () => {
      addAndQuote(short);

      const alert = el().querySelector('[role="alert"]')!;
      expect(alert.textContent).toContain('Bánh croissant: needs 101 pc, 51 pc left');
      const pay = el().querySelector<HTMLButtonElement>('.btn-primary')!;
      expect(pay.disabled).toBe(true);
      const reason = el().querySelector('#' + pay.getAttribute('aria-describedby'))!;
      expect(reason.textContent).toContain('Not enough stock');
    });

    it('clears the block and enables Pay when a later quote has no shortfalls', () => {
      TestBed.inject(AuthService).me.set({
        userId: 'u', email: 'a@b.c', companyId: 'c', branchIds: ['b1'], roles: [], permissions: ['sales:write'],
        permissionsByBranch: { b1: ['sales:write'] },
      } as MeResponse);
      addAndQuote(short);
      expect(el().querySelector('[role="alert"]')).not.toBeNull();
      expect(el().querySelector<HTMLButtonElement>('.btn-primary')!.disabled).toBe(true);

      pos.setLineDiscount(0, '1000');
      nextQuote().flush(withShortfalls('27000', []));
      fixture.detectChanges();

      expect(el().querySelector('[role="alert"]')).toBeNull();
      const pay = el().querySelector<HTMLButtonElement>('.btn-primary')!;
      expect(pay.disabled).toBe(false);
      expect(pay.hasAttribute('aria-describedby')).toBe(false);
    });

    it('keeps Pay disabled and the block shown while a newer quote is pending', () => {
      TestBed.inject(AuthService).me.set({
        userId: 'u', email: 'a@b.c', companyId: 'c', branchIds: ['b1'], roles: [], permissions: ['sales:write'],
        permissionsByBranch: { b1: ['sales:write'] },
      } as MeResponse);
      addAndQuote(short);

      pos.changeQty(0, 1);
      nextQuote();
      fixture.detectChanges();

      expect(el().querySelector<HTMLButtonElement>('.btn-primary')!.disabled).toBe(true);
      expect(el().textContent).toContain('Not enough stock');
      pos.submit();
      ctrl.expectNone(isPost('/api/v1/orders'));
    });

    function expectRefreshed(detail: string) {
      ctrl.expectOne('/api/v1/menu').flush([latte]);
      vi.advanceTimersByTime(250);
      ctrl.expectOne(isPost('/api/v1/orders/quote')).flush(withShortfalls('28000', short));
      fixture.detectChanges();
      expect(el().textContent).toContain(detail);
      expect(pos.saving()).toBe(false);
    }
    const refusal = { code: 'INSUFFICIENT_STOCK', detail: 'Not enough stock: Bánh croissant needs 101 pc, 51 left', shortfalls: short };

    it('shows a refusal at payment inline, reloads the menu and quotes the cart again', () => {
      addAndQuote([]);
      pos.submit();
      ctrl.expectOne(isPost('/api/v1/orders')).flush({ id: 'o1', status: 'OPEN', grandTotal: '28000', currency: 'VND' });
      ctrl.expectOne('/api/v1/orders/o1/payments').flush(refusal, { status: 422, statusText: 'Unprocessable' });
      fixture.detectChanges();

      expect(el().textContent).toContain(refusal.detail);
      ctrl.expectOne('/api/v1/menu').flush([latte]);
      // the unpaid order is cancelled so it does not linger OPEN
      const cancel = ctrl.expectOne(isPost('/api/v1/orders/o1/cancel'));
      expect(cancel.request.context.get(SUPPRESS_ERROR_TOAST)).toBe(true);
      cancel.flush({ id: 'o1', status: 'CANCELLED' });
      vi.advanceTimersByTime(250);
      ctrl.expectOne(isPost('/api/v1/orders/quote')).flush(withShortfalls('28000', short));
      fixture.detectChanges();
      expect(el().textContent).toContain(refusal.detail);
      // one announcement, not two: the refusal alerts and the shortfall list is a status
      const alerts = el().querySelectorAll('[role="alert"]');
      expect(alerts.length).toBe(1);
      expect(alerts[0].textContent).toContain(refusal.detail);
      expect(el().querySelector('#pos-stock-block')?.getAttribute('role')).toBe('status');
      expect(el().querySelector<HTMLButtonElement>('.btn-primary')!.disabled).toBe(true);
    });

    it('shows a refusal at create inline too, as a zero-total sale is completed there', () => {
      pos.addProduct(latte);
      nextQuote().flush(withShortfalls('0', []));
      pos.submit();
      ctrl.expectOne(isPost('/api/v1/orders')).flush(refusal, { status: 422, statusText: 'Unprocessable' });
      fixture.detectChanges();

      expect(el().textContent).toContain(refusal.detail);
      expectRefreshed(refusal.detail);
      ctrl.expectNone('/api/v1/orders/o1/payments');
    });

    it('drops the inline refusal once the cart changes', () => {
      addAndQuote([]);
      pos.submit();
      ctrl.expectOne(isPost('/api/v1/orders')).flush(refusal, { status: 422, statusText: 'Unprocessable' });
      ctrl.expectOne('/api/v1/menu').flush([latte]);
      vi.advanceTimersByTime(250);
      ctrl.expectOne(isPost('/api/v1/orders/quote')).flush(withShortfalls('28000', short));

      pos.changeQty(0, 1);
      fixture.detectChanges();

      expect(el().textContent).not.toContain(refusal.detail);
    });

    it('drops the inline refusal when the requote finds enough stock', () => {
      addAndQuote([]);
      pos.submit();
      ctrl.expectOne(isPost('/api/v1/orders')).flush(refusal, { status: 422, statusText: 'Unprocessable' });
      ctrl.expectOne('/api/v1/menu').flush([latte]);
      vi.advanceTimersByTime(250);
      ctrl.expectOne(isPost('/api/v1/orders/quote')).flush(withShortfalls('28000', []));
      fixture.detectChanges();

      expect(el().textContent).not.toContain(refusal.detail);
    });

    it('does not show another failure as a stock refusal', () => {
      addAndQuote([]);
      pos.submit();
      ctrl.expectOne(isPost('/api/v1/orders')).flush({ code: 'X', detail: 'Boom' }, { status: 500, statusText: 'Server Error' });
      fixture.detectChanges();

      expect(el().textContent).not.toContain('Boom');
      ctrl.expectNone('/api/v1/menu');
    });

    it.each(['PAID', 'COMPLETED'])('reloads the menu after a sale that ends %s', (status) => {
      addAndQuote([]);
      pos.submit();
      ctrl.expectOne(isPost('/api/v1/orders')).flush({ id: 'o1', status: 'OPEN', grandTotal: '28000', currency: 'VND' });
      ctrl.expectOne('/api/v1/orders/o1/payments').flush({ id: 'o1', status, grandTotal: '28000', currency: 'VND' });

      ctrl.expectOne('/api/v1/menu').flush([croissant]);
      expect(pos.products().map((p) => p.name)).toEqual(['Croissant']);
      expect(pos.loading()).toBe(false);
    });
  });

  describe('on a branch switch', () => {
    const toasts = () => TestBed.inject(ToastService).toasts().map((t) => `${t.kind}: ${t.text}`);
    function switchTo(id: string, name: string) {
      TestBed.inject(BranchSwitcher).known.set([{ id, name, code: id.toUpperCase() } as BranchResponse]);
      TestBed.inject(AuthService).setActiveBranch(id);
      TestBed.tick();
    }

    it('reloads the menu, and clears an unsent cart with a note naming the new branch', () => {
      pos.addProduct(latte);
      pos.addProduct(mocha);
      nextQuote().flush(quoteOf('58000'));

      switchTo('b2', 'Riverside');
      ctrl.expectOne('/api/v1/categories').flush([]);
      ctrl.expectOne('/api/v1/menu').flush([mocha]);

      expect(pos.cart()).toEqual([]);
      expect(pos.products().map((p) => p.name)).toEqual(['Mocha']);
      expect(toasts()).toEqual(['info: Cart cleared for Riverside']);
    });

    it('keeps the cart and says nothing while a payment is saving, but still reloads the menu', () => {
      pos.addProduct(latte);
      nextQuote().flush(quoteOf('28000'));
      pos.submit();
      ctrl.expectOne(isPost('/api/v1/orders'));
      expect(pos.saving()).toBe(true);

      switchTo('b2', 'Riverside');
      ctrl.expectOne('/api/v1/categories').flush([]);
      ctrl.expectOne('/api/v1/menu').flush([mocha]);

      expect(pos.cart().length).toBe(1);
      expect(pos.products().map((p) => p.name)).toEqual(['Mocha']);
      expect(toasts()).toEqual([]);
    });

    it('says nothing over an empty cart, and a late menu from the branch it left does not win', () => {
      pos.load();
      ctrl.expectOne('/api/v1/categories').flush([]);
      const late = ctrl.expectOne('/api/v1/menu');

      switchTo('b2', 'Riverside');
      ctrl.expectOne('/api/v1/categories').flush([]);
      ctrl.expectOne('/api/v1/menu').flush([mocha]);
      late.flush([latte, mocha]);

      expect(pos.products().map((p) => p.name)).toEqual(['Mocha']);
      expect(toasts()).toEqual([]);
    });
  });
});
