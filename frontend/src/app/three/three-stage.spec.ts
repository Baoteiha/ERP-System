import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ThreeStage } from './three-stage';

/** Test doubles standing in for the three module, so no WebGL is needed. */
function fakeThree() {
  const renderers: { render: ReturnType<typeof vi.fn>; dispose: ReturnType<typeof vi.fn>; forceContextLoss: ReturnType<typeof vi.fn>;
    setSize: ReturnType<typeof vi.fn>; setPixelRatio: ReturnType<typeof vi.fn>; domElement: HTMLCanvasElement }[] = [];
  const cameras: { aspect: number; updateProjectionMatrix: ReturnType<typeof vi.fn> }[] = [];
  class WebGLRenderer {
    domElement = document.createElement('canvas');
    render = vi.fn();
    dispose = vi.fn();
    forceContextLoss = vi.fn();
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
    constructor() { cameras.push(this); }
  }
  return { three: { WebGLRenderer, Scene, PerspectiveCamera } as never, renderers, cameras };
}

describe('ThreeStage', () => {
  let raf: ReturnType<typeof vi.fn>;
  let caf: ReturnType<typeof vi.fn>;
  let disconnects: ReturnType<typeof vi.fn>;
  let host: HTMLElement;
  let resizeCb: (() => void) | null;
  let ioCb: ((e: { isIntersecting: boolean }[]) => void) | null;

  beforeEach(() => {
    raf = vi.fn().mockReturnValue(7);
    caf = vi.fn();
    disconnects = vi.fn();
    vi.stubGlobal('requestAnimationFrame', raf);
    vi.stubGlobal('cancelAnimationFrame', caf);
    resizeCb = null;
    ioCb = null;
    vi.stubGlobal('ResizeObserver', class {
      constructor(cb: () => void) { resizeCb = cb; }
      observe = vi.fn();
      disconnect = disconnects;
    });
    vi.stubGlobal('IntersectionObserver', class {
      constructor(cb: (e: { isIntersecting: boolean }[]) => void) { ioCb = cb; }
      observe = vi.fn();
      disconnect = disconnects;
    });
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
    (raf.mock.calls[0][0] as (t: number) => void)(0);
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
    (raf.mock.calls[0][0] as (t: number) => void)(0);
    raf.mockClear();
    renderers[0].render.mockClear();
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
    const map = { isTexture: true, dispose: vi.fn() };
    const notTexture = { dispose: vi.fn() };
    Object.assign(material, { map, userData: notTexture });
    stage.scene.add({ geometry, material: [material, second] } as never);
    stage.invalidate();

    stage.destroy();

    expect(geometry.dispose).toHaveBeenCalledTimes(1);
    expect(material.dispose).toHaveBeenCalledTimes(1);
    expect(second.dispose).toHaveBeenCalledTimes(1);
    expect(map.dispose).toHaveBeenCalledTimes(1);
    expect(notTexture.dispose).not.toHaveBeenCalled();
    expect(renderers[0].dispose).toHaveBeenCalledTimes(1);
    expect(renderers[0].forceContextLoss).toHaveBeenCalledTimes(1);
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

  it('requests the first frame itself', () => {
    const { three } = fakeThree();
    const stage = new ThreeStage(three, host);
    expect(raf).toHaveBeenCalledTimes(1);
    stage.destroy();
  });

  it('resizes the renderer and camera, then requests a frame', () => {
    const { three, renderers, cameras } = fakeThree();
    const stage = new ThreeStage(three, host);
    Object.defineProperty(host, 'clientWidth', { value: 400, configurable: true });
    Object.defineProperty(host, 'clientHeight', { value: 200, configurable: true });
    (raf.mock.calls[0][0] as (t: number) => void)(0);
    raf.mockClear();
    resizeCb!();
    expect(renderers[0].setSize).toHaveBeenLastCalledWith(400, 200);
    expect(cameras[0].aspect).toBe(2);
    expect(cameras[0].updateProjectionMatrix).toHaveBeenCalled();
    expect(raf).toHaveBeenCalledTimes(1);
    stage.destroy();
  });

  it('pauses when scrolled off-screen and resumes when visible again', () => {
    const { three } = fakeThree();
    const stage = new ThreeStage(three, host);
    ioCb!([{ isIntersecting: false }]);
    expect(caf).toHaveBeenCalledWith(7);
    raf.mockClear();
    stage.invalidate();
    expect(raf).not.toHaveBeenCalled();
    ioCb!([{ isIntersecting: true }]);
    expect(raf).toHaveBeenCalledTimes(1);
    stage.destroy();
  });

  it('pauses when the tab is hidden and resumes when shown', () => {
    const { three } = fakeThree();
    const stage = new ThreeStage(three, host);
    const hidden = vi.spyOn(document, 'hidden', 'get').mockReturnValue(true);
    document.dispatchEvent(new Event('visibilitychange'));
    expect(caf).toHaveBeenCalledWith(7);
    raf.mockClear();
    stage.invalidate();
    expect(raf).not.toHaveBeenCalled();
    hidden.mockReturnValue(false);
    document.dispatchEvent(new Event('visibilitychange'));
    expect(raf).toHaveBeenCalledTimes(1);
    hidden.mockRestore();
    stage.destroy();
  });

  it('caps the pixel ratio at maxPixelRatio', () => {
    vi.stubGlobal('devicePixelRatio', 3);
    const a = fakeThree();
    new ThreeStage(a.three, host).destroy();
    expect(a.renderers[0].setPixelRatio).toHaveBeenCalledWith(2);
    const b = fakeThree();
    new ThreeStage(b.three, host, { maxPixelRatio: 1.5 }).destroy();
    expect(b.renderers[0].setPixelRatio).toHaveBeenCalledWith(1.5);
  });

  it('is safe to destroy twice', () => {
    const { three, renderers } = fakeThree();
    const stage = new ThreeStage(three, host);
    stage.destroy();
    expect(() => stage.destroy()).not.toThrow();
    expect(renderers[0].dispose).toHaveBeenCalledTimes(1);
  });
});
