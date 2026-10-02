import { ThreeStage } from './three-stage';

type Three = typeof import('three');

/** What a view does at each step of its stage's life. */
export interface LazyStageHooks<S> {
  /** Builds the scene on a fresh stage; throwing counts as a failure to start. */
  build(three: Three, stage: ThreeStage): S;
  /** The scene is up: feed it the data, start the animator, drop the loading state. */
  started(scene: S, stage: ThreeStage): void;
  /** three did not load, or the stage or scene would not start: show the fallback. */
  failed(): void;
  /** The stage was torn down (when the host goes, before a remount, on a failure). */
  stopped?(): void;
}

/**
 * Loads three on demand and keeps one ThreeStage (plus its scene) on a host element: it mounts when a host appears,
 * tears down when the host goes or changes, and ignores a load that resolves after the host has moved on.
 */
export class LazyStage<S> {
  private current: { host: HTMLElement; stage: ThreeStage | null; scene: S | null } | null = null;
  /** Bumped on every mount and unmount, so a three import that resolves late knows it is stale. */
  private generation = 0;

  constructor(private readonly loadThree: () => Promise<Three>, private readonly hooks: LazyStageHooks<S>) {}

  get host(): HTMLElement | null { return this.current?.host ?? null; }
  get stage(): ThreeStage | null { return this.current?.stage ?? null; }
  get scene(): S | null { return this.current?.scene ?? null; }

  /**
   * Follows the host element as it comes and goes. Returns true when the host is the one already mounted, so the
   * caller can update its scene in place; false when it (re)mounted or tore down.
   */
  follow(host: HTMLElement | null): boolean {
    if (host === this.host) return true;
    this.unmount();
    if (host) void this.mount(host);
    return false;
  }

  unmount(): void {
    this.generation++;
    const was = this.current;
    this.current = null;
    was?.stage?.destroy();
    if (was) this.hooks.stopped?.();
  }

  private async mount(host: HTMLElement): Promise<void> {
    const run = ++this.generation;
    const mounted: { host: HTMLElement; stage: ThreeStage | null; scene: S | null } = { host, stage: null, scene: null };
    this.current = mounted;
    let three: Three;
    try {
      three = await this.loadThree();
    } catch {
      if (run === this.generation) this.fail();
      return;
    }
    if (run !== this.generation) return; // the page or the view changed while three was loading
    try {
      mounted.stage = new ThreeStage(three, host);
      mounted.scene = this.hooks.build(three, mounted.stage);
    } catch {
      this.fail();
      return;
    }
    this.hooks.started(mounted.scene, mounted.stage);
  }

  private fail(): void {
    this.unmount();
    this.hooks.failed();
  }
}
