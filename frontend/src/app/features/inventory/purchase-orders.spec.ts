import { stubLocalStorage } from '../../core/test-storage';
import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { AuthService } from '../../core/auth.service';
import { PurchaseOrdersComponent } from './purchase-orders';

const url = (u: string) => (r: { url: string }) => r.url === u;
const po = (id: string, branchId: string) => ({ id, branchId, supplierId: 's1', status: 'DRAFT', lines: [] });
const page = (content: unknown[]) => ({ content, page: 0, size: 50, totalElements: content.length, totalPages: 1 });

describe('PurchaseOrdersComponent', () => {
  let ctrl: HttpTestingController;
  let orders: PurchaseOrdersComponent;

  beforeEach(() => {
    stubLocalStorage();
    TestBed.configureTestingModule({
      providers: [provideZonelessChangeDetection(), provideHttpClient(), provideHttpClientTesting()],
    });
    ctrl = TestBed.inject(HttpTestingController);
    TestBed.inject(AuthService).activeBranchId.set('b1');
    orders = TestBed.createComponent(PurchaseOrdersComponent).componentInstance;
    TestBed.tick();
    ctrl.expectOne('/api/v1/ingredients').flush([]);
    ctrl.expectOne('/api/v1/suppliers').flush([]);
    ctrl.expectOne(url('/api/v1/purchase-orders')).flush(page([po('a1', 'b1')]));
  });

  afterEach(() => ctrl.verify());

  it('ignores a late answer from the branch it left, and clears the old rows at once', () => {
    orders.load(); // branch A's load, still in flight
    ctrl.expectOne('/api/v1/ingredients').flush([]);
    ctrl.expectOne('/api/v1/suppliers').flush([]);
    const lateA = ctrl.expectOne(url('/api/v1/purchase-orders'));

    TestBed.inject(AuthService).setActiveBranch('b2');
    TestBed.tick();
    expect(orders.items()).toEqual([]);
    ctrl.expectOne('/api/v1/ingredients').flush([]);
    ctrl.expectOne('/api/v1/suppliers').flush([]);
    ctrl.expectOne(url('/api/v1/purchase-orders')).flush(page([po('b-1', 'b2')]));

    lateA.flush(page([po('a1', 'b1')]));

    expect(orders.items().map((p) => p.id)).toEqual(['b-1']);
  });
});
