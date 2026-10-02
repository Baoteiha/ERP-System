import { stubLocalStorage } from '../../core/test-storage';
import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { OrdersComponent } from './orders';

const order = (id: string, total: string) => ({
  id, branchId: 'b1', cashierUserId: 'u1', orderType: 'DINE_IN', status: 'PAID', currency: 'VND', taxRate: '0',
  subtotal: total, discountTotal: '0', taxTotal: '0', grandTotal: total, cogsTotal: '0', amountPaid: total,
  lines: [], payments: [], createdAt: '2026-10-02T10:00:00Z',
});

describe('OrdersComponent', () => {
  let fixture: ReturnType<typeof TestBed.createComponent<OrdersComponent>>;
  const el = () => fixture.nativeElement as HTMLElement;

  beforeEach(() => {
    stubLocalStorage();
    TestBed.configureTestingModule({
      providers: [provideZonelessChangeDetection(), provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    fixture = TestBed.createComponent(OrdersComponent);
    TestBed.inject(HttpTestingController).expectOne((r) => r.url === '/api/v1/orders').flush({
      content: [order('aaaaaaaa-1', '10000'), order('bbbbbbbb-2', '20000')], page: 0, size: 25, totalElements: 2, totalPages: 1,
    });
    fixture.detectChanges();
  });

  it('opens no drawer when the list loads', () => {
    expect(el().querySelector('app-modal')).toBeNull();
  });

  it('opens the drawer for the clicked order and removes it on close', () => {
    const buttons = el().querySelectorAll<HTMLButtonElement>('.row-open');
    buttons[1].click();
    fixture.detectChanges();
    const drawer = el().querySelector('app-modal');
    expect(drawer).not.toBeNull();
    expect(drawer?.textContent).toContain('#bbbbbb');
    expect(drawer?.textContent).not.toContain('#aaaaaa');
    expect(drawer?.textContent).toMatch(/20[.,]000/);

    fixture.componentInstance.detailOpen.set(false);
    fixture.detectChanges();
    expect(el().querySelector('app-modal')).toBeNull();
  });
});
