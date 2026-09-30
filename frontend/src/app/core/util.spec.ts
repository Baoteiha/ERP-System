import { formatMoney } from './util';

describe('formatMoney', () => {
  it('shows a dash for missing amounts', () => {
    expect(formatMoney(null)).toBe('—');
  });
});
