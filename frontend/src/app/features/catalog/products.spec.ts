import { stubLocalStorage } from '../../core/test-storage';
import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { AuthService } from '../../core/auth.service';
import { BranchResponse, IngredientResponse, ProductResponse } from '../../core/models';
import { By } from '@angular/platform-browser';
import { ProductEditorComponent } from './product-editor';
import { ProductsComponent } from './products';

const money = (amount: string) => ({ amount, currency: 'VND' });
const latte: ProductResponse = {
  id: 'p1', companyId: 'co', categoryId: 'c1', name: 'Latte', sku: 'LAT-1', basePrice: money('28000'), active: true, modifierGroupIds: [],
};
const milk: IngredientResponse = { id: 'i1', companyId: 'co', name: 'Milk', baseUnit: 'ml', active: true, version: 0 };
const page = <T>(content: T[]) => ({ content, page: 0, size: 100, totalElements: content.length, totalPages: 1 });

describe('ProductsComponent', () => {
  let ctrl: HttpTestingController;
  let products: ProductsComponent;
  let fixture: ReturnType<typeof TestBed.createComponent<ProductsComponent>>;

  const url = (u: string) => (r: { url: string }) => r.url === u;

  beforeEach(() => {
    stubLocalStorage();
    TestBed.configureTestingModule({
      providers: [provideZonelessChangeDetection(), provideHttpClient(), provideHttpClientTesting()],
    });
    TestBed.inject(AuthService).me.set({
      userId: 'u', email: 'a@b.c', companyId: 'co', branchIds: ['b1'], roles: [],
      permissions: ['catalog:write', 'inventory:read', 'branch:read'],
      permissionsByBranch: { b1: ['catalog:read', 'catalog:write', 'inventory:read', 'branch:read'] },
    });
    ctrl = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(ProductsComponent);
    products = fixture.componentInstance;
    ctrl.expectOne(url('/api/v1/products')).flush(page([latte]));
    ctrl.expectOne('/api/v1/categories').flush([]);
    ctrl.expectOne('/api/v1/modifier-groups').flush([]);
    ctrl.expectOne('/api/v1/ingredients').flush([milk]);
    ctrl.expectOne(url('/api/v1/branches')).flush(page([{ id: 'b1', name: 'Main' }]));
  });

  afterEach(() => ctrl.verify());

  /** Opens the product's editor; the recipe and availability answers are left to the test. */
  function openEditor(): ProductEditorComponent {
    products.openManage(latte);
    fixture.detectChanges();
    return fixture.debugElement.query(By.directive(ProductEditorComponent)).componentInstance;
  }
  /** Opens the editor on its recipe tab; the recipe answer is left to the test. */
  function openRecipe(): ProductEditorComponent {
    const editor = openEditor();
    ctrl.expectOne('/api/v1/products/p1/availability').flush([]);
    editor.tab.set('recipe');
    return editor;
  }
  const saveButton = () => [...fixture.nativeElement.querySelectorAll('button')]
    .find((b: HTMLButtonElement) => b.textContent?.includes('Save recipe')) as HTMLButtonElement;

  it('disables Save and shows an error when the recipe fails to load', () => {
    openRecipe();
    ctrl.expectOne('/api/v1/products/p1/recipe').flush({ code: 'X' }, { status: 500, statusText: 'Server Error' });
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Could not load the recipe');
    expect(saveButton().disabled).toBe(true);
  });

  it('treats a missing recipe as an empty one that can be saved', () => {
    openRecipe();
    ctrl.expectOne('/api/v1/products/p1/recipe').flush({ code: 'NOT_FOUND' }, { status: 404, statusText: 'Not Found' });
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).not.toContain('Could not load the recipe');
    expect(saveButton().disabled).toBe(false);
  });

  it('blocks Save while a line is missing its unit', () => {
    const editor = openRecipe();
    ctrl.expectOne('/api/v1/products/p1/recipe').flush({ productId: 'p1', version: 1, lines: [{ ingredientId: 'i1', quantity: '20', unit: '' }] });
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Every ingredient needs a quantity and a unit');
    expect(saveButton().disabled).toBe(true);
    editor.saveRecipe();
    ctrl.expectNone((r) => r.method === 'PUT');
  });

  it('sends a price override of 0 as "0"', () => {
    const editor = openEditor();
    ctrl.expectOne('/api/v1/products/p1/recipe').flush({ productId: 'p1', version: 1, lines: [] });
    ctrl.expectOne('/api/v1/products/p1/availability').flush([]);

    editor.availMap['b1'].priceOverride = 0 as unknown as string;
    editor.saveAvail('b1');

    expect(ctrl.expectOne((r) => r.method === 'PUT' && r.url === '/api/v1/products/p1/availability').request.body.priceOverride).toBe('0');
  });

  it('offers availability only where the role reads the catalog, and saving only where it writes it', () => {
    TestBed.inject(AuthService).me.update((me) => me && {
      ...me, branchIds: ['b1', 'b2'],
      permissionsByBranch: { b1: ['catalog:read', 'catalog:write'], b2: ['catalog:read'] },
    });
    products.branches.set([
      { id: 'b1', name: 'Main' }, { id: 'b2', name: 'Riverside' }, { id: 'b3', name: 'Airport' },
    ] as BranchResponse[]);
    const editor = openEditor();
    ctrl.expectOne('/api/v1/products/p1/recipe').flush({ productId: 'p1', version: 1, lines: [] });
    ctrl.expectOne('/api/v1/products/p1/availability').flush([]);
    editor.tab.set('availability');
    fixture.detectChanges();

    const rows = [...fixture.nativeElement.querySelectorAll('table.data tbody tr')] as HTMLTableRowElement[];
    expect(rows.map((r) => r.cells[0].textContent?.trim())).toEqual(['Main', 'Riverside']);
    expect(rows.map((r) => (r.querySelector('button') as HTMLButtonElement).disabled)).toEqual([false, true]);
  });
});
