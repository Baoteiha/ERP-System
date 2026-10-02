import { Component, computed, input } from '@angular/core';

/** Builds the SVG path of a sparkline: x spans the width, y is flipped (bigger is higher) with a 1px inset. */
export function sparkPath(raw: number[], w: number, h: number): string {
  const values = raw.filter(Number.isFinite);
  if (values.length < 2) return '';
  const min = Math.min(...values);
  const max = Math.max(...values);
  return values
    .map((v, i) => {
      const x = (i / (values.length - 1)) * w;
      const y = max === min ? h / 2 : h - 1 - ((v - min) / (max - min)) * (h - 2);
      return `${i === 0 ? 'M' : 'L'}${x.toFixed(1)},${y.toFixed(1)}`;
    })
    .join(' ');
}

/** A small decorative trend line with a soft fill; renders nothing for fewer than two values. */
@Component({
  selector: 'app-sparkline',
  standalone: true,
  template: `
    @if (line()) {
      <svg [attr.width]="width()" [attr.height]="height()" [attr.viewBox]="'0 0 ' + width() + ' ' + height()" aria-hidden="true" focusable="false">
        <path class="spark-fill" [attr.d]="fill()" />
        <path class="spark-line" [attr.d]="line()" />
      </svg>
    }
  `,
  styles: [`
    :host { display: inline-flex; }
    svg { display: block; overflow: visible; }
    .spark-line { fill: none; stroke: var(--accent); stroke-width: 1.5; stroke-linecap: round; stroke-linejoin: round; }
    .spark-fill { fill: var(--accent); opacity: .12; stroke: none; }
  `],
})
export class SparklineComponent {
  values = input<number[]>([]);
  width = input(96);
  height = input(28);
  line = computed(() => sparkPath(this.values(), this.width(), this.height()));
  fill = computed(() => (this.line() ? `${this.line()} L${this.width()},${this.height()} L0,${this.height()} Z` : ''));
}
