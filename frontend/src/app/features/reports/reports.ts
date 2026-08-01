import { DatePipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { ReportingService } from '../../core/api.services';
import { BranchPerformance, DailySales, PeriodSummary } from '../../core/models';
import { MoneyPipe } from '../../core/util';
import { IconComponent } from '../../shared/icon';
import { SteamComponent } from '../../shared/steam';

type LoadState = 'loading' | 'ready' | 'error';
type RangeDays = 7 | 30 | 90;

interface Bar { x: number; y: number; w: number; h: number; label: string; value: string; path: string; }
interface GroupedBar extends Bar { series: 0 | 1; }

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
  imports: [DatePipe, MoneyPipe, IconComponent, SteamComponent],
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
        <div class="card"><div class="brewing"><span class="brew-cup cup-steamed"><app-icon name="cup" [size]="20" /><app-steam /></span> Brewing numbers…</div></div>
      } @else if (summaryState() === 'error') {
        <div class="card"><div class="load-error" role="alert">Couldn't load the summary. <button type="button" class="btn btn-sm btn-outline" (click)="load()">Retry</button></div></div>
      } @else if (summary(); as s) {
        <div class="tiles">
          <div class="tile grain"><div class="t-lbl">Revenue</div><div class="t-val">{{ s.revenue | money }}</div><div class="t-sub">{{ s.orderCount }} orders</div></div>
          <div class="tile grain"><div class="t-lbl">COGS (from depletion)</div><div class="t-val">{{ s.cogsDepletion | money }}</div><div class="t-sub">orders view {{ s.cogsOrders | money }}</div></div>
          <div class="tile grain"><div class="t-lbl">Labor</div><div class="t-val">{{ s.laborCost | money }}</div><div class="t-sub">completed shifts</div></div>
          <div class="tile grain"><div class="t-lbl">Gross margin</div><div class="t-val">{{ s.grossMargin | money }}</div><div class="t-sub">revenue − COGS</div></div>
          <div class="tile grain"><div class="t-lbl">Net margin</div><div class="t-val" [class.neg]="isNeg(s.netMargin)">{{ s.netMargin | money }}</div><div class="t-sub">− labor</div></div>
        </div>
      }

      <!-- revenue by day -->
      <div class="card chart-card">
        <div class="card-head"><h2>Revenue by day</h2></div>
        @if (daysState() === 'loading') {
          <div class="loading-block"><span class="spinner"></span> Loading…</div>
        } @else if (daysState() === 'error') {
          <div class="load-error" role="alert">Couldn't load daily sales. <button type="button" class="btn btn-sm btn-outline" (click)="load()">Retry</button></div>
        } @else if (!days().length) {
          <div class="empty"><div class="big"><app-icon name="trending-up" [size]="30" /></div>No sales in this period.</div>
        } @else {
          <div class="chart-wrap" (mouseleave)="hover.set(null)">
            <svg [attr.viewBox]="'0 0 ' + W + ' ' + H" class="chart" role="img" aria-label="Bar chart of daily revenue; a data table follows.">
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
              <div class="tip" [style.left.%]="(t.x + t.w / 2) / W * 100" [style.top.%]="t.y / H * 100">
                <b>{{ t.value | money }}</b><span>{{ t.label }}</span>
              </div>
            }
          </div>
          <table class="sr-only">
            <caption>Daily revenue</caption>
            <thead><tr><th scope="col">Day</th><th scope="col">Orders</th><th scope="col">Revenue</th></tr></thead>
            <tbody>
              @for (d of days(); track d.day) {
                <tr><td>{{ d.day }}</td><td>{{ d.orderCount }}</td><td>{{ d.revenue | money }}</td></tr>
              }
            </tbody>
          </table>
        }
      </div>

      <!-- outlet comparison -->
      <div class="card chart-card">
        <div class="card-head">
          <h2>Outlets — revenue vs labor</h2>
          <div class="legend" aria-hidden="true">
            <span class="chip"><i class="sw s1"></i>Revenue</span>
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
          <div class="chart-wrap" (mouseleave)="hover.set(null)">
            <svg [attr.viewBox]="'0 0 ' + W + ' ' + H" class="chart" role="img" aria-label="Grouped bar chart of revenue and labor per outlet; a data table follows.">
              @for (g of outletGrid(); track g.y) {
                <line [attr.x1]="padL" [attr.x2]="W" [attr.y1]="g.y" [attr.y2]="g.y" class="grid" />
                <text [attr.x]="padL - 6" [attr.y]="g.y + 3" class="tick" text-anchor="end">{{ g.label }}</text>
              }
              @for (b of outletBars(); track b.label + b.series) {
                <path [attr.d]="b.path" class="bar" [class.s1]="b.series === 0" [class.s2]="b.series === 1" (mouseenter)="hover.set(b)" />
              }
              @for (o of outlets(); track o.branchId; let i = $index) {
                <text [attr.x]="groupCenter(i)" [attr.y]="H - 6" class="tick" text-anchor="middle">{{ o.code }}</text>
              }
            </svg>
            @if (hover(); as t) {
              <div class="tip" [style.left.%]="(t.x + t.w / 2) / W * 100" [style.top.%]="t.y / H * 100">
                <b>{{ t.value | money }}</b><span>{{ t.label }}</span>
              </div>
            }
          </div>
          <div class="table-wrap">
            <table class="data">
              <caption class="sr-only">Outlet performance comparison</caption>
              <thead><tr>
                <th scope="col">Branch</th><th scope="col" class="right">Orders</th>
                <th scope="col" class="right">Revenue</th><th scope="col" class="right">COGS</th>
                <th scope="col" class="right">Labor</th><th scope="col" class="right">Net margin</th>
              </tr></thead>
              <tbody>
                @for (o of outlets(); track o.branchId) {
                  <tr>
                    <td><span class="name-cell"><span class="mini-coin" aria-hidden="true"><app-icon name="store" [size]="14" /></span><b>{{ o.name }}</b></span></td>
                    <td class="num">{{ o.summary.orderCount }}</td>
                    <td class="num">{{ o.summary.revenue | money }}</td>
                    <td class="num">{{ o.summary.cogsDepletion | money }}</td>
                    <td class="num">{{ o.summary.laborCost | money }}</td>
                    <td class="num" [class.neg]="isNeg(o.summary.netMargin)">{{ o.summary.netMargin | money }}</td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        }
      </div>
      <p class="soft asof">Period: last {{ rangeDays() }} days · {{ from() | date:'mediumDate' }} – {{ to() | date:'mediumDate' }}</p>
    </div>
  `,
  styles: [`
    .tiles { display: grid; grid-template-columns: repeat(auto-fit, minmax(190px, 1fr)); gap: 1rem; margin-bottom: 1.2rem; }
    .tile { padding: 1.1rem 1.2rem; background: var(--surface); border: 1px solid var(--border);
      border-radius: var(--radius-lg); box-shadow: var(--shadow-sm), inset 0 0 0 6px color-mix(in srgb, var(--wood-mid) 20%, var(--surface)); }
    .t-lbl { font-size: 12px; color: var(--text-muted); font-weight: 600; }
    .t-val { font-family: var(--font-mono); font-variant-numeric: tabular-nums; font-size: 20px; font-weight: 700; margin-top: .2rem; }
    .t-sub { font-size: 11.5px; color: var(--text-muted); margin-top: .15rem; }
    .neg { color: var(--red); }
    .chart-card { margin-bottom: 1.2rem; }
    .chart-card .card-head h2 { font-size: 16px; }
    .chart-wrap { position: relative; padding: .4rem 1.2rem 0; }
    .chart { width: 100%; height: auto; display: block; }
    .grid { stroke: var(--border); stroke-width: 1; }
    .tick { font-size: 10px; fill: var(--text-muted); font-family: var(--font-mono); }
    .bar { transition: opacity var(--dur-1); cursor: default; }
    .bar.s1 { fill: var(--chart-1); }
    .bar.s2 { fill: var(--chart-2); }
    .chart-wrap:hover .bar:not(:hover) { opacity: .75; }
    .tip { position: absolute; transform: translate(-50%, -110%); pointer-events: none;
      display: flex; flex-direction: column; align-items: center; gap: 1px;
      background: var(--surface); border: 1px solid var(--border-strong); border-radius: 8px;
      padding: .3rem .55rem; box-shadow: var(--shadow-md); white-space: nowrap; z-index: 5; }
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
  from = signal<Date>(new Date());
  to = signal<Date>(new Date());

  constructor() { this.load(); }

  setRange(r: RangeDays) { this.rangeDays.set(r); this.load(); }

  isNeg(v: string) { return Number(v) < 0; }

  load() {
    const to = new Date();
    const from = new Date(to.getTime() - this.rangeDays() * 86_400_000);
    this.from.set(from); this.to.set(to);
    const f = from.toISOString(), t = to.toISOString();

    this.summaryState.set('loading');
    this.api.summary(f, t).subscribe({
      next: (s) => { this.summary.set(s); this.summaryState.set('ready'); },
      error: () => this.summaryState.set('error'),
    });
    this.daysState.set('loading');
    this.api.salesByDay(f, t).subscribe({
      next: (d) => { this.days.set(d); this.daysState.set('ready'); },
      error: () => this.daysState.set('error'),
    });
    this.outletsState.set('loading');
    this.api.outlets(f, t).subscribe({
      next: (o) => { this.outlets.set(o); this.outletsState.set('ready'); },
      error: () => this.outletsState.set('error'),
    });
  }

  // --- daily bars ---

  private maxDay = computed(() => Math.max(1, ...this.days().map((d) => Number(d.revenue))));

  dayBars = computed<Bar[]>(() => {
    const data = this.days();
    if (!data.length) return [];
    const plotW = CHART_W - PAD_L;
    const plotH = CHART_H - PAD_T - PAD_B;
    const slot = plotW / data.length;
    const w = Math.max(3, Math.min(34, slot - 2)); // ≥2px spacer between bars
    const max = this.maxDay();
    return data.map((d, i) => {
      const h = Math.max(1, (Number(d.revenue) / max) * plotH);
      const x = PAD_L + i * slot + (slot - w) / 2;
      const y = PAD_T + plotH - h;
      return { x, y, w, h, label: this.dayLabel(d.day, data.length), value: d.revenue, path: barPath(x, y, w, h) };
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
    ...this.outlets().flatMap((o) => [Number(o.summary.revenue), Number(o.summary.laborCost)])));

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
        [0, o.summary.revenue, `${o.name} · revenue`],
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
