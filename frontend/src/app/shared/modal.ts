import { AfterViewInit, Component, ElementRef, EventEmitter, HostListener, Input, OnDestroy, Output, ViewChild } from '@angular/core';
import { IconComponent } from './icon';

let modalSeq = 0;

@Component({
  selector: 'app-modal',
  standalone: true,
  imports: [IconComponent],
  template: `
    <div class="modal-backdrop" (click)="onBackdrop()">
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
    .modal-backdrop {
      position: fixed; inset: 0; z-index: 100; display: flex; align-items: flex-start;
      justify-content: center; padding: 7vh 1rem 2rem; overflow-y: auto;
      overscroll-behavior: contain;
      background: var(--scrim); backdrop-filter: var(--blur); -webkit-backdrop-filter: var(--blur);
      animation: fade var(--dur-2) var(--ease-out);
    }
    .modal {
      width: 100%; max-width: 520px; background: var(--surface);
      border: 1px solid var(--hairline); border-radius: var(--radius-lg);
      box-shadow: var(--shadow-pop); animation: sheet var(--dur-3) var(--ease-spring);
    }
    .modal:focus { outline: none; }
    .modal-head { display: flex; align-items: center; justify-content: space-between;
      padding: 1.15rem 1.35rem 1.15rem 1.5rem; border-bottom: 1px solid var(--hairline); }
    .modal-head h3 { font-size: 18px; letter-spacing: -.02em; }
    .modal-body { padding: 1.4rem 1.5rem; }
    .modal-foot { display: flex; justify-content: flex-end; gap: .6rem;
      padding: 1.1rem 1.5rem; border-top: 1px solid var(--hairline); background: var(--surface-2);
      border-radius: 0 0 var(--radius-lg) var(--radius-lg); }
    @keyframes fade { from { opacity: 0; } }
    @keyframes sheet { from { opacity: 0; transform: translateY(12px) scale(.97); } }
  `],
})
export class ModalComponent implements AfterViewInit, OnDestroy {
  @Input() title = '';
  @Input() width = 520;
  @Input() hasFooter = true;
  @Input() closeOnBackdrop = true;
  @Output() close = new EventEmitter<void>();
  @ViewChild('dialog') dialog?: ElementRef<HTMLElement>;

  titleId = `modal-title-${++modalSeq}`;
  private opener: HTMLElement | null = null;

  ngAfterViewInit() {
    this.opener = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    queueMicrotask(() => this.dialog?.nativeElement.focus());
  }

  /** Return focus to whatever opened the dialog. */
  ngOnDestroy() { this.opener?.focus(); }

  onBackdrop() { if (this.closeOnBackdrop) this.close.emit(); }

  @HostListener('document:keydown.escape')
  onEscape() { this.close.emit(); }

  /** Keep Tab / Shift+Tab cycling inside the dialog. */
  @HostListener('document:keydown', ['$event'])
  trapTab(e: KeyboardEvent) {
    if (e.key !== 'Tab') return;
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
