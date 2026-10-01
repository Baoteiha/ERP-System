import { Injectable, inject, signal } from '@angular/core';
import { OverlayStack } from '../shared/overlay-stack';

export interface ConfirmOptions {
  title: string;
  message?: string;
  confirmText?: string;
  cancelText?: string;
  danger?: boolean;
}

interface PendingConfirm extends ConfirmOptions { resolve: (ok: boolean) => void; }

@Injectable({ providedIn: 'root' })
export class ConfirmService {
  private overlays = inject(OverlayStack);
  readonly pending = signal<PendingConfirm | null>(null);

  ask(opts: ConfirmOptions): Promise<boolean> {
    return new Promise((resolve) => {
      this.overlays.push(this);
      this.pending.set({ ...opts, resolve });
    });
  }

  answer(ok: boolean) {
    const p = this.pending();
    if (p) { this.overlays.remove(this); p.resolve(ok); this.pending.set(null); }
  }
}
