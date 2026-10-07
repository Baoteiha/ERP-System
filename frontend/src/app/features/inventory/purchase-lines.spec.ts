import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { NEW_ITEM, PurchaseLinesComponent, newPurchaseLine, purchaseLinesError } from './purchase-lines';

const items = [
  { id: 'box', unit: 'pc', packLabel: 'hộp', packSize: '100' },
  { id: 'flour', unit: 'kg' },
] as never[];

describe('purchaseLinesError', () => {
  it('passes complete lines, and whole packs', () => {
    expect(purchaseLinesError([{ itemId: 'box', quantity: '5', unitCost: '45000' }, { itemId: 'flour', quantity: '2.5', unitCost: '19000' }], items)).toBe('');
  });

  it('names what is missing, and refuses half a pack', () => {
    expect(purchaseLinesError([{ itemId: '', quantity: '5', unitCost: '1' }], items)).toBe('Every line needs an item');
    expect(purchaseLinesError([{ itemId: 'box', quantity: '', unitCost: '1' }], items)).toBe('Every line needs a quantity above 0 and a cost');
    expect(purchaseLinesError([{ itemId: 'box', quantity: '2.5', unitCost: '1' }], items)).toBe('Packs are counted whole: 2.5 hộp');
  });
});

describe('cleared fields', () => {
  it('treats a null cost or quantity as missing', () => {
    const msg = 'Every line needs a quantity above 0 and a cost';
    expect(purchaseLinesError([{ itemId: 'box', quantity: '5', unitCost: null as never }], items)).toBe(msg);
    expect(purchaseLinesError([{ itemId: 'box', quantity: null as never, unitCost: '1' }], items)).toBe(msg);
  });
});

describe('PurchaseLinesComponent picker', () => {
  it('resets the select after "+ New item" is chosen', async () => {
    TestBed.configureTestingModule({ providers: [provideZonelessChangeDetection()] });
    const f = TestBed.createComponent(PurchaseLinesComponent);
    const lines = [newPurchaseLine()];
    f.componentRef.setInput('lines', lines);
    f.componentRef.setInput('items', [{ id: 'box', name: 'Box', unit: 'pc' }]);
    f.componentRef.setInput('ingredients', []);
    f.componentRef.setInput('canCreateItem', true);
    const emitted: number[] = [];
    f.componentInstance.newItem.subscribe((i) => emitted.push(i));
    await f.whenStable();
    const sel = (f.nativeElement as HTMLElement).querySelector('select') as HTMLSelectElement;
    const opt = Array.from(sel.options).find((o) => o.text.includes('+ New item'));
    sel.value = opt!.value;
    sel.dispatchEvent(new Event('change'));
    f.detectChanges();
    await f.whenStable();
    expect(NEW_ITEM).toBeTruthy();
    expect(emitted).toEqual([0]);
    expect(lines[0].itemId).toBe('');
    expect(sel.value).toBe('');
  });
});
