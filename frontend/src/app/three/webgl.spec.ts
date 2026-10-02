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

  it('falls back to webgl when webgl2 is unavailable', () => {
    vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockImplementation(((id: string) =>
      id === 'webgl' ? {} : null) as never);
    expect(hasWebGL()).toBe(true);
  });

  it('releases the probe context after a successful check', () => {
    const loseContext = vi.fn();
    const ctx = { getExtension: vi.fn().mockReturnValue({ loseContext }) };
    vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue(ctx as never);
    expect(hasWebGL()).toBe(true);
    expect(ctx.getExtension).toHaveBeenCalledWith('WEBGL_lose_context');
    expect(loseContext).toHaveBeenCalledTimes(1);
  });
});
