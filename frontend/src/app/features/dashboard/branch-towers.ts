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
import { LabelPlacer, LabelTips, ScreenPoint } from '../../three/label-overlay';
import { LazyStage } from '../../three/lazy-stage';
import { ThreeStage } from '../../three/three-stage';
import { TowerModel, TowerSpec, towerModel } from '../../three/tower-model';
import { THREE_LOADER, WEBGL_SUPPORTED } from '../../three/webgl';
import { reportPeriod } from '../reports/reports';

type Three = typeof import('three');
type Period = 1 | 7 | 30;
type Mode = '3d' | 'table' | 'loading' | 'error' | 'empty';

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
  private slots: { t: TowerSpec; p: ScreenPoint }[] = [];
  private readonly points = new Map<string, ScreenPoint>();
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
  screenPoints(w: number, h: number): ReadonlyMap<string, ScreenPoint> {
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
            @if (!ready()) { <div class="scene-wait" role="status"><span class="spinner"></span> Loading 3D view…</div> }
            <div class="overlay-labels">
              @for (t of model().towers; track t.branchId) {
                <div #anchor class="label-anchor tower-anchor" [attr.data-id]="t.branchId" [class.on]="focused()?.branchId === t.branchId"
                  (mouseenter)="show(t)" (mouseleave)="tips.leave(anchor)" (focusin)="show(t)" (focusout)="tips.blur(anchor, $event)">
                  <button #label type="button" class="tower-label glass" [class.top]="t.top" [attr.aria-label]="labelFor(t)"
                    (pointerdown)="pressed(t)" (click)="toggle(t, $event)">{{ t.code }}</button>
                  @if (focused()?.branchId === t.branchId) {
                    <div class="label-tip tower-tip">
                      <b>{{ t.name }}</b>
                      <dl>
                        <dt>Net sales</dt><dd class="num">{{ t.netSales | money }}</dd>
                        <dt>Orders</dt><dd class="num">{{ t.orderCount }}</dd>
                        <dt>Net margin</dt><dd class="num" [class.neg]="t.loss">{{ t.netMargin | money }}</dd>
                      </dl>
                      <a routerLink="/reports" (keydown.escape)="tips.closeTo(label)">View report</a>
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
    .tower-label { pointer-events: auto; position: absolute; left: 0; bottom: 8px; transform: translateX(-50%);
      padding: .22rem .5rem; border: 1px solid var(--border-strong); border-radius: 6px; color: var(--text);
      font: 600 11.5px/1.2 var(--font-mono); letter-spacing: .02em; white-space: nowrap; cursor: pointer;
      transition: border-color var(--dur-1), color var(--dur-1); }
    .tower-label.top { color: var(--accent-text); border-color: color-mix(in srgb, var(--accent) 55%, var(--border-strong)); }
    .tower-label:hover, .on .tower-label { color: var(--accent-text); border-color: var(--accent); }
    .tower-tip a { font-weight: 600; }
  `],
})
export class BranchTowersComponent implements OnDestroy {
  private reporting = inject(ReportingService);
  private theme = inject(ThemeService);
  /** Whether this device can draw WebGL at all; without it the panel is always a table. */
  private readonly webgl = inject(WEBGL_SUPPORTED)();

  readonly periods: readonly { days: Period; label: string }[] = [
    { days: 1, label: 'Today' }, { days: 7, label: '7 days' }, { days: 30, label: '30 days' },
  ];
  readonly period = signal<Period>(1);
  readonly tips = new LabelTips<TowerSpec>((t) => t.branchId);
  /** The tower whose label is hovered or focused; its tip is open. */
  readonly focused = this.tips.open;
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
  /** three, the stage and the towers scene, mounted while the stage element is on the page. */
  private readonly lazy = new LazyStage<TowerScene>(inject(THREE_LOADER), {
    build: (three, stage) => new TowerScene(three, stage),
    started: (scene, stage) => {
      scene.setTowers(this.model().towers);
      this.orbiting = !this.interacted && !this.motion?.matches;
      stage.setAnimator((dt) => this.frame(dt));
      this.ready.set(true);
    },
    failed: () => this.broken.set(true),
    stopped: () => {
      this.drag = null;
      this.ready.set(false);
    },
  });
  private readonly placer = new LabelPlacer({ width: TIP_W, flipBelow: TIP_FLIP_Y });
  private orbiting = false;
  private readonly motion = typeof matchMedia === 'function' ? matchMedia('(prefers-reduced-motion: reduce)') : null;
  private readonly onMotion = (e: { matches: boolean }) => { if (e.matches) this.stopOrbit(); };
  /** Whether the label's tip was already open when the current pointer press began. */
  private openAtPress = false;
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
    this.tips.show(t);
  }

  pressed(t: TowerSpec): void {
    this.openAtPress = this.tips.isOpen(t);
  }

  /**
   * Click, Enter or Space on a label opens or closes its tip. A keyboard click (detail 0) flips it; a pointer
   * press flips the state it had before the press, because hover and focus may have opened it on the way in.
   */
  toggle(t: TowerSpec, e: MouseEvent): void {
    const open = e.detail === 0 ? !this.tips.isOpen(t) : !this.openAtPress;
    if (open) this.show(t);
    else this.tips.hide();
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
    this.lazy.unmount();
  }

  private get scene(): TowerScene | null { return this.lazy.scene; }
  private get stage(): ThreeStage | null { return this.lazy.stage; }

  private load(): void {
    if (this.failed()) { this.failed.set(false); this.outlets.set(null); }
    this.busy.set(true);
    this.reload$.next();
  }

  /** The stage element came, went or stayed: mount, tear down, or rebuild the towers in place. */
  private sync(host: HTMLElement | null, towers: readonly TowerSpec[]): void {
    if (this.lazy.follow(host)) this.scene?.setTowers(towers);
  }

  /** One frame: advance the orbit, frame the camera, then pin every label to its tower's top. Loops while orbiting. */
  private frame(dt: number): boolean {
    if (!this.scene) return false;
    if (this.orbiting) this.scene.world.rotation.y += dt * ORBIT_RAD_PER_MS;
    this.scene.fit();
    this.placeLabels();
    return this.orbiting;
  }

  /** Pins each label to its tower's top, nearer towers' labels on top; tip offsets wait while the scene orbits. */
  private placeLabels(force = false): void {
    const host = this.lazy.host;
    if (!host || !this.scene) return;
    const w = host.clientWidth;
    this.placer.place(this.anchors(), this.scene.screenPoints(w, host.clientHeight), w, { tips: true, moving: this.orbiting, force });
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
