import { Component, ElementRef, HostListener, ViewChild, effect, inject } from '@angular/core';
import { ToastService } from '../core/toast.service';
import { ConfirmService } from '../core/confirm.service';
import { IconComponent } from './icon';

/** App-level toast stack. Rendered once in the root. */
@Component({
  selector: 'app-toast-host',
  standalone: true,
  imports: [IconComponent],
  template: `
    <div class="toast-host" aria-live="polite" aria-relevant="additions">
      @for (t of toast.toasts(); track t.id) {
        <div class="toast" [class]="'toast-' + t.kind" (click)="toast.dismiss(t.id)"
             [attr.role]="t.kind === 'error' ? 'alert' : 'status'">
          <span class="t-ic" [class]="'ti-' + t.kind" aria-hidden="true"><app-icon [name]="icon(t.kind)" [size]="16" /></span>
          <span class="t-txt">{{ t.text }}</span>
          <button type="button" class="t-close" (click)="toast.dismiss(t.id)" aria-label="Dismiss notification">
            <app-icon name="close" [size]="14" />
          </button>
        </div>
      }
    </div>
  `,
  styles: [`
    .toast-host { position: fixed; bottom: 1.3rem; right: 1.3rem; z-index: 200;
      display: flex; flex-direction: column; gap: .65rem; max-width: 380px; }
    .toast { display: flex; align-items: center; gap: .65rem; padding: .8rem .95rem;
      border-radius: var(--radius); background: var(--material); color: var(--text);
      border: 1px solid var(--hairline); box-shadow: var(--shadow-lg); cursor: pointer;
      font-size: 14px; font-weight: 500; animation: slide var(--dur-3) var(--ease-spring);
      backdrop-filter: var(--blur); -webkit-backdrop-filter: var(--blur); }
    .t-ic { width: 26px; height: 26px; border-radius: 50%; flex-shrink: 0;
      display: flex; align-items: center; justify-content: center; color: #fff; }
    .t-txt { flex: 1; }
    .t-close { background: none; border: none; padding: .3rem; margin: -.2rem -.3rem -.2rem 0;
      display: inline-flex; align-items: center; justify-content: center; border-radius: 7px;
      color: var(--text-muted); cursor: pointer; }
    .t-close:hover { color: var(--text); background: var(--surface-2); }
    .ti-success { background: var(--green); }
    .ti-error   { background: var(--red); }
    .ti-info    { background: var(--azure); }
    @keyframes slide { from { opacity: 0; transform: translateX(24px) scale(.96); } }
  `],
})
export class ToastHostComponent {
  toast = inject(ToastService);
  icon(kind: string) { return kind === 'success' ? 'check' : kind === 'error' ? 'alert' : 'info'; }
}

/** App-level confirmation dialog driven by ConfirmService. */
@Component({
  selector: 'app-confirm-host',
  standalone: true,
  imports: [IconComponent],
  template: `
    @if (confirm.pending(); as p) {
      <div class="c-backdrop" (click)="confirm.answer(false)">
        <div class="c-box" #box (click)="$event.stopPropagation()"
             role="alertdialog" aria-modal="true" aria-labelledby="confirm-title"
             [attr.aria-describedby]="p.message ? 'confirm-msg' : null">
          <span class="c-ic" [class.danger]="p.danger" aria-hidden="true">
            <app-icon [name]="p.danger ? 'alert' : 'info'" [size]="22" />
          </span>
          <h3 id="confirm-title">{{ p.title }}</h3>
          @if (p.message) { <p id="confirm-msg">{{ p.message }}</p> }
          <div class="c-actions">
            <button class="btn btn-outline c-cancel" (click)="confirm.answer(false)">{{ p.cancelText || 'Cancel' }}</button>
            <button class="btn" [class.btn-danger]="p.danger" [class.btn-primary]="!p.danger"
                    (click)="confirm.answer(true)">{{ p.confirmText || 'Confirm' }}</button>
          </div>
        </div>
      </div>
    }
  `,
  styles: [`
    .c-backdrop { position: fixed; inset: 0; z-index: 150; display: flex; align-items: center;
      justify-content: center; padding: 1rem; background: var(--scrim);
      backdrop-filter: var(--blur); -webkit-backdrop-filter: var(--blur); animation: fade var(--dur-2) var(--ease-out); }
    .c-box { width: 100%; max-width: 400px; background: var(--surface); border-radius: var(--radius-lg);
      border: 1px solid var(--hairline); box-shadow: var(--shadow-pop); padding: 1.6rem;
      text-align: center; animation: sheet var(--dur-3) var(--ease-spring); }
    .c-ic { width: 52px; height: 52px; margin: 0 auto 1rem; border-radius: 50%;
      display: flex; align-items: center; justify-content: center;
      background: var(--azure-soft); color: var(--azure); }
    .c-ic.danger { background: var(--red-soft); color: var(--red); }
    .c-box h3 { font-size: 18px; margin-bottom: .5rem; letter-spacing: -.02em; }
    .c-box p { color: var(--text-soft); margin: 0 0 1.4rem; font-size: 14px; line-height: 1.5; }
    .c-actions { display: flex; justify-content: center; gap: .6rem; }
    .c-actions .btn { min-width: 104px; }
    @keyframes fade { from { opacity: 0; } }
    @keyframes sheet { from { opacity: 0; transform: translateY(12px) scale(.96); } }
  `],
})
export class ConfirmHostComponent {
  confirm = inject(ConfirmService);

  @ViewChild('box') box?: ElementRef<HTMLElement>;
  private opener: HTMLElement | null = null;

  constructor() {
    // Move focus into the dialog on open (Cancel first — least destructive),
    // and hand it back to the trigger on close.
    effect(() => {
      if (this.confirm.pending()) {
        this.opener = document.activeElement instanceof HTMLElement ? document.activeElement : null;
        queueMicrotask(() => this.box?.nativeElement.querySelector<HTMLElement>('.c-cancel')?.focus());
      } else if (this.opener) {
        this.opener.focus();
        this.opener = null;
      }
    });
  }

  @HostListener('document:keydown.escape')
  onEscape() { if (this.confirm.pending()) this.confirm.answer(false); }

  /** Keep Tab / Shift+Tab inside the dialog while it is open. */
  @HostListener('document:keydown', ['$event'])
  trapTab(e: KeyboardEvent) {
    if (e.key !== 'Tab' || !this.confirm.pending()) return;
    const root = this.box?.nativeElement;
    if (!root) return;
    const focusables = Array.from(root.querySelectorAll<HTMLElement>('button:not([disabled])'));
    if (!focusables.length) return;
    const first = focusables[0];
    const last = focusables[focusables.length - 1];
    const active = document.activeElement;
    if (e.shiftKey && (active === first || !root.contains(active))) {
      e.preventDefault(); last.focus();
    } else if (!e.shiftKey && (active === last || !root.contains(active))) {
      e.preventDefault(); first.focus();
    }
  }
}
