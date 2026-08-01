import { Component } from '@angular/core';

/**
 * Three rising steam wisps. Purely decorative (aria-hidden) and colored by
 * `currentColor`. The `.steam`/`.wisp` styles live in styles.scss; the wisps'
 * base opacity is 0 and only transform/opacity animate, so the global
 * prefers-reduced-motion kill-switch leaves them invisible.
 * Usage: <app-steam /> positioned by the parent.
 */
@Component({
  selector: 'app-steam',
  standalone: true,
  template: `<span class="steam" aria-hidden="true"><span class="wisp"></span><span class="wisp"></span><span class="wisp"></span></span>`,
  styles: [':host { display: inline-flex; line-height: 0; }'],
})
export class SteamComponent {}
