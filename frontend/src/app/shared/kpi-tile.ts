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
      <div class="kpi-value">{{ value() }}</div>
      <div class="kpi-foot">
        @if (delta() !== null) {
          <span class="kpi-delta" [class.up]="delta()! >= 0" [class.down]="delta()! < 0">
            <app-icon [name]="delta()! >= 0 ? 'arrow-up' : 'arrow-down'" [size]="12" aria-hidden="true" />{{ deltaText() }}
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
  deltaText = computed(() => {
    const d = this.delta() ?? 0;
    return (d >= 0 ? '+' : '−') + Math.abs(d * 100).toFixed(1) + '%';
  });
}
