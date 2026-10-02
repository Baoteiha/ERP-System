import { AfterViewInit, Component, ElementRef, EventEmitter, HostListener, Input, OnDestroy, Output, ViewChild, inject } from '@angular/core';
import { IconComponent } from './icon';
import { OverlayStack } from './overlay-stack';

let modalSeq = 0;

@Component({
  selector: 'app-modal',
  standalone: true,
  imports: [IconComponent],
  template: `
    <div class="modal-backdrop" [class.is-drawer]="variant === 'drawer'" (click)="onBackdrop()">
      <div class="modal" #dialog [style.max-width.px]="width" tabindex="-1"
           role="dialog" aria-modal="true" [attr.aria-labelledby]="titleId"
           (click)="$event.stopPropagation()">
        <div class="modal-head">
          <h3 [id]="titleId">{{ title }}</h3>
          <button type="button" class="btn btn-icon btn-ghost" (click)="close.emit()" aria-label="Close dialog">
            <app-icon name="close" [size]="18" />
          </button>
        </div>
        <div class="modal-body">
          <ng-content></ng-content>
        </div>
        @if (hasFooter) {
          <div class="modal-foot">
            <ng-content select="[footer]"></ng-content>
          </div>
        }
      </div>
    </div>
  `,
  styles: [`
    .modal-backdrop { position: fixed; inset: 0; z-index: 100; display: flex; align-items: flex-start; justify-content: center;
      padding: 8vh 1rem 2rem; overflow-y: auto; overscroll-behavior: contain; background: var(--scrim); animation: fade var(--dur-2) var(--ease-out); }
    .modal { width: 100%; background: var(--surface-1); border: 1px solid var(--border); border-radius: var(--radius-lg);
      box-shadow: var(--shadow-pop), 0 0 60px -20px var(--glow); animation: rise var(--dur-3) var(--ease-out); }
    .modal:focus { outline: none; }
    .modal-head { display: flex; align-items: center; justify-content: space-between; padding: 1rem 1.2rem 1rem 1.4rem; border-bottom: 1px solid var(--border); }
    .modal-head h3 { font-size: 17px; }
    .modal-body { padding: 1.3rem 1.4rem; }
    .modal-foot { display: flex; justify-content: flex-end; gap: .6rem; padding: 1rem 1.4rem; border-top: 1px solid var(--border);
      background: var(--surface-2); border-radius: 0 0 var(--radius-lg) var(--radius-lg); }
    .is-drawer.modal-backdrop { justify-content: flex-end; align-items: stretch; padding: 0; overflow: hidden; }
    .is-drawer .modal { height: 100%; border-radius: 0; border-width: 0 0 0 1px; display: flex; flex-direction: column; animation: slide-in var(--dur-3) var(--ease-out); }
    .is-drawer .modal-body { flex: 1; overflow-y: auto; }
    .is-drawer .modal-foot { border-radius: 0; }
    @keyframes fade { from { opacity: 0; } }
    @keyframes rise { from { opacity: 0; transform: translateY(10px) scale(.98); } }
    @keyframes slide-in { from { transform: translateX(32px); opacity: 0; } }
  `],
})
export class ModalComponent implements AfterViewInit, OnDestroy {
  @Input() title = '';
  @Input() width = 520;
  /** `drawer` slides in from the right at full height; `dialog` is centred. */
  @Input() variant: 'dialog' | 'drawer' = 'dialog';
  @Input() hasFooter = true;
  @Input() closeOnBackdrop = true;
  @Output() close = new EventEmitter<void>();
  @ViewChild('dialog') dialog?: ElementRef<HTMLElement>;

  titleId = `modal-title-${++modalSeq}`;
  private overlays = inject(OverlayStack);
  private opener: HTMLElement | null = null;

  constructor() { this.overlays.push(this); }

  ngAfterViewInit() {
    this.opener = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    queueMicrotask(() => this.dialog?.nativeElement.focus());
  }

  /** Return focus to whatever opened the dialog. */
  ngOnDestroy() { this.overlays.remove(this); this.opener?.focus(); }

  onBackdrop() { if (this.closeOnBackdrop) this.close.emit(); }

  @HostListener('document:keydown.escape', ['$event'])
  onEscape(e: Event) { if (this.overlays.isTop(this, e)) this.close.emit(); }

  /** Keep Tab / Shift+Tab cycling inside the dialog. */
  @HostListener('document:keydown', ['$event'])
  trapTab(e: KeyboardEvent) {
    if (e.key !== 'Tab' || !this.overlays.isTop(this, e)) return;
    const root = this.dialog?.nativeElement;
    if (!root) return;
    const focusables = Array.from(root.querySelectorAll<HTMLElement>(
      'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])',
    )).filter((el) => el.offsetParent !== null);
    if (!focusables.length) { e.preventDefault(); root.focus(); return; }
    const first = focusables[0];
    const last = focusables[focusables.length - 1];
    const active = document.activeElement;
    if (e.shiftKey && (active === first || active === root || !root.contains(active))) {
      e.preventDefault(); last.focus();
    } else if (!e.shiftKey && (active === last || !root.contains(active))) {
      e.preventDefault(); first.focus();
    }
  }
}
