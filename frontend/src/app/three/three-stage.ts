import type { PerspectiveCamera, Scene, WebGLRenderer } from 'three';

type Three = typeof import('three');
interface Disposable { dispose?: () => void }

/** Owns one renderer + scene + camera for a host element: resize, render-on-demand, pause off-screen, dispose. */
export class ThreeStage {
  readonly scene: Scene;
  readonly camera: PerspectiveCamera;
  private readonly renderer: WebGLRenderer;
  private readonly resizeObserver: ResizeObserver;
  private readonly intersectionObserver: IntersectionObserver;
  private visible = true;
  private hidden = typeof document !== 'undefined' && document.hidden;
  private frame: number | null = null;
  private animator: ((dtMs: number) => boolean) | null = null;
  private lastTime: number | null = null;
  private destroyed = false;
  private readonly onVisibility = () => {
    this.hidden = document.hidden;
    if (this.hidden) this.cancel();
    else this.invalidate();
  };

  constructor(three: Three, private readonly host: HTMLElement, opts?: { maxPixelRatio?: number }) {
    this.renderer = new three.WebGLRenderer({ antialias: true, alpha: true });
    this.renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, opts?.maxPixelRatio ?? 2));
    host.appendChild(this.renderer.domElement);
    this.scene = new three.Scene();
    const w = host.clientWidth || 1;
    const h = host.clientHeight || 1;
    this.camera = new three.PerspectiveCamera(40, w / h, 0.1, 200);
    this.renderer.setSize(w, h);

    this.resizeObserver = new ResizeObserver(() => this.resize());
    this.resizeObserver.observe(host);
    this.intersectionObserver = new IntersectionObserver(entries => {
      this.visible = entries[entries.length - 1]?.isIntersecting ?? true;
      if (this.visible) this.invalidate();
      else this.cancel();
    });
    this.intersectionObserver.observe(host);
    document.addEventListener('visibilitychange', this.onVisibility);
  }

  /** Ask for one more frame (render-on-demand). */
  invalidate(): void {
    if (this.destroyed || this.frame !== null || !this.visible || this.hidden) return;
    this.frame = requestAnimationFrame(t => this.tick(t));
  }

  /** Called every frame while it returns true, e.g. an orbit; the stage stops looping when it returns false. */
  setAnimator(fn: ((dtMs: number) => boolean) | null): void {
    this.animator = fn;
    this.lastTime = null;
    this.invalidate();
  }

  destroy(): void {
    if (this.destroyed) return;
    this.destroyed = true;
    this.resizeObserver.disconnect();
    this.intersectionObserver.disconnect();
    document.removeEventListener('visibilitychange', this.onVisibility);
    this.cancel();
    this.animator = null;
    this.scene.traverse(obj => {
      const o = obj as { geometry?: Disposable; material?: Disposable | Disposable[] };
      o.geometry?.dispose?.();
      const materials = Array.isArray(o.material) ? o.material : o.material ? [o.material] : [];
      materials.forEach(m => m.dispose?.());
    });
    this.renderer.dispose();
    this.renderer.domElement.remove();
  }

  private resize(): void {
    const w = this.host.clientWidth || 1;
    const h = this.host.clientHeight || 1;
    this.renderer.setSize(w, h);
    this.camera.aspect = w / h;
    this.camera.updateProjectionMatrix();
    this.invalidate();
  }

  private cancel(): void {
    if (this.frame !== null) cancelAnimationFrame(this.frame);
    this.frame = null;
    this.lastTime = null;
  }

  private tick(time: number): void {
    this.frame = null;
    if (this.destroyed) return;
    const dt = this.lastTime === null ? 0 : time - this.lastTime;
    this.lastTime = time;
    const more = this.animator ? this.animator(dt) : false;
    if (more) this.invalidate();
    else this.lastTime = null;
    this.renderer.render(this.scene, this.camera);
  }
}
