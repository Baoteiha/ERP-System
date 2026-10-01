import { Injectable } from '@angular/core';

/**
 * Open overlays, oldest first. Each one listens for Escape and Tab on the document,
 * so without this a confirm over a modal would close both on one keypress.
 */
@Injectable({ providedIn: 'root' })
export class OverlayStack {
  private readonly open: object[] = [];
  /** The top overlay as it was when each event first arrived. */
  private readonly topAtEvent = new WeakMap<Event, object | undefined>();

  push(overlay: object) { if (!this.open.includes(overlay)) this.open.push(overlay); }
  remove(overlay: object) {
    const i = this.open.indexOf(overlay);
    if (i >= 0) this.open.splice(i, 1);
  }
  /**
   * Whether `overlay` is the one this key event is for. Decided once per event: a handler
   * that closes the top overlay would otherwise promote the next one in time to handle
   * the very same keypress.
   */
  isTop(overlay: object, event: Event) {
    if (!this.topAtEvent.has(event)) this.topAtEvent.set(event, this.open[this.open.length - 1]);
    return this.topAtEvent.get(event) === overlay;
  }
}
