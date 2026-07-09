import { Component, EventEmitter, Input, Output } from '@angular/core';

@Component({
  selector: 'app-modal',
  standalone: true,
  template: `
    <div class="modal-backdrop" (click)="onBackdrop()">
      <div class="modal" [style.max-width.px]="width" (click)="$event.stopPropagation()">
        <div class="modal-head">
          <h3>{{ title }}</h3>
          <button type="button" class="btn btn-icon btn-ghost" (click)="close.emit()" aria-label="Close">✕</button>
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
      justify-content: center; padding: 6vh 1rem 2rem; overflow-y: auto;
      background: rgba(15, 20, 28, .55); backdrop-filter: blur(2px);
      animation: fade .12s ease;
    }
    .modal {
      width: 100%; max-width: 520px; background: var(--surface);
      border: 1px solid var(--border); border-radius: var(--radius-lg);
      box-shadow: var(--shadow-lg); animation: pop .14s ease;
    }
    .modal-head { display: flex; align-items: center; justify-content: space-between;
      padding: 1.1rem 1.4rem; border-bottom: 1px solid var(--border); }
    .modal-head h3 { font-size: 16px; }
    .modal-body { padding: 1.3rem 1.4rem; }
    .modal-foot { display: flex; justify-content: flex-end; gap: .6rem;
      padding: 1rem 1.4rem; border-top: 1px solid var(--border); background: var(--surface-2);
      border-radius: 0 0 var(--radius-lg) var(--radius-lg); }
    @keyframes fade { from { opacity: 0; } }
    @keyframes pop { from { opacity: 0; transform: translateY(-8px) scale(.98); } }
  `],
})
export class ModalComponent {
  @Input() title = '';
  @Input() width = 520;
  @Input() hasFooter = true;
  @Input() closeOnBackdrop = true;
  @Output() close = new EventEmitter<void>();

  onBackdrop() { if (this.closeOnBackdrop) this.close.emit(); }
}
