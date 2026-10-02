import { Component, inject } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { ToastHostComponent, ConfirmHostComponent } from './shared/overlays';
import { ThemeService } from './core/theme.service';
import { DensityService } from './core/density.service';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, ToastHostComponent, ConfirmHostComponent],
  template: `
    <router-outlet />
    <app-toast-host />
    <app-confirm-host />
  `,
})
export class App {
  // constructed at start-up so the theme and density apply before the first paint of any page
  constructor() { inject(ThemeService); inject(DensityService); }
}
