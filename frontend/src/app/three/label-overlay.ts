import { DOCUMENT, ElementRef, computed, effect, inject, signal } from '@angular/core';

/** Where a label sits on the canvas: CSS pixels, plus a z-index rank that grows as its spot gets nearer the camera. */
export interface ScreenPoint { x: number; y: number; z: number; }

/**
 * Which label's tip is open over a 3D view. Hover or focus opens it; leaving closes it unless focus or the pointer
 * is still on it; Escape anywhere closes it while one is open, so a tip opened by hover alone can be dismissed too.
 * Build it in an injection context (a component field): the Escape listener lives as long as its owner.
 */
export class LabelTips<T> {
  readonly open = signal<T | null>(null);
  private readonly doc = inject(DOCUMENT);

  constructor(private readonly idOf: (item: T) => string) {
    const anyOpen = computed(() => this.open() !== null);
    effect((onCleanup) => {
      if (!anyOpen()) return;
      const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') this.open.set(null); };
      this.doc.addEventListener('keydown', onKey);
      onCleanup(() => this.doc.removeEventListener('keydown', onKey));
    });
  }

  isOpen(item: T): boolean {
    const open = this.open();
    return open !== null && this.idOf(open) === this.idOf(item);
  }

  show(item: T): void {
    this.open.set(item);
  }

  hide(): void {
    this.open.set(null);
  }

  /** The pointer left an anchor: close its tip unless the keyboard focus is still inside it. */
  leave(anchor: HTMLElement): void {
    if (!anchor.contains(this.doc.activeElement)) this.close(anchor);
  }

  /** Focus left an anchor: close its tip unless focus moved within it or the pointer is over it. */
  blur(anchor: HTMLElement, e: FocusEvent): void {
    const next = e.relatedTarget as Node | null;
    if ((next && anchor.contains(next)) || anchor.matches(':hover')) return;
    this.close(anchor);
  }

  /** Escape inside a tip: focus goes back to its label first (that refocus would reopen it), then the tip closes. */
  closeTo(label: HTMLElement): void {
    label.focus();
    this.open.set(null);
  }

  private close(anchor: HTMLElement): void {
    const open = this.open();
    if (open !== null && this.idOf(open) === anchor.dataset['id']) this.open.set(null);
  }
}

/** How a tip card fits over its label: its width, and how near the stage top a label must be for its tip to open below. */
export interface TipFit { width: number; flipBelow: number; }

/**
 * Pins overlay anchors (elements with a `data-id`) to their screen points, writing a style only when it changes:
 * `transform` on a move of more than half a pixel, `--z` when the depth rank changes. With `tips`, it also sets the
 * tip's sideways offset `--dx` (kept inside the stage) and the `.below` flip; while the scene is `moving` those wait,
 * except on the open anchor (`.on`), until a still frame or a `force` pass.
 */
export class LabelPlacer {
  private readonly last = new WeakMap<HTMLElement, ScreenPoint>();

  constructor(private readonly tip: TipFit) {}

  place(anchors: readonly ElementRef<HTMLElement>[], points: ReadonlyMap<string, ScreenPoint>, w: number,
    mode: { tips: boolean; moving: boolean; force: boolean }): void {
    for (const { nativeElement: a } of anchors) {
      const p = points.get(a.dataset['id'] ?? '');
      if (!p) continue;
      let last = this.last.get(a);
      const fresh = !last;
      if (!last) this.last.set(a, (last = { x: NaN, y: NaN, z: NaN }));
      const moved = !(Math.abs(p.x - last.x) <= 0.5 && Math.abs(p.y - last.y) <= 0.5);
      if (moved) {
        last.x = p.x;
        last.y = p.y;
        a.style.transform = `translate(${p.x.toFixed(1)}px, ${p.y.toFixed(1)}px)`;
      }
      if (p.z !== last.z) {
        last.z = p.z;
        a.style.setProperty('--z', String(p.z));
      }
      if (mode.tips && (fresh || mode.force || (moved && (!mode.moving || a.classList.contains('on'))))) {
        const { width, flipBelow } = this.tip;
        const left = Math.min(Math.max(p.x - width / 2, 8), Math.max(8, w - width - 8));
        a.style.setProperty('--dx', `${(left - p.x).toFixed(1)}px`);
        a.classList.toggle('below', p.y < flipBelow);
      }
      if (fresh) a.classList.add('placed');
    }
  }
}
