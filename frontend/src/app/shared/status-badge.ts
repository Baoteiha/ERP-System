import { Component, input } from '@angular/core';

/** The Active / Inactive pill every list shows; colours come from the badge tokens in styles.scss. */
@Component({
  selector: 'app-status-badge',
  standalone: true,
  template: `<span class="badge dot" [class.badge-green]="active()" [class.badge-gray]="!active()"
                   [class.badge-sm]="size() === 'sm'" [class.badge-xs]="size() === 'xs'">{{ active() ? activeLabel() : inactiveLabel() }}</span>`,
  styles: [`
    :host { display: inline-flex; }
    .badge-sm { height: 20px; font-size: 10.5px; padding: 0 .5rem; }
    .badge-xs { height: 19px; font-size: 10px; padding: 0 .45rem; }
  `],
})
export class StatusBadgeComponent {
  active = input.required<boolean>();
  activeLabel = input('Active');
  inactiveLabel = input('Inactive');
  /** `sm` and `xs` are the compact pills used on cards and tags. */
  size = input<'md' | 'sm' | 'xs'>('md');
}
