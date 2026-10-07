import { purchaseLinesError } from './purchase-lines';

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
