import { LabelPipe, QtyPipe, formatMoney } from './util';

describe('formatMoney', () => {
  it('shows a dash for missing amounts', () => {
    expect(formatMoney(null)).toBe('—');
  });
});

describe('LabelPipe', () => {
  const label = (v: string | null | undefined) => new LabelPipe().transform(v);
  it('turns SNAKE_CASE into sentence case', () => {
    expect(label('DINE_IN')).toBe('Dine in');
    expect(label('TAKEAWAY')).toBe('Takeaway');
    expect(label('COMPLETED')).toBe('Completed');
  });
  it('shows a dash for a missing value', () => expect(label(null)).toBe('—'));
});

describe('QtyPipe', () => {
  const qty = (v: number | string | null | undefined) => new QtyPipe().transform(v);
  it('groups thousands with a dot and uses a decimal comma', () => {
    expect(qty(28240)).toBe('28.240');
    expect(qty('28240')).toBe('28.240');
    expect(qty(1.5)).toBe('1,5');
  });
  it('keeps at most three decimals', () => expect(qty('0.12345')).toBe('0,123'));
  it('shows a dash for null or NaN', () => {
    expect(qty(null)).toBe('—');
    expect(qty('abc')).toBe('—');
  });
});
