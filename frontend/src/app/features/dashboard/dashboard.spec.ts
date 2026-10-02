import { pct } from './dashboard';

describe('pct', () => {
  it('is the change against the previous value', () => expect(pct(120, 100)).toBeCloseTo(0.2));
  it('is null when there is nothing to compare with', () => expect(pct(50, 0)).toBeNull());
  it('is negative for a fall', () => expect(pct(80, 100)).toBeCloseTo(-0.2));
});
