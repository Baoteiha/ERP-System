import { stubLocalStorage } from '../../core/test-storage';
import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { AuthService } from '../../core/auth.service';
import { RetailPurchaseComponent } from './retail-purchase';

describe('RetailPurchaseComponent', () => {
  let ctrl: HttpTestingController;
  let page: RetailPurchaseComponent;
  const boot = (perms: string[]) => {
    stubLocalStorage();
    TestBed.configureTestingModule({ providers: [provideZonelessChangeDetection(), provideHttpClient(), provideHttpClientTesting()] });
    const auth = TestBed.inject(AuthService);
    auth.me.set({ userId: 'u', email: 'a@b.c', companyId: 'co', branchIds: ['b1'], roles: [], permissions: perms, permissionsByBranch: { b1: perms } });
    auth.setActiveBranch('b1');
    ctrl = TestBed.inject(HttpTestingController);
    page = TestBed.createComponent(RetailPurchaseComponent).componentInstance;
    ctrl.expectOne('/api/v1/suppliers').flush([{ id: 's1', name: 'Tạp hóa Cô Ba' }]);
    ctrl.expectOne('/api/v1/ingredients').flush([{ id: 'coffee', name: 'Cà phê', baseUnit: 'g' }]);
    ctrl.expectOne('/api/v1/items').flush([{ id: 'bag', supplierId: 's1', ingredientId: 'coffee', unit: 'g', packLabel: 'bịch', packSize: '500', active: true, name: 'Cà phê bịch 500g' }]);
  };
  afterEach(() => ctrl.verify());

  it('saves the purchase in one call and starts a fresh form', () => {
    boot(['purchasing:retail', 'inventory:read']);
    page.onSupplierChange('s1');
    page.form.lines[0] = { itemId: 'bag', quantity: '1', unitCost: '95000' };
    page.save();
    const req = ctrl.expectOne('/api/v1/purchase-orders/retail');
    expect(req.request.body).toEqual({ supplierId: 's1', note: null, lines: [{ itemId: 'bag', quantity: '1', unitCost: '95000' }] });
    req.flush({ id: 'p1' });
    expect(page.form.lines).toEqual([{ itemId: '', quantity: '', unitCost: '' }]);
  });

  it('hides the shortcuts that declare suppliers and items without inventory:write', () => {
    boot(['purchasing:retail', 'inventory:read']);
    expect(page.canDeclare()).toBe(false);
  });
});
