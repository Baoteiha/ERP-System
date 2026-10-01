import { describe, expect, it } from 'vitest';
import { reportPeriod, zeroFillDays } from './reports';

describe('report days', () => {
  it('covers the last N days in Vietnam time and zero-fills the quiet ones', () => {
    // 2026-03-03 00:30 in UTC+7 is still 2 March in UTC.
    const now = Date.parse('2026-03-02T17:30:00Z');
    const { days, from, to } = reportPeriod(now, 3);
    expect(days).toEqual(['2026-03-01', '2026-03-02', '2026-03-03']);
    expect(from.toISOString()).toBe('2026-02-28T17:00:00.000Z');
    expect(to.toISOString()).toBe('2026-03-03T17:00:00.000Z');

    const filled = zeroFillDays(days, [{ day: '2026-03-02', orderCount: 2, revenue: '50000' }]);
    expect(filled.map((d) => d.orderCount)).toEqual([0, 2, 0]);
    expect(filled[0].revenue).toBe('0');
  });
});
