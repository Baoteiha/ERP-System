import { Component, inject } from '@angular/core';
import { ToastService } from '../core/toast.service';
import { ConfirmService } from '../core/confirm.service';

/** App-level toast stack. Rendered once in the root. */
@Component({
  selector: 'app-toast-host',
  standalone: true,
  template: `
    <div class="toast-host">
      @for (t of toast.toasts(); track t.id) {
        <div class="toast" [class]="'toast-' + t.kind" (click)="toast.dismiss(t.id)">
          <span class="ic">{{ icon(t.kind) }}</span>
          <span>{{ t.text }}</span>
        </div>
      }
    </div>
  `,
  styles: [`
    .toast-host { position: fixed; bottom: 1.2rem; right: 1.2rem; z-index: 200;
      display: flex; flex-direction: column; gap: .6rem; max-width: 380px; }
    .toast { display: flex; align-items: flex-start; gap: .6rem; padding: .8rem 1rem;
      border-radius: var(--radius); background: var(--surface); color: var(--text);
      border: 1px solid var(--border); box-shadow: var(--shadow-lg); cursor: pointer;
      font-size: 13.5px; animation: slide .18s ease; border-left-width: 4px; }
    .toast .ic { font-size: 15px; line-height: 1.3; }
    .toast-success { border-left-color: var(--green); }
    .toast-error   { border-left-color: var(--red); }
    .toast-info    { border-left-color: var(--blue); }
    @keyframes slide { from { opacity: 0; transform: translateX(20px); } }
  `],
})
export class ToastHostComponent {
  toast = inject(ToastService);
  icon(kind: string) { return kind === 'success' ? '✓' : kind === 'error' ? '⚠' : 'ℹ'; }
}

/** App-level confirmation dialog driven by ConfirmService. */
@Component({
  selector: 'app-confirm-host',
  standalone: true,
  template: `
    @if (confirm.pending(); as p) {
      <div class="c-backdrop" (click)="confirm.answer(false)">
        <div class="c-box" (click)="$event.stopPropagation()">
          <h3>{{ p.title }}</h3>
          @if (p.message) { <p>{{ p.message }}</p> }
          <div class="c-actions">
            <button class="btn btn-outline" (click)="confirm.answer(false)">{{ p.cancelText || 'Cancel' }}</button>
            <button class="btn" [class.btn-danger]="p.danger" [class.btn-primary]="!p.danger"
                    (click)="confirm.answer(true)">{{ p.confirmText || 'Confirm' }}</button>
          </div>
        </div>
      </div>
    }
  `,
  styles: [`
    .c-backdrop { position: fixed; inset: 0; z-index: 150; display: flex; align-items: center;
      justify-content: center; padding: 1rem; background: rgba(15,20,28,.55); backdrop-filter: blur(2px); }
    .c-box { width: 100%; max-width: 400px; background: var(--surface); border-radius: var(--radius-lg);
      border: 1px solid var(--border); box-shadow: var(--shadow-lg); padding: 1.5rem; animation: pop .14s ease; }
    .c-box h3 { font-size: 16px; margin-bottom: .5rem; }
    .c-box p { color: var(--text-soft); margin: 0 0 1.3rem; font-size: 13.5px; }
    .c-actions { display: flex; justify-content: flex-end; gap: .6rem; }
    @keyframes pop { from { opacity: 0; transform: scale(.97); } }
  `],
})
export class ConfirmHostComponent {
  confirm = inject(ConfirmService);
}
