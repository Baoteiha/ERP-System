import { Injectable, effect, signal } from '@angular/core';
import { readPref, writePref } from './prefs';

export type Density = 'comfortable' | 'compact';
const KEY = 'erpcafe.density';

/** Table and list density, applied as data-density on <html>. */
@Injectable({ providedIn: 'root' })
export class DensityService {
  readonly pref = signal<Density>(readPref(KEY, ['comfortable', 'compact'] as const, 'comfortable'));

  constructor() {
    effect(() => document.documentElement.setAttribute('data-density', this.pref()));
  }

  set(d: Density) { this.pref.set(d); writePref(KEY, d); }

  toggle() { this.set(this.pref() === 'compact' ? 'comfortable' : 'compact'); }
}
