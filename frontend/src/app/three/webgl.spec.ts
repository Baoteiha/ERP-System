import { afterEach, describe, expect, it, vi } from 'vitest';
import { hasWebGL, resetWebGLCacheForTests } from './webgl';

describe('hasWebGL', () => {
  afterEach(() => {
    vi.restoreAllMocks();
    resetWebGLCacheForTests();
  });

  it('is false when no WebGL context can be created', () => {
    vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue(null);
    expect(hasWebGL()).toBe(false);
  });

  it('is false when creating a context throws', () => {
    vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockImplementation(() => { throw new Error('blocked'); });
    expect(hasWebGL()).toBe(false);
  });

  it('is true when a context is returned, and the answer is cached', () => {
    const spy = vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue({} as never);
    expect(hasWebGL()).toBe(true);
    expect(hasWebGL()).toBe(true);
    expect(spy).toHaveBeenCalledTimes(1);
  });
});
