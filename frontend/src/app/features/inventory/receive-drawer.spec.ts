import { stubLocalStorage } from '../../core/test-storage';
import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ReceiveDrawerComponent } from './receive-drawer';

const po = {
  id: 'p1', branchId: 'b1', supplierId: 's1', kind: 'ORDER', status: 'SENT', receipts: [],
  lines: [{ id: 'l1', itemId: 'box', ingredientId: 'straw', unit: 'pc', packLabel: 'hộp', packSize: '100', orderedQty: '5', receivedQty: '0', unitCost: { amount: '45000', currency: 'VND' } }],
};

describe('ReceiveDrawerComponent', () => {
  let ctrl: HttpTestingController;
  let drawer: ReceiveDrawerComponent;

  beforeEach(() => {
    stubLocalStorage();
    TestBed.configureTestingModule({ providers: [provideZonelessChangeDetection(), provideHttpClient(), provideHttpClientTesting()] });
    ctrl = TestBed.inject(HttpTestingController);
    const f = TestBed.createComponent(ReceiveDrawerComponent);
    f.componentRef.setInput('po', po);
    f.componentRef.setInput('items', [{ id: 'box', name: 'Ống hút hộp 100' }]);
    f.componentRef.setInput('ingredients', [{ id: 'straw', name: 'Ống hút giấy', baseUnit: 'pc' }]);
    f.detectChanges();
    drawer = f.componentInstance;
  });
  afterEach(() => ctrl.verify());

  it('defaults delivered to what is outstanding, and states stock and loss', () => {
    const row = drawer.rows[0];
    expect(row.delivered).toBe('5');
    row.damaged = '40';
    expect(drawer.intoStockText(row)).toBe('Into stock: 460 pc · 450 ₫/pc');
    expect(drawer.lossText(row)).toContain('40 × 450 = 18.000');
  });

  it('requires a choice for an extra, then sends it with the damage', () => {
    const row = drawer.rows[0];
    row.delivered = '6';
    drawer.confirm();
    ctrl.expectNone('/api/v1/purchase-orders/p1/receive');

    row.overKind = 'FREE';
    row.damaged = '40';
    drawer.confirm();
    const req = ctrl.expectOne('/api/v1/purchase-orders/p1/receive');
    expect(req.request.body.receipts).toEqual([
      { lineId: 'l1', receivedQty: '6', damagedQty: '40', unitCostOverride: null, overDeliveryKind: 'FREE', note: null },
    ]);
    req.flush(po);
  });

  it('sends numbers typed into number inputs as strings', () => {
    const row = drawer.rows[0];
    row.delivered = 4 as unknown as string;
    row.damaged = 10 as unknown as string;
    row.actualPrice = 44000 as unknown as string;
    drawer.confirm();
    const req = ctrl.expectOne('/api/v1/purchase-orders/p1/receive');
    expect(req.request.body.receipts[0]).toEqual({ lineId: 'l1', receivedQty: '4', damagedQty: '10', unitCostOverride: '44000', overDeliveryKind: null, note: null });
    req.flush(po);
  });
});
