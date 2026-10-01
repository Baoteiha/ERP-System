import { stubLocalStorage } from '../../core/test-storage';
import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { PosComponent } from './pos';

describe('PosComponent', () => {
  let ctrl: HttpTestingController;
  let pos: PosComponent;

  beforeEach(() => {
    stubLocalStorage();
    TestBed.configureTestingModule({
      providers: [provideZonelessChangeDetection(), provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    ctrl = TestBed.inject(HttpTestingController);
    pos = TestBed.createComponent(PosComponent).componentInstance;
  });

  it('skips the payment call when the till completed the order at creation', () => {
    pos.cart.set([{ product: { id: 'p1', modifierGroupIds: [] } as never, quantity: 1, modifierIds: [], lineDiscount: '0' }]);
    pos.submit();
    const order = { id: 'o1', status: 'COMPLETED', grandTotal: '0', currency: 'VND' };
    ctrl.expectOne((r) => r.method === 'POST' && r.url === '/api/v1/orders').flush(order);

    ctrl.expectNone('/api/v1/orders/o1/payments');
    expect(pos.createdOrder()).toEqual(order as never);
    expect(pos.saving()).toBe(false);
  });
});
