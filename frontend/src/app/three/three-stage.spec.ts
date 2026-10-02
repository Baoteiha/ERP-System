import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ThreeStage } from './three-stage';

/** Test doubles standing in for the three module, so no WebGL is needed. */
function fakeThree() {
  const renderers: { render: ReturnType<typeof vi.fn>; dispose: ReturnType<typeof vi.fn>; domElement: HTMLCanvasElement }[] = [];
  class WebGLRenderer {
    domElement = document.createElement('canvas');
    render = vi.fn();
    dispose = vi.fn();
    setPixelRatio = vi.fn();
    setSize = vi.fn();
    constructor() { renderers.push(this); }
  }
  class Scene {
    children: unknown[] = [];
    add(o: unknown) { this.children.push(o); }
    traverse(fn: (o: unknown) => void) { fn(this); this.children.forEach(c => fn(c)); }
  }
  class PerspectiveCamera {
    aspect = 1;
    updateProjectionMatrix = vi.fn();
  }
  return { three: { WebGLRenderer, Scene, PerspectiveCamera } as never, renderers };
}

describe('ThreeStage', () => {
  let raf: ReturnType<typeof vi.fn>;
  let caf: ReturnType<typeof vi.fn>;
  let disconnects: ReturnType<typeof vi.fn>;
  let host: HTMLElement;

  beforeEach(() => {
    raf = vi.fn().mockReturnValue(7);
    caf = vi.fn();
    disconnects = vi.fn();
    vi.stubGlobal('requestAnimationFrame', raf);
    vi.stubGlobal('cancelAnimationFrame', caf);
    vi.stubGlobal('ResizeObserver', class { observe = vi.fn(); disconnect = disconnects; });
    vi.stubGlobal('IntersectionObserver', class { observe = vi.fn(); disconnect = disconnects; });
    host = document.createElement('div');
    document.body.appendChild(host);
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    host.remove();
  });

  it('appends the canvas to the host', () => {
    const { three, renderers } = fakeThree();
    const stage = new ThreeStage(three, host);
    expect(host.contains(renderers[0].domElement)).toBe(true);
    stage.destroy();
  });

  it('schedules a single frame for repeated invalidate calls', () => {
    const { three } = fakeThree();
    const stage = new ThreeStage(three, host);
    raf.mockClear();
    stage.invalidate();
    stage.invalidate();
    expect(raf).toHaveBeenCalledTimes(1);
    stage.destroy();
  });

  it('renders on a frame and keeps looping only while the animator returns true', () => {
    const { three, renderers } = fakeThree();
    const stage = new ThreeStage(three, host);
    let more = true;
    raf.mockClear();
    stage.setAnimator(() => more);
    const frame = raf.mock.calls[0][0] as (t: number) => void;
    frame(16);
    expect(renderers[0].render).toHaveBeenCalledTimes(1);
    expect(raf).toHaveBeenCalledTimes(2);
    more = false;
    (raf.mock.calls[1][0] as (t: number) => void)(32);
    expect(renderers[0].render).toHaveBeenCalledTimes(2);
    expect(raf).toHaveBeenCalledTimes(2);
    stage.destroy();
  });

  it('destroy disposes geometries, materials, the renderer and removes the canvas', () => {
    const { three, renderers } = fakeThree();
    const stage = new ThreeStage(three, host);
    const geometry = { dispose: vi.fn() };
    const material = { dispose: vi.fn() };
    const second = { dispose: vi.fn() };
    stage.scene.add({ geometry, material: [material, second] } as never);
    stage.invalidate();

    stage.destroy();

    expect(geometry.dispose).toHaveBeenCalledTimes(1);
    expect(material.dispose).toHaveBeenCalledTimes(1);
    expect(second.dispose).toHaveBeenCalledTimes(1);
    expect(renderers[0].dispose).toHaveBeenCalledTimes(1);
    expect(host.contains(renderers[0].domElement)).toBe(false);
    expect(caf).toHaveBeenCalledWith(7);
    expect(disconnects).toHaveBeenCalledTimes(2);
  });

  it('does not schedule frames after destroy', () => {
    const { three } = fakeThree();
    const stage = new ThreeStage(three, host);
    stage.destroy();
    raf.mockClear();
    stage.invalidate();
    expect(raf).not.toHaveBeenCalled();
  });
});
