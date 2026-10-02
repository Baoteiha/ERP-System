import { stubLocalStorage } from '../../core/test-storage';
import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { AuthService } from '../../core/auth.service';
import { ConfirmService } from '../../core/confirm.service';
import { MeResponse } from '../../core/models';
import { OrdersComponent } from './orders';

const pageOf = (orders: unknown[], page = 0, totalPages = 1) => ({ content: orders, page, size: 25, totalElements: orders.length, totalPages });
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
    TestBed.inject(AuthService).activeBranchId.set('b1');
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

  it('names the drawer\'s cancellation "Cancel order", styles it as destructive, and still confirms it', async () => {
    TestBed.inject(AuthService).me.set({
      userId: 'u', email: 'a@b.c', companyId: 'c', branchIds: ['b1'], roles: [], permissions: ['sales:write'],
      permissionsByBranch: { b1: ['sales:write'] },
    } as MeResponse);
    const ask = vi.spyOn(TestBed.inject(ConfirmService), 'ask').mockResolvedValue(false);
    fixture.componentInstance.select({ ...order('eeeeeeee-5', '50000'), status: 'OPEN', amountPaid: '0' } as never);
    fixture.detectChanges();

    const buttons = [...el().querySelectorAll<HTMLButtonElement>('app-modal .action-bar button')];
    expect(buttons.map((b) => b.textContent?.trim())).toEqual(['Add payment', 'Cancel order']);
    expect(buttons[1].classList).toContain('btn-danger');
    buttons[1].click();
    await fixture.whenStable();
    expect(ask).toHaveBeenCalledWith(expect.objectContaining({ danger: true }));
  });

  describe('when the branch changes', () => {
    const ordersAt = (page: number) => (r: { url: string; params: { get(k: string): string | null } }) =>
      r.url === '/api/v1/orders' && r.params.get('page') === String(page);
    const rowIds = () => [...el().querySelectorAll('.row-open')].map((b) => b.textContent?.trim());

    it('closes the drawer and reloads from the first page of the new branch', () => {
      const ctrl = TestBed.inject(HttpTestingController);
      el().querySelectorAll<HTMLButtonElement>('.row-open')[0].click();
      fixture.componentInstance.goTo(1);
      ctrl.expectOne(ordersAt(1)).flush(pageOf([order('cccccccc-3', '30000')], 1, 2));
      fixture.detectChanges();
      expect(el().querySelector('app-modal')).not.toBeNull();

      TestBed.inject(AuthService).setActiveBranch('b2');
      TestBed.tick();
      ctrl.expectOne(ordersAt(0)).flush(pageOf([order('dddddddd-4', '40000')]));
      fixture.detectChanges();

      expect(el().querySelector('app-modal')).toBeNull();
      expect(rowIds()).toEqual(['#dddddd']);
      expect(el().textContent).toContain('Page 1 of 1');
      ctrl.verify();
    });

    it('ignores a late page from the branch it left', () => {
      const ctrl = TestBed.inject(HttpTestingController);
      fixture.componentInstance.goTo(1);
      const late = ctrl.expectOne(ordersAt(1));

      TestBed.inject(AuthService).setActiveBranch('b2');
      TestBed.tick();
      ctrl.expectOne(ordersAt(0)).flush(pageOf([order('dddddddd-4', '40000')]));
      late.flush(pageOf([order('cccccccc-3', '30000')], 1, 2));
      fixture.detectChanges();

      expect(rowIds()).toEqual(['#dddddd']);
      ctrl.verify();
    });
  });
});
