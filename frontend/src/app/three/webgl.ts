import { InjectionToken } from '@angular/core';

let cached: boolean | null = null;

/** True when the browser can create a WebGL context (cached after the first check). */
export function hasWebGL(): boolean {
  if (cached !== null) return cached;
  try {
    const canvas = document.createElement('canvas');
    const ctx = (canvas.getContext('webgl2') ?? canvas.getContext('webgl')) as WebGLRenderingContext | null;
    cached = !!ctx;
    ctx?.getExtension?.('WEBGL_lose_context')?.loseContext();
  } catch {
    cached = false;
  }
  return cached;
}

/** Clears the cached answer so a test can probe again. */
export function resetWebGLCacheForTests(): void {
  cached = null;
}

/** WebGL support as an injectable check, so a spec can pretend the device has none. */
export const WEBGL_SUPPORTED = new InjectionToken<() => boolean>('WEBGL_SUPPORTED', {
  providedIn: 'root',
  factory: () => hasWebGL,
});

/** Loads `three` on demand; the dynamic import keeps it in a lazy chunk, and a spec can swap in its own loader. */
export const THREE_LOADER = new InjectionToken<() => Promise<typeof import('three')>>('THREE_LOADER', {
  providedIn: 'root',
  factory: () => () => import('three'),
});
