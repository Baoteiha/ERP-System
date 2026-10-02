import { Component, computed, input } from '@angular/core';
import { IconComponent } from './icon';
import { SparklineComponent } from './sparkline';

/** A key-number tile: label, big value, change against the previous period and a trend sparkline. */
@Component({
  selector: 'app-kpi-tile',
  standalone: true,
  imports: [IconComponent, SparklineComponent],
  template: `
    <div class="kpi card">
      <div class="kpi-top"><span class="kpi-label">{{ label() }}</span><span class="kpi-ic" aria-hidden="true"><app-icon [name]="icon()" [size]="16" /></span></div>
      <div class="kpi-value" [class.negative]="tone() === 'negative'">{{ value() }}</div>
      @if (hint(); as h) { <div class="kpi-hint">{{ h }}</div> }
      <div class="kpi-foot">
        @if (change(); as c) {
          <span class="kpi-delta" [class.up]="c.up" [class.down]="!c.up">
            <app-icon [name]="c.up ? 'arrow-up' : 'arrow-down'" [size]="12" aria-hidden="true" />{{ c.text }}
          </span>
          <span class="sr-only">compared with the previous period</span>
        }
        <app-sparkline [values]="trend()" />
      </div>
    </div>
  `,
  styles: [`
    :host { display: block; }
    .kpi { padding: 1rem 1.1rem; display: flex; flex-direction: column; gap: .35rem; height: 100%; }
    .kpi-top { display: flex; align-items: center; justify-content: space-between; color: var(--text-muted); font-size: 13px; }
    .kpi-ic { display: inline-flex; color: var(--accent-text); }
    .kpi-value { font-family: var(--font-display); font-size: 26px; font-weight: 600; font-variant-numeric: tabular-nums; line-height: 1.15; }
    .kpi-value.negative { color: var(--red); }
    .kpi-hint { font-size: 12px; color: var(--text-muted); }
    .kpi-foot { display: flex; align-items: center; justify-content: space-between; gap: .6rem; min-height: 28px; }
    .kpi-delta { display: inline-flex; align-items: center; gap: .2rem; font-family: var(--font-mono); font-size: 12.5px; font-weight: 500; }
    .kpi-delta.up { color: var(--green); }
    .kpi-delta.down { color: var(--red); }
    app-sparkline { margin-left: auto; }
  `],
})
export class KpiTileComponent {
  label = input.required<string>();
  value = input.required<string>();
  /** Fraction against the previous period: 0.12 means +12%. Null hides the change line. */
  delta = input<number | null>(null);
  trend = input<number[]>([]);
  icon = input('banknote');
  /** 'negative' paints the value red. */
  tone = input<'default' | 'negative'>('default');
  /** One muted line under the value; null hides it. */
  hint = input<string | null>(null);
  /** The change line, rounded before the sign is chosen; null for a missing or non-finite delta. */
  change = computed(() => {
    const d = this.delta();
    if (d === null || !Number.isFinite(d)) return null;
    const pct = Math.round(d * 1000) / 10;
    const up = pct >= 0;
    return { up, text: (up ? '+' : '−') + Math.abs(pct).toFixed(1) + '%' };
  });
}
