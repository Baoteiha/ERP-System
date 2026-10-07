import { stubLocalStorage } from '../../core/test-storage';
import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Router } from '@angular/router';
import { AuthService } from '../../core/auth.service';
import { PurchaseOrdersComponent } from './purchase-orders';

const url = (u: string) => (r: { url: string }) => r.url === u;
const po = (id: string, branchId: string) => ({ id, branchId, supplierId: 's1', kind: 'ORDER', status: 'DRAFT', lines: [], receipts: [] });
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
    ctrl.expectOne('/api/v1/items').flush([]);
    ctrl.expectOne(url('/api/v1/purchase-orders')).flush(page([po('a1', 'b1')]));
  });

  afterEach(() => ctrl.verify());

  it('ignores a late answer from the branch it left, and clears the old rows at once', () => {
    orders.load(); // branch A's load, still in flight
    ctrl.expectOne('/api/v1/ingredients').flush([]);
    ctrl.expectOne('/api/v1/suppliers').flush([]);
    ctrl.expectOne('/api/v1/items').flush([]);
    const lateA = ctrl.expectOne(url('/api/v1/purchase-orders'));

    TestBed.inject(AuthService).setActiveBranch('b2');
    TestBed.tick();
    expect(orders.items()).toEqual([]);
    ctrl.expectOne('/api/v1/ingredients').flush([]);
    ctrl.expectOne('/api/v1/suppliers').flush([]);
    ctrl.expectOne('/api/v1/items').flush([]);
    ctrl.expectOne(url('/api/v1/purchase-orders')).flush(page([po('b-1', 'b2')]));

    lateA.flush(page([po('a1', 'b1')]));

    expect(orders.items().map((p) => p.id)).toEqual(['b-1']);
  });

  it('sends item lines only, with the quantity in packs', () => {
    orders.allItems.set([{ id: 'i1', supplierId: 's1', ingredientId: 'g1', unit: 'pc', packLabel: 'hộp', packSize: '100', active: true } as never]);
    orders.openNew();
    orders.onSupplierChange('s1');
    orders.form.lines[0] = { itemId: 'i1', quantity: '5', unitCost: '45000' };

    orders.save();
    const req = ctrl.expectOne(url('/api/v1/purchase-orders'));
    expect(req.request.body.lines).toEqual([{ itemId: 'i1', orderedQty: '5', unitCost: '45000' }]);
    req.flush(po('p1', 'b1'));
    ctrl.expectOne('/api/v1/ingredients').flush([]);
    ctrl.expectOne('/api/v1/suppliers').flush([]);
    ctrl.expectOne('/api/v1/items').flush([]);
    ctrl.expectOne(url('/api/v1/purchase-orders')).flush(page([]));
  });

  it('does not send half a pack', () => {
    orders.allItems.set([{ id: 'i1', supplierId: 's1', ingredientId: 'g1', unit: 'pc', packLabel: 'hộp', packSize: '100', active: true } as never]);
    orders.openNew();
    orders.onSupplierChange('s1');
    orders.form.lines[0] = { itemId: 'i1', quantity: '2.5', unitCost: '45000' };
    orders.save();
    ctrl.expectNone(url('/api/v1/purchase-orders'));
  });

  it('receipt history shows the original loss, the replaced quantity and formatted numbers', () => {
    const order = {
      ...po('a1', 'b1'),
      lines: [{ id: 'l1', itemId: 'i1', ingredientId: 'g1', unit: 'pc', orderedQty: '10', receivedQty: '10', unitCost: { amount: '450', currency: 'VND' } }],
    };
    const rl = {
      id: 'x', purchaseOrderLineId: 'l1', deliveredQty: '10000', damagedQty: '40', lossQty: '40', overDeliveredQty: '0',
      unitCost: { amount: '450', currency: 'VND' }, costPerUnit: '450', compensatedQty: '40', replacementQty: '0',
      lossAmount: { amount: '0', currency: 'VND' },
    };
    const text = orders.receiptLineText(order as never, rl as never);
    expect(text).toContain('delivered 10.000 pc');
    expect(text).toContain('damaged 40 pc');
    expect(text).toContain('18.000');
    expect(text).toContain('(replaced 40)');
    expect(text).not.toContain('loss 0');
  });
});

describe('PurchaseOrdersComponent route intent', () => {
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
    const f = TestBed.createComponent(PurchaseOrdersComponent);
    f.componentRef.setInput('new', param);
    TestBed.tick();
    return { f, nav, ctrl: TestBed.inject(HttpTestingController) };
  };

  it('selects the order a receipt link names, and highlights the receipt', () => {
    const { f, ctrl } = setup(['purchasing:read'], '');
    f.componentRef.setInput('receipt', 'r1');
    TestBed.tick();
    ctrl.expectOne('/api/v1/purchase-orders/by-receipt/r1').flush({ id: 'p9', branchId: 'b1', supplierId: 's1', kind: 'ORDER', status: 'RECEIVED', lines: [], receipts: [] });
    expect(f.componentInstance.selected()?.id).toBe('p9');
    expect(f.componentInstance.highlightReceipt()).toBe('r1');
    ctrl.match(() => true);
  });

  it('opens the dialog once with the permission, and clears the param', () => {
    const { f, nav, ctrl } = setup(['purchasing:write'], '1');
    expect(f.componentInstance.creating()).toBe(true);
    expect(nav).toHaveBeenCalledWith([], { queryParams: { new: null }, queryParamsHandling: 'merge', replaceUrl: true });
    ctrl.match(() => true);
    void f;
  });

  it('does not open without the permission, but still clears the param', () => {
    const { nav, f, ctrl } = setup([], '1');
    expect(f.componentInstance.creating()).toBe(false);
    expect(nav).toHaveBeenCalledWith([], { queryParams: { new: null }, queryParamsHandling: 'merge', replaceUrl: true });
    ctrl.match(() => true);
    void f;
  });
});

