import { DatePipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Subject, catchError, map, of, switchMap } from 'rxjs';
import { ReportingService } from '../../core/api.services';
import { BranchPerformance, DailySales, PeriodSummary } from '../../core/models';
import { MoneyPipe } from '../../core/util';
import { IconComponent } from '../../shared/icon';
import { AvatarComponent } from '../../shared/avatar';
import { KpiTileComponent } from '../../shared/kpi-tile';

type LoadState = 'loading' | 'ready' | 'error';
type RangeDays = 7 | 30 | 90;

interface Bar { x: number; y: number; w: number; h: number; label: string; value: string; path: string; }
interface GroupedBar extends Bar { series: 0 | 1; }

/** Report days are cut in Vietnam time (the server's default `app.reporting.zone`, which has no DST). */
const REPORT_UTC_OFFSET = '+07:00';
const REPORT_UTC_OFFSET_HOURS = 7;
const DAY_MS = 86_400_000;

/** Calendar date (yyyy-mm-dd) of an instant in the reporting zone. */
function reportDay(instant: number): string {
  return new Date(instant + REPORT_UTC_OFFSET_HOURS * 3_600_000).toISOString().slice(0, 10);
}

/** The last `count` report days ending today, oldest first, plus the instant bounds covering them. */
export function reportPeriod(now: number, count: number): { days: string[]; from: Date; to: Date } {
  const days: string[] = [];
  for (let i = count - 1; i >= 0; i--) days.push(reportDay(now - i * DAY_MS));
  const from = new Date(`${days[0]}T00:00:00${REPORT_UTC_OFFSET}`);
  const to = new Date(new Date(`${days[days.length - 1]}T00:00:00${REPORT_UTC_OFFSET}`).getTime() + DAY_MS);
  return { days, from, to };
}

/** One row per day in `days`; a day with no sales is a zero row, so a quiet day shows as a gap in the bars. */
export function zeroFillDays(days: string[], sales: DailySales[]): DailySales[] {
  const byDay = new Map(sales.map((d) => [d.day, d]));
  return days.map((day) => byDay.get(day) ?? { day, orderCount: 0, netSales: '0' });
}

const CHART_W = 640;
const CHART_H = 200;
const PAD_L = 46;
const PAD_B = 22;
const PAD_T = 10;

/** Top-rounded bar path (4px data-end, square baseline anchor). */
function barPath(x: number, y: number, w: number, h: number): string {
  const r = Math.min(4, w / 2, h);
  const yb = y + h;
  return `M${x},${yb} L${x},${y + r} Q${x},${y} ${x + r},${y} L${x + w - r},${y} Q${x + w},${y} ${x + w},${y + r} L${x + w},${yb} Z`;
}

@Component({
  selector: 'app-reports',
  standalone: true,
  imports: [DatePipe, MoneyPipe, IconComponent, AvatarComponent, KpiTileComponent],
  template: `
    <div class="page">
      <div class="page-head">
        <div><h1>Reports</h1><div class="sub">Sales, cost and labor performance for the active branch — and across outlets.</div></div>
        <div class="segmented" role="group" aria-label="Report period">
          @for (r of ranges; track r) {
            <button [class.active]="rangeDays() === r" (click)="setRange(r)">{{ r }} days</button>
          }
        </div>
      </div>

      <!-- headline tiles -->
      @if (summaryState() === 'loading') {
        <div class="tiles">
          @for (n of [1, 2, 3, 4, 5]; track n) { <div class="card skeleton tile-skel"></div> }
        </div>
      } @else if (summaryState() === 'error') {
        <div class="card"><div class="load-error" role="alert">Couldn't load the summary. <button type="button" class="btn btn-sm btn-outline" (click)="load()">Retry</button></div></div>
      } @else if (summary(); as s) {
        <div class="tiles">
          <app-kpi-tile label="Net sales" icon="banknote" [value]="s.netSales | money" [trend]="trend()" />
          <app-kpi-tile label="COGS" icon="package" [value]="s.cogs | money" hint="stock used, net of reversals" />
          <app-kpi-tile label="Labor" icon="users" [value]="s.laborCost | money" hint="completed shifts" />
          <app-kpi-tile label="Gross margin" icon="percent" [value]="s.grossMargin | money" hint="net sales − COGS" />
          <app-kpi-tile label="Net margin" icon="trending-up" [value]="s.netMargin | money" [tone]="isNeg(s.netMargin) ? 'negative' : 'default'" hint="gross margin − labor" />
        </div>
      }

      <!-- net sales by day -->
      <div class="card chart-card">
        <div class="card-head"><h2>Net sales by day</h2></div>
        @if (daysState() === 'loading') {
          <div class="loading-block"><span class="spinner"></span> Loading…</div>
        } @else if (daysState() === 'error') {
          <div class="load-error" role="alert">Couldn't load daily sales. <button type="button" class="btn btn-sm btn-outline" (click)="load()">Retry</button></div>
        } @else if (!hasSales()) {
          <div class="empty"><div class="big"><app-icon name="trending-up" [size]="30" /></div>No sales in this period.</div>
        } @else {
          <div class="chart-wrap" role="region" tabindex="0" aria-label="Net sales by day chart" (mouseleave)="hover.set(null)">
            <div class="chart-box">
            <svg [attr.viewBox]="'0 0 ' + W + ' ' + H" class="chart" role="img" aria-label="Bar chart of daily net sales; a data table follows.">
              @for (g of gridLines(); track g.y) {
                <line [attr.x1]="padL" [attr.x2]="W" [attr.y1]="g.y" [attr.y2]="g.y" class="grid" />
                <text [attr.x]="padL - 6" [attr.y]="g.y + 3" class="tick" text-anchor="end">{{ g.label }}</text>
              }
              @for (b of dayBars(); track b.label) {
                <path [attr.d]="b.path" class="bar s1" (mouseenter)="hover.set(b)" />
                <text [attr.x]="b.x + b.w / 2" [attr.y]="H - 6" class="tick" text-anchor="middle">{{ b.label }}</text>
              }
            </svg>
            @if (hover(); as t) {
              <div class="tip" [class.below]="t.y / H < 0.3" [style.left.%]="(t.x + t.w / 2) / W * 100" [style.top.%]="t.y / H * 100">
                <b>{{ t.value | money }}</b><span>{{ t.label }}</span>
              </div>
            }
            </div>
          </div>
          <table class="sr-only">
            <caption>Daily net sales</caption>
            <thead><tr><th scope="col">Day</th><th scope="col">Orders</th><th scope="col">Net sales</th></tr></thead>
            <tbody>
              @for (d of days(); track d.day) {
                <tr><td>{{ d.day }}</td><td>{{ d.orderCount }}</td><td>{{ d.netSales | money }}</td></tr>
              }
            </tbody>
          </table>
        }
      </div>

      <!-- outlet comparison -->
      <div class="card chart-card">
        <div class="card-head">
          <h2>Outlets — net sales vs labor</h2>
          <div class="legend" aria-hidden="true">
            <span class="chip"><i class="sw s1"></i>Net sales</span>
            <span class="chip"><i class="sw s2"></i>Labor</span>
          </div>
        </div>
        @if (outletsState() === 'loading') {
          <div class="loading-block"><span class="spinner"></span> Loading…</div>
        } @else if (outletsState() === 'error') {
          <div class="load-error" role="alert">Couldn't load outlets. <button type="button" class="btn btn-sm btn-outline" (click)="load()">Retry</button></div>
        } @else if (!outlets().length) {
          <div class="empty"><div class="big"><app-icon name="store" [size]="30" /></div>No active branches.</div>
        } @else {
          <div class="chart-wrap" role="region" tabindex="0" aria-label="Outlet net sales and labor chart" (mouseleave)="hoverOutlet.set(null)">
            <div class="chart-box">
            <svg [attr.viewBox]="'0 0 ' + W + ' ' + H" class="chart" role="img" aria-label="Grouped bar chart of net sales and labor per outlet; a data table follows.">
              @for (g of outletGrid(); track g.y) {
                <line [attr.x1]="padL" [attr.x2]="W" [attr.y1]="g.y" [attr.y2]="g.y" class="grid" />
                <text [attr.x]="padL - 6" [attr.y]="g.y + 3" class="tick" text-anchor="end">{{ g.label }}</text>
              }
              @for (b of outletBars(); track b.label + b.series) {
                <path [attr.d]="b.path" class="bar" [class.s1]="b.series === 0" [class.s2]="b.series === 1" (mouseenter)="hoverOutlet.set(b)" />
              }
              @for (o of outlets(); track o.branchId; let i = $index) {
                <text [attr.x]="groupCenter(i)" [attr.y]="H - 6" class="tick" text-anchor="middle">{{ o.code }}</text>
              }
            </svg>
            @if (hoverOutlet(); as t) {
              <div class="tip" [class.below]="t.y / H < 0.3" [style.left.%]="(t.x + t.w / 2) / W * 100" [style.top.%]="t.y / H * 100">
                <b>{{ t.value | money }}</b><span>{{ t.label }}</span>
              </div>
            }
            </div>
          </div>
          <div class="table-wrap">
            <table class="data">
              <caption class="sr-only">Outlet performance comparison</caption>
              <thead><tr>
                <th scope="col">Branch</th><th scope="col" class="right">Orders</th>
                <th scope="col" class="right">Net sales</th><th scope="col" class="right">COGS</th>
                <th scope="col" class="right">Labor</th><th scope="col" class="right">Net margin</th>
              </tr></thead>
              <tbody>
                @for (o of outlets(); track o.branchId) {
                  <tr>
                    <td><span class="name-with-avatar"><app-avatar [name]="o.name" /><b>{{ o.name }}</b></span></td>
                    <td class="num">{{ o.summary.orderCount }}</td>
                    <td class="num">{{ o.summary.netSales | money }}</td>
                    <td class="num">{{ o.summary.cogs | money }}</td>
                    <td class="num">{{ o.summary.laborCost | money }}</td>
                    <td class="num" [class.neg]="isNeg(o.summary.netMargin)">{{ o.summary.netMargin | money }}</td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        }
      </div>
      <p class="soft asof">@if (summary(); as s) { {{ s.orderCount }} orders · {{ s.grandTotal | money }} with tax · }Period: last {{ rangeDays() }} days · {{ from() | date:'mediumDate' }} – {{ to() | date:'mediumDate' }}</p>
    </div>
  `,
  styles: [`
    .tiles { display: grid; grid-template-columns: repeat(auto-fit, minmax(160px, 1fr)); gap: 1rem; margin-bottom: 1.2rem; }
    .tile-skel { height: 118px; }
    .neg { color: var(--red); }
    .chart-card { margin-bottom: 1.2rem; }
    .chart-card .card-head h2 { font-size: 16px; }
    .chart-wrap { padding: .4rem 1.2rem 0; overflow-x: auto; }
    .chart-box { position: relative; width: 100%; min-width: 640px; max-width: 860px; margin: 0 auto; }
    .chart { width: 100%; height: auto; display: block; }
    .grid { stroke: var(--border); stroke-width: 1; }
    .tick { font-size: 11px; fill: var(--text-muted); font-family: var(--font-mono); }
    .bar { transition: opacity var(--dur-1); cursor: default; }
    .bar.s1 { fill: var(--chart-1); }
    .bar.s2 { fill: var(--chart-2); }
    .chart-wrap:hover .bar:not(:hover) { opacity: .75; }
    .tip { position: absolute; transform: translate(-50%, -110%); pointer-events: none;
      display: flex; flex-direction: column; align-items: center; gap: 1px;
      background: var(--surface-1); border: 1px solid var(--border-strong); border-radius: 8px;
      padding: .3rem .55rem; box-shadow: var(--shadow-pop); white-space: nowrap; z-index: 5; }
    .tip.below { transform: translate(-50%, 8px); }
    .tip b { font-family: var(--font-mono); font-variant-numeric: tabular-nums; font-size: 12.5px; }
    .tip span { font-size: 10.5px; color: var(--text-muted); }
    .legend { display: flex; gap: .9rem; }
    .chip { display: inline-flex; align-items: center; gap: .35rem; font-size: 12px; color: var(--text-soft); font-weight: 600; }
    .sw { width: 10px; height: 10px; border-radius: 3px; display: inline-block; }
    .sw.s1 { background: var(--chart-1); }
    .sw.s2 { background: var(--chart-2); }
    .load-error { display: flex; align-items: center; justify-content: center; gap: .8rem; padding: 2rem 1rem; color: var(--text-soft); font-size: 14px; }
    .asof { font-size: 12px; }
  `],
})
export class ReportsComponent {
  private api = inject(ReportingService);

  readonly W = CHART_W;
  readonly H = CHART_H;
  readonly padL = PAD_L;
  readonly ranges: RangeDays[] = [7, 30, 90];

  rangeDays = signal<RangeDays>(7);
  summary = signal<PeriodSummary | null>(null);
  days = signal<DailySales[]>([]);
  outlets = signal<BranchPerformance[]>([]);
  summaryState = signal<LoadState>('loading');
  daysState = signal<LoadState>('loading');
  outletsState = signal<LoadState>('loading');
  hover = signal<Bar | null>(null);
  hoverOutlet = signal<Bar | null>(null);
  from = signal<Date>(new Date());
  to = signal<Date>(new Date());

  private reload$ = new Subject<{ from: string; to: string; days: string[] }>();
  trend = computed(() => this.days().map((d) => Number(d.netSales)));
  hasSales = computed(() => this.days().some((d) => d.orderCount > 0));

  constructor() {
    // switchMap drops an in-flight request when the period changes, so a slow old answer can't overwrite a newer one.
    this.reload$.pipe(
      switchMap(({ from, to }) => this.api.summary(from, to).pipe(catchError(() => of(null)))),
      takeUntilDestroyed(),
    ).subscribe((s) => {
      if (s) { this.summary.set(s); this.summaryState.set('ready'); } else this.summaryState.set('error');
    });
    this.reload$.pipe(
      switchMap(({ from, to, days }) => this.api.salesByDay(from, to).pipe(
        map((d) => zeroFillDays(days, d)),
        catchError(() => of(null)),
      )),
      takeUntilDestroyed(),
    ).subscribe((d) => {
      if (d) { this.days.set(d); this.daysState.set('ready'); } else this.daysState.set('error');
    });
    this.reload$.pipe(
      switchMap(({ from, to }) => this.api.outlets(from, to).pipe(catchError(() => of(null)))),
      takeUntilDestroyed(),
    ).subscribe((o) => {
      if (o) { this.outlets.set(o); this.outletsState.set('ready'); } else this.outletsState.set('error');
    });
    this.load();
  }

  setRange(r: RangeDays) { this.rangeDays.set(r); this.load(); }

  isNeg(v: string) { return Number(v) < 0; }

  load() {
    const period = reportPeriod(Date.now(), this.rangeDays());
    this.from.set(period.from); this.to.set(new Date(period.to.getTime() - 1)); // `to` is exclusive; show the last day
    this.summaryState.set('loading');
    this.daysState.set('loading');
    this.outletsState.set('loading');
    this.reload$.next({ from: period.from.toISOString(), to: period.to.toISOString(), days: period.days });
  }

  // --- daily bars ---

  private maxDay = computed(() => Math.max(1, ...this.days().map((d) => Number(d.netSales))));

  dayBars = computed<Bar[]>(() => {
    const data = this.days();
    if (!data.length) return [];
    const plotW = CHART_W - PAD_L;
    const plotH = CHART_H - PAD_T - PAD_B;
    const slot = plotW / data.length;
    const w = Math.max(3, Math.min(34, slot - 2)); // ≥2px spacer between bars
    const max = this.maxDay();
    return data.map((d, i) => {
      const h = Math.max(1, (Number(d.netSales) / max) * plotH);
      const x = PAD_L + i * slot + (slot - w) / 2;
      const y = PAD_T + plotH - h;
      return { x, y, w, h, label: this.dayLabel(d.day, data.length), value: d.netSales, path: barPath(x, y, w, h) };
    });
  });

  gridLines = computed(() => this.ticks(this.maxDay()));

  private dayLabel(iso: string, count: number): string {
    const d = new Date(iso + 'T00:00:00Z');
    if (count > 14) {
      // avoid cramped ticks: label ~weekly
      return d.getUTCDate() === 1 || d.getUTCDay() === 1 ? `${d.getUTCDate()}/${d.getUTCMonth() + 1}` : '';
    }
    return `${d.getUTCDate()}/${d.getUTCMonth() + 1}`;
  }

  // --- outlet grouped bars ---

  private maxOutlet = computed(() => Math.max(1,
    ...this.outlets().flatMap((o) => [Number(o.summary.netSales), Number(o.summary.laborCost)])));

  outletBars = computed<GroupedBar[]>(() => {
    const data = this.outlets();
    if (!data.length) return [];
    const plotW = CHART_W - PAD_L;
    const plotH = CHART_H - PAD_T - PAD_B;
    const slot = plotW / data.length;
    const w = Math.max(4, Math.min(28, (slot - 10) / 2 - 1));
    const max = this.maxOutlet();
    const bars: GroupedBar[] = [];
    data.forEach((o, i) => {
      const cx = PAD_L + i * slot + slot / 2;
      const pairs: Array<[0 | 1, string, string]> = [
        [0, o.summary.netSales, `${o.name} · net sales`],
        [1, o.summary.laborCost, `${o.name} · labor`],
      ];
      pairs.forEach(([series, value, label]) => {
        const h = Math.max(1, (Number(value) / max) * plotH);
        const x = series === 0 ? cx - w - 1 : cx + 1; // 2px spacer between the pair
        const y = PAD_T + plotH - h;
        bars.push({ x, y, w, h, label, value, series, path: barPath(x, y, w, h) });
      });
    });
    return bars;
  });

  outletGrid = computed(() => this.ticks(this.maxOutlet()));

  groupCenter(i: number): number {
    const slot = (CHART_W - PAD_L) / Math.max(1, this.outlets().length);
    return PAD_L + i * slot + slot / 2;
  }

  // --- shared scale helpers ---

  private ticks(max: number) {
    const plotH = CHART_H - PAD_T - PAD_B;
    const steps = [0, 0.5, 1];
    return steps.map((s) => ({
      y: PAD_T + plotH - s * plotH,
      label: this.compact(max * s),
    }));
  }

  private compact(v: number): string {
    if (v >= 1_000_000_000) return (v / 1_000_000_000).toFixed(1) + 'B';
    if (v >= 1_000_000) return (v / 1_000_000).toFixed(1) + 'M';
    if (v >= 1_000) return (v / 1_000).toFixed(0) + 'K';
    return String(Math.round(v));
  }
}
