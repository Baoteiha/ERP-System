import { Component, input } from '@angular/core';

/** The Active / Inactive pill every list shows; colours come from the badge tokens in styles.scss. */
@Component({
  selector: 'app-status-badge',
  standalone: true,
  template: `<span class="badge dot" [class.badge-green]="active()" [class.badge-gray]="!active()">{{ active() ? activeLabel() : inactiveLabel() }}</span>`,
})
export class StatusBadgeComponent {
  active = input.required<boolean>();
  activeLabel = input('Active');
  inactiveLabel = input('Inactive');
}
