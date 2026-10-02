import { vi } from 'vitest';

/** What a spec can check on the stand-in renderer. */
export interface FakeRenderer {
  domElement: HTMLCanvasElement;
  render: ReturnType<typeof vi.fn>;
  dispose: ReturnType<typeof vi.fn>;
  forceContextLoss: ReturnType<typeof vi.fn>;
  setSize: ReturnType<typeof vi.fn>;
  setPixelRatio: ReturnType<typeof vi.fn>;
}

/**
 * The real `three` module with only WebGLRenderer swapped for a stand-in, so a spec can build and
 * inspect a real scene without a GPU. `renderers` collects every renderer the module has made.
 */
export function threeWithFakeRenderer() {
  const renderers: FakeRenderer[] = [];
  /** Records calls instead of drawing. */
  class WebGLRenderer implements FakeRenderer {
    domElement = document.createElement('canvas');
    render = vi.fn();
    dispose = vi.fn();
    forceContextLoss = vi.fn();
    setSize = vi.fn();
    setPixelRatio = vi.fn();
    constructor() { renderers.push(this); }
  }
  const load = async () => ({ ...(await import('three')), WebGLRenderer });
  return { load, renderers };
}

/**
 * Stubs what ThreeStage needs from the browser: requestAnimationFrame (queued until `flush`),
 * ResizeObserver and IntersectionObserver. Undo with `vi.unstubAllGlobals()`.
 */
export function stubStageGlobals() {
  const frames = new Map<number, FrameRequestCallback>();
  let next = 0;
  vi.stubGlobal('requestAnimationFrame', (cb: FrameRequestCallback) => { frames.set(++next, cb); return next; });
  vi.stubGlobal('cancelAnimationFrame', (id: number) => frames.delete(id));
  vi.stubGlobal('ResizeObserver', class { observe = vi.fn(); disconnect = vi.fn(); });
  vi.stubGlobal('IntersectionObserver', class { observe = vi.fn(); disconnect = vi.fn(); });
  return {
    /** Runs the frames queued so far, as if the browser painted at time `t` (ms). */
    flush(t: number) {
      const due = [...frames.values()];
      frames.clear();
      due.forEach((cb) => cb(t));
    },
  };
}
