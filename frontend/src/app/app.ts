import { Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { ToastHostComponent, ConfirmHostComponent } from './shared/overlays';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, ToastHostComponent, ConfirmHostComponent],
  template: `
    <router-outlet />
    <app-toast-host />
    <app-confirm-host />
  `,
})
export class App {}
