import { sparkPath } from './sparkline';

describe('sparkPath', () => {
  it('spans the full width and flips y so bigger is higher', () => {
    expect(sparkPath([0, 10], 100, 20)).toBe('M0.0,19.0 L100.0,1.0');
  });
  it('draws a flat line in the middle when every value is equal', () => {
    expect(sparkPath([5, 5, 5], 100, 20)).toBe('M0.0,10.0 L50.0,10.0 L100.0,10.0');
  });
  it('is empty for fewer than two points', () => {
    expect(sparkPath([3], 100, 20)).toBe('');
  });
});
