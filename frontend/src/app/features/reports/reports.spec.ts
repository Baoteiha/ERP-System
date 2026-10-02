import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { describe, expect, it } from 'vitest';
import { ReportingService } from '../../core/api.services';
import { ReportsComponent, reportPeriod, zeroFillDays } from './reports';

describe('report days', () => {
  it('covers the last N days in Vietnam time and zero-fills the quiet ones', () => {
    // 2026-03-03 00:30 in UTC+7 is still 2 March in UTC.
    const now = Date.parse('2026-03-02T17:30:00Z');
    const { days, from, to } = reportPeriod(now, 3);
    expect(days).toEqual(['2026-03-01', '2026-03-02', '2026-03-03']);
    expect(from.toISOString()).toBe('2026-02-28T17:00:00.000Z');
    expect(to.toISOString()).toBe('2026-03-03T17:00:00.000Z');

    const filled = zeroFillDays(days, [{ day: '2026-03-02', orderCount: 2, netSales: '50000' }]);
    expect(filled.map((d) => d.orderCount)).toEqual([0, 2, 0]);
    expect(filled[0].netSales).toBe('0');
  });
});

describe('ReportsComponent', () => {
  const summary = { netSales: '100', orderCount: 1, grandTotal: '110', cogs: '10', laborCost: '200', grossMargin: '90', netMargin: '-110' };
  async function render() {
    TestBed.configureTestingModule({
      providers: [provideZonelessChangeDetection(), { provide: ReportingService, useValue: {
        summary: () => of(summary),
        salesByDay: () => of(reportPeriod(Date.now(), 2).days.map((day, i) => ({ day, orderCount: 1, netSales: String(100 * (i + 1)) }))),
        outlets: () => of([]),
      } }],
    });
    const f = TestBed.createComponent(ReportsComponent);
    f.detectChanges();
    await f.whenStable();
    f.detectChanges();
    return f.nativeElement as HTMLElement;
  }

  it('shows a trend only on the net sales tile', async () => {
    const tiles = Array.from((await render()).querySelectorAll('app-kpi-tile'));
    expect(tiles.length).toBe(5);
    expect(tiles.map((t) => t.querySelector('app-sparkline svg') !== null)).toEqual([true, false, false, false, false]);
  });

  it('tones a negative net margin and explains each tile', async () => {
    const tiles = Array.from((await render()).querySelectorAll('app-kpi-tile'));
    expect(tiles[4].querySelector('.kpi-value.negative')).not.toBeNull();
    expect(tiles[0].querySelector('.kpi-value.negative')).toBeNull();
    expect(tiles[1].querySelector('.kpi-hint')?.textContent).toContain('stock used');
  });

  it('wraps the chart and its tooltip in one positioned box inside a named scroll region', async () => {
    const el = await render();
    const region = el.querySelector('[role="region"][tabindex="0"]');
    expect(region?.getAttribute('aria-label')).toBe('Net sales by day chart');
    expect(region?.querySelector('.chart-box > svg.chart')).not.toBeNull();
  });
});
