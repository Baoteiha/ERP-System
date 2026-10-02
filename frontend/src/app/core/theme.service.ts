import { Injectable, computed, effect, signal } from '@angular/core';
import { readPref, writePref } from './prefs';

export type ThemePref = 'system' | 'dark' | 'light';
const KEY = 'erpcafe.theme';
const ORDER: readonly ThemePref[] = ['system', 'dark', 'light'];
const THEME_COLOR = { dark: '#0B0D14', light: '#F6F7FB' } as const;

/** The colour theme: System follows the OS live; Dark and Light set data-theme on <html>. */
@Injectable({ providedIn: 'root' })
export class ThemeService {
  readonly pref = signal<ThemePref>(readPref(KEY, ORDER, 'system'));
  private readonly systemLight = signal(false);
  readonly effective = computed<'dark' | 'light'>(() => {
    const p = this.pref();
    if (p === 'system') return this.systemLight() ? 'light' : 'dark';
    return p;
  });

  constructor() {
    const mq = typeof matchMedia === 'function' ? matchMedia('(prefers-color-scheme: light)') : null;
    this.systemLight.set(!!mq?.matches);
    mq?.addEventListener('change', (e) => this.systemLight.set(e.matches));

    effect(() => {
      const root = document.documentElement;
      const p = this.pref();
      if (p === 'system') root.removeAttribute('data-theme');
      else root.setAttribute('data-theme', p);
      document.querySelector('meta[name="theme-color"]')?.setAttribute('content', THEME_COLOR[this.effective()]);
    });
  }

  set(p: ThemePref) { this.pref.set(p); writePref(KEY, p); }

  cycle() { this.set(ORDER[(ORDER.indexOf(this.pref()) + 1) % ORDER.length]); }
}
