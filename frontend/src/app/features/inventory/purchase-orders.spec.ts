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

  it('sends an item line as itemId, and an ingredient line with its chosen unit', () => {
    orders.allItems.set([{ id: 'i1', supplierId: 's1', ingredientId: 'g1', unit: 'kg', active: true } as never]);
    orders.ingredients.set([{ id: 'g1', baseUnit: 'g' } as never]);
    orders.openNew();
    orders.onSupplierChange('s1');
    orders.form.note = '';
    orders.addLine();
    orders.pickSource(orders.form.lines[0], 'item:i1');
    orders.pickSource(orders.form.lines[1], 'ingredient:g1');
    orders.form.lines[1].unit = 'kg';
    orders.form.lines.forEach((l) => { l.orderedQty = '10'; l.unitCost = '210000'; });

    expect(orders.editorUnit(orders.form.lines[0])).toBe('kg');
    expect(orders.unitChoices(orders.form.lines[1]).map((u) => u.value)).toEqual(['mg', 'g', 'kg']);

    orders.save();
    const req = ctrl.expectOne(url('/api/v1/purchase-orders'));
    expect(req.request.body.lines).toEqual([
      { itemId: 'i1', orderedQty: '10', unitCost: '210000' },
      { ingredientId: 'g1', unit: 'kg', orderedQty: '10', unitCost: '210000' },
    ]);
    req.flush(po('p1', 'b1'));
    ctrl.expectOne('/api/v1/ingredients').flush([]);
    ctrl.expectOne('/api/v1/suppliers').flush([]);
    ctrl.expectOne('/api/v1/items').flush([]);
    ctrl.expectOne(url('/api/v1/purchase-orders')).flush(page([]));
  });
});
