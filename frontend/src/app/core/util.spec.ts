import { LabelPipe, formatMoney } from './util';

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
