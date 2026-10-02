import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { stubLocalStorage } from './test-storage';
import { ThemeService } from './theme.service';

/** A matchMedia double whose (prefers-color-scheme: light) answer the test can flip. */
function fakeMatchMedia(initialLight: boolean) {
  let light = initialLight;
  const listeners: ((e: { matches: boolean }) => void)[] = [];
  const mql = {
    get matches() { return light; },
    addEventListener: (_: string, l: (e: { matches: boolean }) => void) => listeners.push(l),
    removeEventListener: () => undefined,
  };
  vi.stubGlobal('matchMedia', () => mql);
  return { flip(to: boolean) { light = to; listeners.forEach((l) => l({ matches: to })); } };
}

describe('ThemeService', () => {
  const html = () => document.documentElement;
  const service = () => {
    TestBed.configureTestingModule({ providers: [provideZonelessChangeDetection()] });
    const s = TestBed.inject(ThemeService);
    TestBed.tick();
    return s;
  };

  beforeEach(() => {
    stubLocalStorage();
    html().removeAttribute('data-theme');
    const meta = document.createElement('meta');
    meta.name = 'theme-color';
    meta.content = '';
    document.head.appendChild(meta);
  });
  afterEach(() => {
    document.head.querySelectorAll('meta[name="theme-color"]').forEach((m) => m.remove());
    html().removeAttribute('data-theme');
    vi.unstubAllGlobals();
  });

  it('defaults to system and leaves the OS in charge', () => {
    fakeMatchMedia(false);
    const s = service();
    expect(s.pref()).toBe('system');
    expect(s.effective()).toBe('dark');
    expect(html().hasAttribute('data-theme')).toBe(false);
  });

  it('follows the OS live while the setting is system', () => {
    const os = fakeMatchMedia(false);
    const s = service();
    os.flip(true);
    expect(s.effective()).toBe('light');
  });

  it('an explicit choice sets data-theme and is remembered', () => {
    fakeMatchMedia(false);
    const s = service();
    s.set('light');
    TestBed.tick();
    expect(html().getAttribute('data-theme')).toBe('light');
    expect(localStorage.getItem('erpcafe.theme')).toBe('light');
  });

  it('cycles system → dark → light → system', () => {
    fakeMatchMedia(false);
    const s = service();
    s.cycle(); expect(s.pref()).toBe('dark');
    s.cycle(); expect(s.pref()).toBe('light');
    s.cycle(); expect(s.pref()).toBe('system');
  });

  it('falls back to defaults when storage throws, and still applies a choice', () => {
    fakeMatchMedia(false);
    vi.stubGlobal('localStorage', {
      getItem: () => { throw new Error('blocked'); },
      setItem: () => { throw new Error('blocked'); },
      removeItem: () => undefined,
    });
    const s = service();
    expect(s.pref()).toBe('system');
    s.set('dark');
    TestBed.tick();
    expect(html().getAttribute('data-theme')).toBe('dark');
  });

  it('sets the theme-color meta after set(\'light\')', () => {
    fakeMatchMedia(false);
    const s = service();
    s.set('light');
    TestBed.tick();
    expect(document.querySelector('meta[name="theme-color"]')?.getAttribute('content')).toBe('#F6F7FB');
  });

  it('sets the theme-color meta after set(\'dark\')', () => {
    fakeMatchMedia(false);
    const s = service();
    s.set('dark');
    TestBed.tick();
    expect(document.querySelector('meta[name="theme-color"]')?.getAttribute('content')).toBe('#0B0D14');
  });

  it('updates theme-color meta when OS flips while setting is system', () => {
    const os = fakeMatchMedia(false);
    const s = service();
    expect(s.pref()).toBe('system');
    os.flip(true);
    TestBed.tick();
    expect(document.querySelector('meta[name="theme-color"]')?.getAttribute('content')).toBe('#F6F7FB');
  });

  it('ignores OS flip when an explicit theme is set', () => {
    const os = fakeMatchMedia(false);
    const s = service();
    s.set('dark');
    TestBed.tick();
    os.flip(true);
    expect(s.effective()).toBe('dark');
  });
});
