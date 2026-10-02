import { Mock, afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { LazyStage } from './lazy-stage';
import { FakeRenderer, stubStageGlobals, threeWithFakeRenderer } from './test-three';
import { ThreeStage } from './three-stage';

type Three = typeof import('three');
interface Scene { stage: ThreeStage; }

describe('LazyStage', () => {
  let renderers: FakeRenderer[];
  let load: () => Promise<Three>;
  let hooks: {
    build: Mock<(three: Three, stage: ThreeStage) => Scene>; started: Mock<(scene: Scene, stage: ThreeStage) => void>;
    failed: Mock<() => void>; stopped: Mock<() => void>;
  };
  let host: HTMLElement;
  const settle = async () => { for (let i = 0; i < 5; i++) await Promise.resolve(); };

  beforeEach(() => {
    stubStageGlobals();
    const three = threeWithFakeRenderer();
    renderers = three.renderers;
    load = three.load as unknown as () => Promise<Three>;
    hooks = {
      build: vi.fn((_three: Three, stage: ThreeStage) => ({ stage })),
      started: vi.fn<(scene: Scene, stage: ThreeStage) => void>(),
      failed: vi.fn<() => void>(),
      stopped: vi.fn<() => void>(),
    };
    host = document.createElement('div');
    document.body.append(host);
  });
  afterEach(() => {
    host.remove();
    vi.unstubAllGlobals();
  });

  it('mounts on a host once three has loaded: a canvas in the host, the scene built and started', async () => {
    const lazy = new LazyStage(() => load(), hooks);
    expect(lazy.follow(host)).toBe(false); // a new host: mounting, nothing to update yet
    expect(lazy.stage).toBeNull();
    await vi.waitFor(() => expect(hooks.started).toHaveBeenCalledTimes(1));
    expect(host.querySelector('canvas')).not.toBeNull();
    expect(lazy.host).toBe(host);
    expect(lazy.stage).toBeInstanceOf(ThreeStage);
    expect(hooks.build).toHaveBeenCalledWith(expect.objectContaining({ Scene: expect.any(Function) }), lazy.stage);
    expect(hooks.started).toHaveBeenCalledWith(lazy.scene, lazy.stage);
    expect(lazy.scene).toEqual({ stage: lazy.stage });
  });

  it('keeps the stage while the host stays, and destroys it when the host goes', async () => {
    const lazy = new LazyStage(() => load(), hooks);
    lazy.follow(host);
    await vi.waitFor(() => expect(hooks.started).toHaveBeenCalled());
    expect(lazy.follow(host)).toBe(true); // same host: the caller updates the scene in place
    expect(renderers.length).toBe(1);
    expect(lazy.follow(null)).toBe(false);
    expect(renderers[0].dispose).toHaveBeenCalled();
    expect(renderers[0].forceContextLoss).toHaveBeenCalled();
    expect(host.querySelector('canvas')).toBeNull();
    expect([lazy.stage, lazy.scene, lazy.host]).toEqual([null, null, null]);
    expect(hooks.stopped).toHaveBeenCalled();
  });

  it('moves to a new host, tearing down the old stage', async () => {
    const lazy = new LazyStage(() => load(), hooks);
    lazy.follow(host);
    await vi.waitFor(() => expect(hooks.started).toHaveBeenCalledTimes(1));
    const next = document.createElement('div');
    document.body.append(next);
    lazy.follow(next);
    await vi.waitFor(() => expect(hooks.started).toHaveBeenCalledTimes(2));
    expect(renderers[0].dispose).toHaveBeenCalled();
    expect(next.querySelector('canvas')).not.toBeNull();
    expect(lazy.host).toBe(next);
    next.remove();
  });

  it('builds nothing when the host goes before three has loaded', async () => {
    let resolve!: (three: Three) => void;
    const lazy = new LazyStage(() => new Promise<Three>((r) => (resolve = r)), hooks);
    lazy.follow(host);
    lazy.unmount();
    resolve(await load());
    await settle();
    expect(renderers.length).toBe(0);
    expect(hooks.build).not.toHaveBeenCalled();
    expect(hooks.failed).not.toHaveBeenCalled();
  });

  it('reports a failed load, but not one it has since given up on', async () => {
    let reject!: (e: Error) => void;
    const lazy = new LazyStage(() => new Promise<Three>((_, r) => (reject = r)), hooks);
    lazy.follow(host);
    lazy.unmount();
    reject(new Error('offline'));
    await settle();
    expect(hooks.failed).not.toHaveBeenCalled();

    const again = new LazyStage(() => Promise.reject(new Error('offline')), hooks);
    again.follow(host);
    await settle();
    expect(hooks.failed).toHaveBeenCalledTimes(1);
    expect(again.stage).toBeNull();
  });

  it('tears the stage down and reports a failure when the scene will not start', async () => {
    hooks.build.mockImplementation(() => { throw new Error('no context'); });
    const lazy = new LazyStage(() => load(), hooks);
    lazy.follow(host);
    await vi.waitFor(() => expect(hooks.failed).toHaveBeenCalledTimes(1));
    expect(renderers[0].dispose).toHaveBeenCalled();
    expect(host.querySelector('canvas')).toBeNull();
    expect(hooks.started).not.toHaveBeenCalled();
    expect(lazy.stage).toBeNull();
  });
});
