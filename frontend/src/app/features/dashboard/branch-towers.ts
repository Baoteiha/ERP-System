import { Component, ElementRef, OnDestroy, afterRenderEffect, computed, effect, inject, signal, untracked, viewChild, viewChildren } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { Subject, catchError, of, switchMap } from 'rxjs';
import type {
  BoxGeometry, BufferAttribute, Color, Fog, GridHelper, Group, Material, MeshBasicMaterial, MeshStandardMaterial,
  PlaneGeometry, RingGeometry, Texture, Vector3,
} from 'three';
import { ReportingService } from '../../core/api.services';
import { BranchPerformance } from '../../core/models';
import { ThemeService } from '../../core/theme.service';
import { MoneyPipe, formatMoney } from '../../core/util';
import { IconComponent } from '../../shared/icon';
import { ThreeStage } from '../../three/three-stage';
import { TowerModel, TowerSpec, towerModel } from '../../three/tower-model';
import { THREE_LOADER, WEBGL_SUPPORTED } from '../../three/webgl';
import { reportPeriod } from '../reports/reports';

type Three = typeof import('three');
type Period = 1 | 7 | 30;
type Mode = '3d' | 'table' | 'loading' | 'error' | 'empty';
/** A tower top on the canvas: CSS pixels, plus a z-index rank that grows as the tower gets nearer the camera. */
interface Point { x: number; y: number; z: number; }

const ORBIT_RAD_PER_MS = 0.00012;
const DRAG_RAD_PER_PX = 0.01;
/** Width of the .tower-tip card; the tip is shifted sideways to stay inside the stage. */
const TIP_W = 220;
/** A label closer than this to the top of the stage opens its tip below instead of above. */
const TIP_FLIP_Y = 176;
/** The camera looks at this height, so the tallest tower (6) and its label stay in frame. */
const TARGET_Y = 2;
/** Camera offset from the target; at scale 1 the camera sits at (10, 9, 12). */
const VIEW: readonly [number, number, number] = [10, 7, 12];

const clamp = (v: number, lo: number, hi: number) => Math.min(Math.max(v, lo), Math.max(lo, hi));

/** A soft round alpha falloff, white so the material colour shows through: the glow pool under each tower. */
function poolTexture(three: Three): Texture {
  const n = 64;
  const data = new Uint8Array(n * n * 4);
  for (let y = 0; y < n; y++) {
    for (let x = 0; x < n; x++) {
      const d = Math.hypot(x + 0.5 - n / 2, y + 0.5 - n / 2) / (n / 2);
      const i = (y * n + x) * 4;
      data[i] = data[i + 1] = data[i + 2] = 255;
      data[i + 3] = Math.round(255 * Math.max(0, 1 - d) ** 1.6);
    }
  }
  const tex = new three.DataTexture(data, n, n);
  tex.magFilter = three.LinearFilter;
  tex.minFilter = three.LinearFilter;
  tex.needsUpdate = true;
  return tex;
}

/** The towers diorama on a ThreeStage: grid floor, one box per branch, glow pools and loss rings, coloured from the CSS tokens. */
class TowerScene {
  /** Turntable holding the floor and the towers; the orbit and the drag rotate it around Y. */
  readonly world: Group;
  private readonly towers: Group;
  private readonly grid: GridHelper;
  private readonly fog: Fog;
  private readonly box: BoxGeometry;
  private readonly ring: RingGeometry;
  private readonly pool: PlaneGeometry;
  private readonly mat: {
    tower: MeshStandardMaterial; top: MeshStandardMaterial; slab: MeshStandardMaterial;
    ring: MeshBasicMaterial; glow: MeshBasicMaterial; glowTop: MeshBasicMaterial;
  };
  private readonly point: Vector3;
  /** One reusable screen point per tower, so projecting every frame allocates nothing. */
  private slots: { t: TowerSpec; p: Point }[] = [];
  private readonly points = new Map<string, Point>();
  /** How far the outermost tower reaches from the turntable's centre, base included. */
  private radius = 0;
  private fitted = { aspect: 0, radius: -1 };

  constructor(private readonly three: Three, private readonly stage: ThreeStage) {
    const T = three;
    const { scene } = stage;
    this.world = new T.Group();
    this.towers = new T.Group();
    this.grid = new T.GridHelper(24, 24);
    this.world.add(this.grid, this.towers);
    const ambient = new T.AmbientLight();
    ambient.intensity = 0.6;
    const sun = new T.DirectionalLight();
    sun.intensity = 1.0;
    sun.position.set(3, 10, 6); // top brightest, then the +Z face, then the +X face
    scene.add(this.world, ambient, sun);
    // the floor fades into the panel colour, so the grid has no hard edge
    scene.fog = this.fog = new T.Fog(new T.Color(), 18, 34);
    this.fit();

    this.box = new T.BoxGeometry(1, 1, 1);
    this.ring = new T.RingGeometry(0.75, 0.9, 32);
    this.pool = new T.PlaneGeometry(2.8, 2.8);
    const map = poolTexture(T);
    const lit = { roughness: 0.35, metalness: 0.2 };
    this.mat = {
      tower: new T.MeshStandardMaterial({ ...lit, emissiveIntensity: 0.45 }),
      top: new T.MeshStandardMaterial({ ...lit, emissiveIntensity: 0.9 }),
      // translucent, so a zero-sales slab reads as dim on a dark panel and as pale on a light one
      slab: new T.MeshStandardMaterial({ ...lit, emissiveIntensity: 0, transparent: true, opacity: 0.55 }),
      ring: new T.MeshBasicMaterial({ side: T.DoubleSide }),
      glow: new T.MeshBasicMaterial({ map, transparent: true, opacity: 0.55, depthWrite: false }),
      glowTop: new T.MeshBasicMaterial({ map, transparent: true, opacity: 0.95, depthWrite: false }),
    };
    this.point = new T.Vector3();
    this.recolour();
  }

  /** Re-reads the colour tokens (after a theme switch) and repaints. */
  recolour(): void {
    const css = getComputedStyle(document.documentElement);
    const tint = (target: Color, token: string) => {
      const value = css.getPropertyValue(token).trim();
      if (value) target.setStyle(value);
    };
    const { tower, top, slab, ring, glow, glowTop } = this.mat;
    for (const c of [tower.color, tower.emissive, top.color, top.emissive, glow.color, glowTop.color]) tint(c, '--accent');
    tint(slab.color, '--text-muted');
    tint(ring.color, '--red');
    tint(this.fog.color, '--surface-1');
    const line = new this.three.Color();
    tint(line, '--border');
    const colors = this.grid.geometry.getAttribute('color') as BufferAttribute;
    for (let i = 0; i < colors.count; i++) colors.setXYZ(i, line.r, line.g, line.b);
    colors.needsUpdate = true;
    this.stage.invalidate();
  }

  /** Replaces the towers; geometries and materials are shared, so only the meshes change. */
  setTowers(list: readonly TowerSpec[]): void {
    const T = this.three;
    this.slots = list.map((t) => ({ t, p: { x: 0, y: 0, z: 0 } }));
    this.points.clear();
    for (const { t, p } of this.slots) this.points.set(t.branchId, p);
    this.radius = Math.max(0, ...list.map((t) => Math.hypot(t.x, t.z))) + 0.8;
    this.towers.clear();
    for (const t of list) {
      const body = new T.Mesh(this.box, t.slab ? this.mat.slab : t.top ? this.mat.top : this.mat.tower);
      body.scale.set(1, t.height, 1);
      body.position.set(t.x, t.height / 2, t.z);
      this.towers.add(body);
      if (!t.slab) this.towers.add(this.flat(this.pool, t.top ? this.mat.glowTop : this.mat.glow, t, 0.004, t.top ? 1.35 : 1));
      if (t.loss) this.towers.add(this.flat(this.ring, this.mat.ring, t, 0.012, 1));
    }
    this.stage.invalidate();
  }

  /** Backs the camera off when the towers would not fit the canvas width (narrow screens, many branches). */
  fit(): void {
    const camera = this.stage.camera;
    if (camera.aspect === this.fitted.aspect && this.radius === this.fitted.radius) return;
    this.fitted = { aspect: camera.aspect, radius: this.radius };
    const halfWidth = Math.tan((camera.fov / 2) * (Math.PI / 180)) * Math.min(camera.aspect, 1.4);
    const scale = Math.max(1, (this.radius * 1.2) / halfWidth / Math.hypot(...VIEW));
    camera.position.set(VIEW[0] * scale, TARGET_Y + VIEW[1] * scale, VIEW[2] * scale);
    camera.lookAt(0, TARGET_Y, 0);
    camera.updateMatrixWorld();
  }

  /** Where each tower's top lands on a w × h canvas, by branch id. The map and its points are reused every call. */
  screenPoints(w: number, h: number): ReadonlyMap<string, Point> {
    this.world.updateMatrixWorld();
    const camera = this.stage.camera;
    for (const { t, p } of this.slots) {
      const v = this.point.set(t.x, t.height, t.z).applyMatrix4(this.towers.matrixWorld);
      p.z = Math.max(0, 4000 - Math.round(v.distanceTo(camera.position) * 100)); // 1/100 unit steps: rows 2.2 apart never tie
      v.project(camera);
      p.x = ((v.x + 1) / 2) * w;
      p.y = ((1 - v.y) / 2) * h;
    }
    return this.points;
  }

  /** A flat disc (glow pool or loss ring) lying on the floor under a tower. */
  private flat(geometry: RingGeometry | PlaneGeometry, material: Material, t: TowerSpec, y: number, scale: number) {
    const m = new this.three.Mesh(geometry, material);
    m.rotation.x = -Math.PI / 2;
    m.position.set(t.x, y, t.z);
    m.scale.setScalar(scale);
    return m;
  }
}

/** Dashboard panel: net sales per branch as 3D towers (with a table fallback), for today, 7 or 30 days. */
@Component({
  selector: 'app-branch-towers',
  standalone: true,
  imports: [RouterLink, MoneyPipe, IconComponent],
  template: `
    <div class="card" [attr.aria-busy]="busy() || null">
      <div class="card-head">
        <h2>Branch performance</h2>
        @if (can3d() && (mode() === '3d' || mode() === 'table')) {
          <div class="segmented" role="group" aria-label="Branch performance view">
            <button type="button" [class.active]="!asTable()" [attr.aria-pressed]="!asTable()" (click)="asTable.set(false)">3D</button>
            <button type="button" [class.active]="asTable()" [attr.aria-pressed]="asTable()" (click)="asTable.set(true)">Table</button>
          </div>
        }
        <div class="segmented" role="group" aria-label="Branch performance period">
          @for (p of periods; track p.days) {
            <button type="button" [class.active]="period() === p.days" [attr.aria-pressed]="period() === p.days" (click)="period.set(p.days)">{{ p.label }}</button>
          }
        </div>
      </div>

      @switch (mode()) {
        @case ('loading') {
          <div class="loading-block hold"><span class="spinner"></span> Loading…</div>
        }
        @case ('error') {
          <div class="load-error" role="alert">
            Couldn't load branch performance.
            <button type="button" class="btn btn-sm btn-outline" (click)="retry()">Retry</button>
          </div>
        }
        @case ('empty') {
          <div class="empty"><div class="big"><app-icon name="store" [size]="30" /></div>No branch sales in this period</div>
        }
        @case ('table') {
          <div class="table-wrap">
            <table class="data">
              <caption class="sr-only">Net sales, orders and net margin per branch</caption>
              <thead><tr>
                <th scope="col">Branch</th><th scope="col">Code</th><th scope="col" class="right">Net sales</th>
                <th scope="col" class="right">Orders</th><th scope="col" class="right">Net margin</th>
              </tr></thead>
              <tbody>
                @for (t of model().towers; track t.branchId) {
                  <tr>
                    <td>{{ t.name }}</td>
                    <td class="mono">{{ t.code }}</td>
                    <td class="num">{{ t.netSales | money }}</td>
                    <td class="num">{{ t.orderCount }}</td>
                    <td class="num" [class.neg]="t.loss">{{ t.netMargin | money }}</td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
          @if (!can3d()) { <p class="muted note">3D view isn't available on this device.</p> }
        }
        @case ('3d') {
          <div class="scene" [class.dim]="busy()">
            <div #stage class="stage" aria-hidden="true" (pointerdown)="onPointerDown($event)" (pointermove)="onPointerMove($event)"
              (pointerup)="onPointerUp($event)" (pointercancel)="onPointerUp($event)"></div>
            @if (!ready()) { <div class="wait" role="status"><span class="spinner"></span> Loading 3D view…</div> }
            <div class="labels">
              @for (t of model().towers; track t.branchId) {
                <div #anchor class="tower-anchor" [attr.data-id]="t.branchId" [class.on]="focused()?.branchId === t.branchId"
                  (mouseenter)="show(t)" (mouseleave)="leave(anchor)" (focusin)="show(t)" (focusout)="blur(anchor, $event)">
                  <button #label type="button" class="tower-label glass" [class.top]="t.top" [attr.aria-label]="labelFor(t)"
                    (pointerdown)="pressed(t)" (click)="toggle(t, $event)" (keydown.escape)="focused.set(null)">{{ t.code }}</button>
                  @if (focused()?.branchId === t.branchId) {
                    <div class="tower-tip">
                      <b>{{ t.name }}</b>
                      <dl>
                        <dt>Net sales</dt><dd class="num">{{ t.netSales | money }}</dd>
                        <dt>Orders</dt><dd class="num">{{ t.orderCount }}</dd>
                        <dt>Net margin</dt><dd class="num" [class.neg]="t.loss">{{ t.netMargin | money }}</dd>
                      </dl>
                      <a routerLink="/reports" (keydown.escape)="closeTo(label)">View report</a>
                    </div>
                  }
                </div>
              }
            </div>
          </div>
        }
      }
      @if ((mode() === '3d' || mode() === 'table') && model().others; as o) {
        <p class="muted note">+ {{ o.count }} more {{ o.count === 1 ? 'branch' : 'branches' }} · {{ o.netSales | money }} net sales</p>
      }
    </div>
  `,
  styles: [`
    :host { display: block; }
    .card-head { flex-wrap: wrap; justify-content: flex-start; gap: .6rem .5rem; }
    .card-head h2 { margin-right: auto; }
    .hold { min-height: 360px; }
    .neg { color: var(--red); }
    .note { margin: 0; padding: .7rem 1.35rem .9rem; font-size: 12.5px; border-top: 1px solid var(--border); }
    .scene { position: relative; isolation: isolate; height: 360px; overflow: hidden; border-radius: 0 0 var(--radius) var(--radius); transition: opacity var(--dur-2); }
    .scene.dim { opacity: .55; }
    .stage { position: absolute; inset: 0; line-height: 0; cursor: grab; touch-action: pan-y;
      mask-image: radial-gradient(ellipse 60% 80% at 50% 45%, currentColor 50%, transparent); }
    .stage:active { cursor: grabbing; }
    .wait { position: absolute; inset: 0; display: flex; align-items: center; justify-content: center; gap: .6rem;
      color: var(--text-muted); font-size: 13px; pointer-events: none; }
    .labels { position: absolute; inset: 0; pointer-events: none; }
    .tower-anchor { position: absolute; left: 0; top: 0; z-index: var(--z, 0); }
    .tower-anchor:not(.placed) { visibility: hidden; }
    .tower-anchor.on { z-index: 5000; }
    .tower-label { pointer-events: auto; position: absolute; left: 0; bottom: 8px; transform: translateX(-50%);
      padding: .22rem .5rem; border: 1px solid var(--border-strong); border-radius: 6px; color: var(--text);
      font: 600 11.5px/1.2 var(--font-mono); letter-spacing: .02em; white-space: nowrap; cursor: pointer;
      transition: border-color var(--dur-1), color var(--dur-1); }
    .tower-label.top { color: var(--accent-text); border-color: color-mix(in srgb, var(--accent) 55%, var(--border-strong)); }
    .tower-label:hover, .on .tower-label { color: var(--accent-text); border-color: var(--accent); }
    .tower-tip { pointer-events: auto; position: absolute; left: var(--dx, -110px); bottom: 40px; width: 220px;
      padding: .7rem .85rem .65rem; background: var(--surface-1); border: 1px solid var(--border-strong);
      border-radius: var(--radius-sm); box-shadow: var(--shadow-pop); font-size: 12.5px; line-height: 1.4; }
    .tower-tip::after { content: ""; position: absolute; left: 0; right: 0; top: 100%; height: 16px; }
    .below .tower-tip { bottom: auto; top: 10px; }
    .below .tower-tip::after { top: auto; bottom: 100%; height: 22px; }
    .tower-tip b { display: block; font-size: 13px; }
    .tower-tip dl { display: grid; grid-template-columns: auto 1fr; gap: .15rem .8rem; margin: .45rem 0 .5rem; }
    .tower-tip dt { color: var(--text-muted); }
    .tower-tip dd { margin: 0; }
    .tower-tip a { font-weight: 600; }
  `],
})
export class BranchTowersComponent implements OnDestroy {
  private reporting = inject(ReportingService);
  private theme = inject(ThemeService);
  private loadThree = inject(THREE_LOADER);
  /** Whether this device can draw WebGL at all; without it the panel is always a table. */
  private readonly webgl = inject(WEBGL_SUPPORTED)();

  readonly periods: readonly { days: Period; label: string }[] = [
    { days: 1, label: 'Today' }, { days: 7, label: '7 days' }, { days: 30, label: '30 days' },
  ];
  readonly period = signal<Period>(1);
  /** The tower whose label is hovered or focused; its tip is open. */
  readonly focused = signal<TowerSpec | null>(null);
  readonly asTable = signal(false);
  /** three failed to load or the renderer could not start: the same fallback as no WebGL. */
  private readonly broken = signal(false);
  /** The first frame of the scene is on its way (three has loaded and the towers are built). */
  readonly ready = signal(false);
  /** A new period is loading while the previous figures stay on screen. */
  readonly busy = signal(false);
  private readonly outlets = signal<BranchPerformance[] | null>(null);
  private readonly failed = signal(false);

  readonly model = computed<TowerModel>(() => towerModel(this.outlets() ?? []));
  readonly can3d = computed(() => this.webgl && !this.broken());
  readonly mode = computed<Mode>(() => {
    if (this.failed()) return 'error';
    if (this.outlets() === null) return 'loading';
    if (!this.model().towers.some((t) => t.netSales > 0)) return 'empty'; // no branches, or none sold anything
    return this.can3d() && !this.asTable() ? '3d' : 'table';
  });

  private readonly stageRef = viewChild<ElementRef<HTMLElement>>('stage');
  private readonly anchors = viewChildren<ElementRef<HTMLElement>>('anchor');
  private readonly reload$ = new Subject<void>();
  private stage: ThreeStage | null = null;
  private scene: TowerScene | null = null;
  private host: HTMLElement | null = null;
  /** Bumped on every mount and unmount, so a three import that resolves late knows it is stale. */
  private generation = 0;
  private orbiting = false;
  private readonly motion = typeof matchMedia === 'function' ? matchMedia('(prefers-reduced-motion: reduce)') : null;
  private readonly onMotion = (e: { matches: boolean }) => { if (e.matches) this.stopOrbit(); };
  /** Whether the label's tip was already open when the current pointer press began. */
  private openAtPress = false;
  /** What each label's style was last set to, so an unmoved label is not written again. */
  private readonly labelAt = new WeakMap<HTMLElement, Point>();
  /** The user has touched the scene; the orbit stays off from then on, even if the scene is rebuilt. */
  private interacted = false;
  private drag: { id: number; x: number; angle: number } | null = null;

  constructor() {
    // switchMap drops the request in flight when the period changes, so a slow old answer can't win.
    this.reload$.pipe(
      switchMap(() => {
        const p = reportPeriod(Date.now(), this.period());
        return this.reporting.outlets(p.from.toISOString(), p.to.toISOString()).pipe(catchError(() => of(null)));
      }),
      takeUntilDestroyed(),
    ).subscribe((o) => {
      this.busy.set(false);
      if (!o) { this.failed.set(true); return; }
      this.outlets.set(o);
      const f = this.focused();
      if (f) this.focused.set(this.model().towers.find((t) => t.branchId === f.branchId) ?? null);
    });

    effect(() => { this.period(); untracked(() => this.load()); });
    effect(() => { this.theme.effective(); untracked(() => this.scene?.recolour()); });

    // the stage element comes and goes with the mode; the scene follows it and the model
    afterRenderEffect(() => {
      const host = this.stageRef()?.nativeElement ?? null;
      const towers = this.model().towers;
      untracked(() => this.sync(host, towers));
    });
    // new labels are placed on the next frame
    afterRenderEffect(() => { this.anchors(); untracked(() => this.stage?.invalidate()); });
    this.motion?.addEventListener('change', this.onMotion);
  }

  retry(): void { this.load(); }

  /** The label's accessible name: the visible code plus every figure the tip shows, so none is visual only. */
  labelFor(t: TowerSpec): string {
    const orders = `${t.orderCount} ${t.orderCount === 1 ? 'order' : 'orders'}`;
    return `${t.name} (${t.code}): net sales ${formatMoney(t.netSales)}, ${orders}, net margin ${formatMoney(t.netMargin)}${t.loss ? ' (loss)' : ''}`;
  }

  show(t: TowerSpec): void {
    this.stopOrbit();
    this.focused.set(t);
  }

  pressed(t: TowerSpec): void {
    this.openAtPress = this.focused()?.branchId === t.branchId;
  }

  /**
   * Click, Enter or Space on a label opens or closes its tip. A keyboard click (detail 0) flips it; a pointer
   * press flips the state it had before the press, because hover and focus may have opened it on the way in.
   */
  toggle(t: TowerSpec, e: MouseEvent): void {
    const open = e.detail === 0 ? this.focused()?.branchId !== t.branchId : !this.openAtPress;
    if (open) this.show(t);
    else this.focused.set(null);
  }

  /** Escape inside a tip: focus goes back to its label first (that refocus would reopen it), then the tip closes. */
  closeTo(label: HTMLElement): void {
    label.focus();
    this.focused.set(null);
  }

  /** The pointer left a label: close its tip unless the keyboard focus is still there. */
  leave(anchor: HTMLElement): void {
    if (!anchor.contains(document.activeElement)) this.close(anchor);
  }

  /** Focus left a label or its tip: close the tip unless focus moved within it or the pointer is over it. */
  blur(anchor: HTMLElement, e: FocusEvent): void {
    const next = e.relatedTarget as Node | null;
    if ((next && anchor.contains(next)) || anchor.matches(':hover')) return;
    this.close(anchor);
  }

  onPointerDown(e: PointerEvent): void {
    this.stopOrbit();
    if (!this.scene) return;
    this.drag = { id: e.pointerId, x: e.clientX, angle: this.scene.world.rotation.y };
    (e.currentTarget as HTMLElement).setPointerCapture?.(e.pointerId);
  }

  onPointerMove(e: PointerEvent): void {
    if (!this.drag || e.pointerId !== this.drag.id || !this.scene) return;
    this.scene.world.rotation.y = clamp(this.drag.angle + (e.clientX - this.drag.x) * DRAG_RAD_PER_PX, -Math.PI, Math.PI);
    this.stage?.invalidate();
  }

  onPointerUp(e: PointerEvent): void {
    if (this.drag?.id === e.pointerId) this.drag = null;
  }

  ngOnDestroy(): void {
    this.motion?.removeEventListener('change', this.onMotion);
    this.unmount();
  }

  private load(): void {
    if (this.failed()) { this.failed.set(false); this.outlets.set(null); }
    this.busy.set(true);
    this.reload$.next();
  }

  private close(anchor: HTMLElement): void {
    if (this.focused()?.branchId === anchor.dataset['id']) this.focused.set(null);
  }

  private sync(host: HTMLElement | null, towers: readonly TowerSpec[]): void {
    if (host !== this.host) {
      this.unmount();
      if (host) void this.mount(host);
    } else {
      this.scene?.setTowers(towers);
    }
  }

  private async mount(host: HTMLElement): Promise<void> {
    const run = ++this.generation;
    this.host = host;
    let three: Three;
    try {
      three = await this.loadThree();
    } catch {
      if (run === this.generation) this.broken.set(true);
      return;
    }
    if (run !== this.generation) return; // the page or the view changed while three was loading
    try {
      this.stage = new ThreeStage(three, host);
      this.scene = new TowerScene(three, this.stage);
    } catch {
      this.unmount();
      this.broken.set(true);
      return;
    }
    this.scene.setTowers(this.model().towers);
    this.orbiting = !this.interacted && !this.motion?.matches;
    this.stage.setAnimator((dt) => this.frame(dt));
    this.ready.set(true);
  }

  private unmount(): void {
    this.generation++;
    this.stage?.destroy();
    this.stage = null;
    this.scene = null;
    this.host = null;
    this.drag = null;
    this.ready.set(false);
  }

  /** One frame: advance the orbit, frame the camera, then pin every label to its tower's top. Loops while orbiting. */
  private frame(dt: number): boolean {
    if (!this.scene) return false;
    if (this.orbiting) this.scene.world.rotation.y += dt * ORBIT_RAD_PER_MS;
    this.scene.fit();
    this.placeLabels();
    return this.orbiting;
  }

  /**
   * Moves each label to its tower's top. A label is only written when it moved by more than half a pixel or
   * changed depth rank; its tip offsets are skipped while orbiting (no tip can be open then) unless `force`.
   */
  private placeLabels(force = false): void {
    const host = this.host;
    if (!host || !this.scene) return;
    const w = host.clientWidth;
    const points = this.scene.screenPoints(w, host.clientHeight);
    for (const { nativeElement: a } of this.anchors()) {
      const p = points.get(a.dataset['id'] ?? '');
      if (!p) continue;
      let last = this.labelAt.get(a);
      const fresh = !last;
      if (!last) this.labelAt.set(a, (last = { x: NaN, y: NaN, z: NaN }));
      const moved = !(Math.abs(p.x - last.x) <= 0.5 && Math.abs(p.y - last.y) <= 0.5);
      if (moved) {
        last.x = p.x;
        last.y = p.y;
        a.style.transform = `translate(${p.x.toFixed(1)}px, ${p.y.toFixed(1)}px)`;
      }
      if (p.z !== last.z) {
        last.z = p.z;
        a.style.setProperty('--z', String(p.z));
      }
      if (fresh || force || (moved && !this.orbiting)) {
        a.style.setProperty('--dx', `${(clamp(p.x - TIP_W / 2, 8, w - TIP_W - 8) - p.x).toFixed(1)}px`);
        a.classList.toggle('below', p.y < TIP_FLIP_Y);
      }
      if (fresh) a.classList.add('placed');
    }
  }

  /** Any interaction stops the orbit for good; the angle is wrapped back into ±π for the drag's bounds. */
  private stopOrbit(): void {
    const wasOrbiting = this.orbiting;
    this.orbiting = false;
    this.interacted = true;
    if (wasOrbiting && this.scene) {
      const a = this.scene.world.rotation.y;
      this.scene.world.rotation.y = Math.atan2(Math.sin(a), Math.cos(a));
      this.placeLabels(true); // the tip offsets were left stale while the scene turned
    }
  }
}
