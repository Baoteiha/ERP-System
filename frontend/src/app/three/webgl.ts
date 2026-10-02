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
