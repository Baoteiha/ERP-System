import { stubLocalStorage } from '../../core/test-storage';
import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ItemFormComponent } from './item-form';

const ingredients = [{ id: 'straw', name: 'Ống hút giấy', baseUnit: 'pc' }, { id: 'flour', name: 'Bột mì', baseUnit: 'g' }];

describe('ItemFormComponent', () => {
  let ctrl: HttpTestingController;
  const create = () => {
    const f = TestBed.createComponent(ItemFormComponent);
    f.componentRef.setInput('suppliers', [{ id: 's1', name: 'Tiệm bánh' }]);
    f.componentRef.setInput('ingredients', ingredients);
    f.componentRef.setInput('supplierId', 's1');
    f.detectChanges();
    return f.componentInstance;
  };

  beforeEach(() => {
    stubLocalStorage();
    TestBed.configureTestingModule({ providers: [provideZonelessChangeDetection(), provideHttpClient(), provideHttpClientTesting()] });
    ctrl = TestBed.inject(HttpTestingController);
  });
  afterEach(() => ctrl.verify());

  it('offersOnlyTheIngredientsDimension_afterPickingIt', () => {
    const form = create();
    expect(form.availableUnits().length).toBe(7);
    form.onIngredientChange('straw');
    expect(form.availableUnits().map((u) => u.value)).toEqual(['pc']);
    expect(form.form.unit).toBe('pc');
  });

  it('states the pack in words, with the stock size when stock counts another unit', () => {
    const form = create();
    form.onIngredientChange('flour');
    form.onUnitChange('kg');
    form.form.packLabel = 'bao';
    form.form.packSize = '25';
    expect(form.packHint()).toBe('Ordered in bao · 1 bao = 25 kg = 25.000 g · stock counts g');
  });

  it('asks for the pack size again when the unit changes', () => {
    const form = create();
    form.onIngredientChange('flour');
    form.form.packLabel = 'bao';
    form.form.packSize = '25';
    form.onUnitChange('kg');
    expect(form.form.packSize).toBe('');
    expect(form.packNeedsSize).toBe(true);
  });

  it('refuses half a pack without calling the server, and sends both halves when complete', () => {
    const form = create();
    form.onIngredientChange('straw');
    form.form.name = 'Ống hút hộp 100';
    form.form.packLabel = 'hộp';
    form.save();
    ctrl.expectNone('/api/v1/items');

    let saved: unknown;
    form.saved.subscribe((i) => (saved = i));
    form.form.packSize = '100';
    form.save();
    const req = ctrl.expectOne('/api/v1/items');
    expect(req.request.body).toMatchObject({ unit: 'pc', packLabel: 'hộp', packSize: '100', supplierId: 's1' });
    req.flush({ id: 'i1' });
    expect(saved).toEqual({ id: 'i1' });
  });
});
