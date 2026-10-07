import { conversionLine, orderingUnit, packSentence, perOrdering, receiptSummary } from './purchase-math';

const box = { unit: 'pc', packLabel: 'hộp', packSize: '100' };
const sack = { unit: 'kg', packLabel: 'bao', packSize: '25' };

describe('purchase maths', () => {
  it('counts a line in its pack, or in its unit without one', () => {
    expect(orderingUnit(box)).toBe('hộp');
    expect(orderingUnit({ unit: 'kg' })).toBe('kg');
    expect(perOrdering(box)).toBe(100);
    expect(perOrdering({ unit: 'kg' })).toBe(1);
  });

  it('states a pack, and its stock size when the stock unit differs', () => {
    expect(packSentence(box, 'pc')).toBe('1 hộp = 100 pc');
    expect(packSentence(sack, 'g')).toBe('1 bao = 25 kg = 25.000 g');
    expect(packSentence({ unit: 'kg' }, 'g')).toBe('');
  });

  it('shows what a line puts into stock', () => {
    expect(conversionLine(5, 45000, box, 'pc', 'Ống hút giấy')).toBe('= 500 pc Ống hút giấy · 450 ₫/pc');
    expect(conversionLine(2, 480000, sack, 'g', 'Bột mì')).toBe('= 50 kg = 50.000 g Bột mì · 19,2 ₫/g');
    expect(conversionLine(0, 45000, box, 'pc', 'Ống hút giấy')).toBe('');
  });

  it('mirrors the server split: damage leaves stock and is a loss at the price paid', () => {
    const s = receiptSummary({ delivered: 5, outstanding: 5, damaged: 40, overKind: null, costPerOrdering: 45000, perOrdering: 100 });
    expect(s).toMatchObject({ over: 0, chargedIntoStock: 460, freeIntoStock: 0, lossQty: 40, lossAmount: 18000, costPerUnit: 450, error: '' });
  });

  it('counts damage against the paid units first, and free extras at zero', () => {
    const s = receiptSummary({ delivered: 6, outstanding: 5, damaged: 150, overKind: 'FREE', costPerOrdering: 45000, perOrdering: 100 });
    expect(s).toMatchObject({ over: 1, chargedIntoStock: 350, freeIntoStock: 100, lossQty: 150, lossAmount: 67500 });
  });

  it('asks for a choice on an extra, and refuses damage beyond the delivery', () => {
    expect(receiptSummary({ delivered: 6, outstanding: 5, damaged: 0, overKind: null, costPerOrdering: 1, perOrdering: 100 }).error)
      .toContain('choose charged or free gift');
    expect(receiptSummary({ delivered: 5, outstanding: 5, damaged: 501, overKind: null, costPerOrdering: 1, perOrdering: 100 }).error)
      .toContain('cannot exceed');
    expect(receiptSummary({ delivered: 2.5, outstanding: 5, damaged: 0, overKind: null, costPerOrdering: 1, perOrdering: 100 }).error)
      .toContain('whole');
  });
});
