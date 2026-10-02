import {
  Component, ElementRef, OnDestroy, afterRenderEffect, computed, effect, inject, input, output, signal, untracked, viewChild, viewChildren,
} from '@angular/core';
import type {
  BoxGeometry, Color, CylinderGeometry, Group, InstancedMesh, Matrix4, MeshPhysicalMaterial, MeshStandardMaterial, Raycaster, Vector2, Vector3,
} from 'three';
import { IngredientResponse, StockItemResponse } from '../../core/models';
import { ThemeService } from '../../core/theme.service';
import { MoneyPipe, QtyPipe, formatMoney } from '../../core/util';
import { ContainerSpec, Level, ShelfSpec, shelfModel } from '../../three/shelf-model';
import { ThreeStage } from '../../three/three-stage';
import { THREE_LOADER, WEBGL_SUPPORTED } from '../../three/webgl';

type Three = typeof import('three');
/** A label's spot on the canvas: CSS pixels, plus a z-index rank that grows as the spot gets nearer the camera. */
interface Point { x: number; y: number; z: number; }
/** One instanced mesh, and the container each of its drawn instances stands for. */
interface Batch { mesh: InstancedMesh; ids: ContainerSpec[]; }

/** CSS pixels per world unit on the shelf face; the camera distance follows the stage height to hold it. */
const PPU = 50;
/** Container centres along a shelf, and shelves down the rack, in world units. */
const SLOT = 1.3;
const ROW = 2.4;
const PLANK_H = 0.08;
const PLANK_D = 1.1;
/** How far a plank reaches past its first and last container. */
const PLANK_PAD = 0.2;
const SHELL_R = 0.45;
const SHELL_H = 1.4;
const FILL_MAX = 1.36;
/** Room above each row of containers for their labels, and under the bottom plank. */
const LABEL_ROOM = 0.95;
const FOOT = 0.45;
/** Room left of the planks for the category tags (about 105px). */
const TAG_ROOM = 2.1;
const MIN_HEIGHT = 240;
const PULSE_MS = 1600;
/** Low fills glow at GLOW, or swing GLOW ± GLOW_SWING (.3 to .8) while they pulse. */
const GLOW = 0.55;
const GLOW_SWING = 0.25;
/** Width of the .shelf-tip card; the tip is shifted sideways to stay inside the stage. */
const TIP_W = 220;
/** A label closer than this to the top of the stage opens its tip below instead of above. */
const TIP_FLIP_Y = 196;
/** How far (px) a press may wander and still be a click rather than a drag. */
const CLICK_SLOP = 4;
/** How close (world units) a focused container may come to the stage edge before the shelf pans to it. */
const REVEAL_MARGIN = 0.9;
const LEVELS: readonly Level[] = ['ok', 'low', 'empty'];
const LEVEL_TOKEN: Record<Level, string> = { ok: '--accent', low: '--amber', empty: '--red' };
const STATUS: Record<Level, string> = { ok: 'Healthy', low: 'Below reorder', empty: 'Out of stock' };

const clamp = (v: number, lo: number, hi: number) => Math.min(Math.max(v, lo), Math.max(lo, hi));
const plankTop = (shelf: number) => -shelf * ROW + PLANK_H / 2;
/** The rack's height in world units: every shelf, the labels over the top row and a foot under the bottom plank. */
const rackHeight = (shelves: number) => Math.max(0, shelves - 1) * ROW + PLANK_H + SHELL_H + LABEL_ROOM + FOOT;
/** Lower case without Vietnamese accents, so "sua" finds "Sữa" and "duong" finds "Đường". */
const fold = (s: string) => s.normalize('NFD').replace(/\p{M}/gu, '').replace(/[đĐ]/g, 'd').toLowerCase();

/** The rack on a ThreeStage: a plank per category, glass containers and their level fills as instanced meshes, coloured from the CSS tokens. */
class ShelfScene {
  /** The camera's x. A drag sets it; `view` keeps it inside the rack, starting at the rack's left end. */
  panX = -Infinity;
  /** The rack is wider than the stage, so a sideways drag pans it. */
  pannable = false;
  private readonly root: Group;
  private readonly planks: Group;
  private readonly box: BoxGeometry;
  private readonly shellGeometry: CylinderGeometry;
  private readonly fillGeometry: CylinderGeometry;
  private readonly mat: {
    plank: MeshStandardMaterial; shell: MeshPhysicalMaterial; shellDim: MeshPhysicalMaterial;
    fill: Record<Level, MeshStandardMaterial>; fillDim: Record<Level, MeshStandardMaterial>;
  };
  /** Lit and dimmed twins: an instanced mesh has one opacity, so a dimmed container moves to the dim batch. */
  private batches: { shells: Batch; shellsDim: Batch; fill: Record<Level, Batch>; fillDim: Record<Level, Batch> } | null = null;
  private containers: ContainerSpec[] = [];
  private filtering = false;
  private readonly white: Color;
  private readonly matrix: Matrix4;
  private readonly point: Vector3;
  private readonly ray: Raycaster;
  private readonly ndc: Vector2;
  /** One reusable screen point per label (category tags and containers), so projecting every frame allocates nothing. */
  private slots: { x: number; y: number; p: Point }[] = [];
  private readonly points = new Map<string, Point>();
  private extent = { left: 0, right: 0, top: 0, bottom: 0 };
  private viewed = { w: 0, h: 0, x: NaN };

  constructor(private readonly three: Three, private readonly stage: ThreeStage) {
    const T = three;
    this.root = new T.Group();
    this.planks = new T.Group();
    this.planks.name = 'planks';
    this.root.add(this.planks);
    const ambient = new T.AmbientLight();
    ambient.intensity = 0.8;
    const sun = new T.DirectionalLight();
    sun.intensity = 1.2;
    sun.position.set(3, 8, 10);
    stage.scene.add(this.root, ambient, sun);

    this.box = new T.BoxGeometry(1, 1, 1);
    this.shellGeometry = new T.CylinderGeometry(SHELL_R, SHELL_R, SHELL_H, 24);
    this.fillGeometry = new T.CylinderGeometry(1, 1, 1, 24);
    const solid = (glow: number) => new T.MeshStandardMaterial({ roughness: 0.45, emissiveIntensity: glow });
    const faded = () => new T.MeshStandardMaterial({ roughness: 0.45, emissiveIntensity: 0.15, transparent: true, opacity: 0.25, depthWrite: false });
    const glass = (opacity: number) => new T.MeshPhysicalMaterial({ transparent: true, opacity, roughness: 0.1, depthWrite: false });
    this.mat = {
      plank: new T.MeshStandardMaterial({ roughness: 0.7 }),
      shell: glass(0.18),
      shellDim: glass(0.18 * 0.25),
      fill: { ok: solid(0.35), low: solid(GLOW), empty: solid(0.35) },
      fillDim: { ok: faded(), low: faded(), empty: faded() },
    };
    this.white = new T.Color(); // three's default colour is white
    this.mat.shell.emissive.copy(this.white);
    this.mat.shell.emissiveIntensity = 0;
    this.matrix = new T.Matrix4();
    this.point = new T.Vector3();
    this.ray = new T.Raycaster();
    this.ndc = new T.Vector2();
    this.recolour();
  }

  /** Whether any low container is lit, so there is something to pulse. */
  get lowLit(): boolean {
    return (this.batches?.fill.low.ids.length ?? 0) > 0;
  }

  /** Re-reads the colour tokens (after a theme switch) and repaints. */
  recolour(): void {
    const css = getComputedStyle(document.documentElement);
    const tint = (target: Color, token: string) => {
      const value = css.getPropertyValue(token).trim();
      if (value) target.setStyle(value);
    };
    tint(this.mat.plank.color, '--surface-3');
    tint(this.mat.shell.color, '--text-soft');
    tint(this.mat.shellDim.color, '--text-soft');
    for (const l of LEVELS) {
      tint(this.mat.fill[l].color, LEVEL_TOKEN[l]);
      tint(this.mat.fillDim[l].color, LEVEL_TOKEN[l]);
    }
    this.bump();
    this.stage.invalidate();
  }

  /** Replaces the rack. Geometries and materials are shared; the instanced meshes are made anew for the new count. */
  setShelves(shelves: readonly ShelfSpec[], lit: ReadonlySet<string>, filtering: boolean): void {
    const T = this.three;
    this.containers = shelves.flatMap((s) => s.containers);
    const slots = Math.max(1, ...shelves.map((s) => s.containers.length));
    const left = -SLOT / 2 - PLANK_PAD;
    const right = (slots - 1) * SLOT + SLOT / 2 + PLANK_PAD;
    this.planks.clear();
    for (const s of shelves) {
      const plank = new T.Mesh(this.box, this.mat.plank);
      plank.scale.set(right - left, PLANK_H, PLANK_D);
      plank.position.set((left + right) / 2, -s.index * ROW, 0);
      this.planks.add(plank);
    }
    this.extent = {
      left: left - TAG_ROOM, right: right + 0.3,
      top: plankTop(0) + SHELL_H + LABEL_ROOM, bottom: plankTop(shelves.length - 1) - PLANK_H - FOOT,
    };

    this.dropBatches();
    const make = (geometry: CylinderGeometry, material: MeshStandardMaterial, size: number, name: string): Batch => {
      const mesh = new T.InstancedMesh(geometry, material, Math.max(1, size));
      mesh.name = name;
      mesh.count = 0;
      this.root.add(mesh);
      return { mesh, ids: [] };
    };
    const fill = {} as Record<Level, Batch>;
    const fillDim = {} as Record<Level, Batch>;
    for (const l of LEVELS) {
      const n = this.containers.filter((c) => c.level === l).length;
      fill[l] = make(this.fillGeometry, this.mat.fill[l], n, `fill-${l}`);
      fillDim[l] = make(this.fillGeometry, this.mat.fillDim[l], n, `fill-${l}-dim`);
    }
    const all = this.containers.length;
    this.batches = { shells: make(this.shellGeometry, this.mat.shell, all, 'shells'), shellsDim: make(this.shellGeometry, this.mat.shellDim, all, 'shells-dim'), fill, fillDim };

    this.slots = [];
    this.points.clear();
    const slot = (id: string, x: number, y: number) => {
      const p = { x: 0, y: 0, z: 0 };
      this.slots.push({ x, y, p });
      this.points.set(id, p);
    };
    for (const s of shelves) slot(`shelf:${s.index}`, left, plankTop(s.index) + SHELL_H / 2);
    for (const c of this.containers) slot(c.ingredientId, c.slot * SLOT, plankTop(c.shelf) + SHELL_H);
    this.panX = -Infinity; // a new rack starts at its left end
    this.viewed.x = NaN; // and is framed afresh on the next frame
    this.highlight(lit, filtering);
  }

  /** Puts each container in the lit or the dimmed batch; while a filter is on, the lit ones glow whiter. */
  highlight(lit: ReadonlySet<string>, filtering: boolean): void {
    this.filtering = filtering;
    const b = this.batches;
    if (b) {
      const all = [b.shells, b.shellsDim, ...LEVELS.flatMap((l) => [b.fill[l], b.fillDim[l]])];
      for (const batch of all) batch.ids.length = 0;
      for (const c of this.containers) {
        const on = lit.has(c.ingredientId);
        const x = c.slot * SLOT;
        const base = plankTop(c.shelf);
        this.put(on ? b.shells : b.shellsDim, c, this.matrix.makeTranslation(x, base + SHELL_H / 2, 0));
        const h = Math.max(0.02, c.fill) * FILL_MAX;
        this.put((on ? b.fill : b.fillDim)[c.level], c, this.matrix.makeScale(0.4, h, 0.4).setPosition(x, base + 0.01 + h / 2, 0));
      }
      for (const { mesh, ids } of all) {
        mesh.count = ids.length;
        mesh.instanceMatrix.needsUpdate = true;
        mesh.computeBoundingSphere(); // for culling and picking at the new spots
      }
    }
    this.bump();
    this.stage.invalidate();
  }

  /** The low fills' glow, which the pulse moves every frame. */
  glow(intensity: number): void {
    this.mat.fill.low.emissiveIntensity = intensity;
  }

  /** Frames the whole rack top to bottom at PPU pixels per unit, and the camera's x inside the rack's ends. */
  view(w: number, h: number): void {
    if (!w || !h) return;
    const { left, right, top, bottom } = this.extent;
    const half = w / PPU / 2;
    this.pannable = left + half < right - half;
    this.panX = this.pannable ? clamp(this.panX, left + half, right - half) : (left + right) / 2;
    const seen = this.viewed;
    if (w === seen.w && h === seen.h && this.panX === seen.x) return;
    seen.w = w;
    seen.h = h;
    seen.x = this.panX;
    const camera = this.stage.camera;
    const y = (top + bottom) / 2;
    const distance = h / (2 * PPU * Math.tan((camera.fov * Math.PI) / 360));
    camera.position.set(this.panX, y, distance);
    camera.lookAt(this.panX, y, 0);
    camera.updateMatrixWorld();
  }

  /** Pans just enough to bring a container (a focused label) inside the stage. */
  reveal(c: ContainerSpec, w: number): void {
    if (!this.pannable) return;
    const x = c.slot * SLOT;
    const half = w / PPU / 2;
    if (x - REVEAL_MARGIN < this.panX - half) this.panX = x - REVEAL_MARGIN + half;
    else if (x + REVEAL_MARGIN > this.panX + half) this.panX = x + REVEAL_MARGIN - half;
  }

  /** Where each label lands on a w × h canvas, by id. The map and its points are reused every call. */
  screenPoints(w: number, h: number): ReadonlyMap<string, Point> {
    const camera = this.stage.camera;
    for (const { x, y, p } of this.slots) {
      const v = this.point.set(x, y, 0);
      p.z = Math.max(0, 10000 - Math.round(v.distanceTo(camera.position) * 100));
      v.project(camera);
      p.x = ((v.x + 1) / 2) * w;
      p.y = ((1 - v.y) / 2) * h;
    }
    return this.points;
  }

  /** The lit container under a canvas point given in normalised device coordinates; dimmed ones are not pickable. */
  pickAt(x: number, y: number): ContainerSpec | null {
    const shells = this.batches?.shells;
    if (!shells?.ids.length) return null;
    this.root.updateMatrixWorld();
    this.ray.setFromCamera(this.ndc.set(x, y), this.stage.camera);
    const id = this.ray.intersectObject(shells.mesh, false)[0]?.instanceId;
    return id === undefined ? null : (shells.ids[id] ?? null);
  }

  private put(batch: Batch, c: ContainerSpec, m: Matrix4): void {
    batch.mesh.setMatrixAt(batch.ids.length, m);
    batch.ids.push(c);
  }

  /** The white lift on lit containers while a filter is on. */
  private bump(): void {
    for (const l of LEVELS) {
      this.mat.fill[l].emissive.copy(this.mat.fill[l].color).lerp(this.white, this.filtering ? 0.4 : 0);
      this.mat.fillDim[l].emissive.copy(this.mat.fillDim[l].color);
    }
    this.mat.shell.emissiveIntensity = this.filtering ? 0.25 : 0;
  }

  /** Frees the old instance buffers; the shared geometries and materials stay. */
  private dropBatches(): void {
    const b = this.batches;
    if (!b) return;
    for (const { mesh } of [b.shells, b.shellsDim, ...LEVELS.flatMap((l) => [b.fill[l], b.fillDim[l]])]) {
      this.root.remove(mesh);
      mesh.dispose();
    }
    this.batches = null;
  }
}

/** Stock as glass containers on 3D shelves, one shelf per category, with category chips, search and a label per container. */
@Component({
  selector: 'app-stock-shelves',
  standalone: true,
  imports: [MoneyPipe, QtyPipe],
  template: `
    @if (can3d()) {
      <div class="shelf-controls">
        <div class="pill-row" role="group" aria-label="Shelf category">
          <button type="button" class="pill" [class.active]="activeCategory() === null" [attr.aria-pressed]="activeCategory() === null" (click)="category.set(null)">All</button>
          @for (s of shelves(); track s.category) {
            <button type="button" class="pill" [class.active]="activeCategory() === s.category" [attr.aria-pressed]="activeCategory() === s.category" (click)="category.set(s.category)">{{ s.category }}</button>
          }
        </div>
        <div class="shelf-search">
          <label for="shelf-search" class="sr-only">Search ingredients</label>
          <input id="shelf-search" class="input" type="search" autocomplete="off" placeholder="Search ingredients" [value]="search()" (input)="onSearch($event)" />
        </div>
        <span class="match-count" role="status">{{ filtering() ? matched().size + ' of ' + total() + ' match' : '' }}</span>
      </div>
      <div class="scene" [style.height.px]="height()">
        <div #stage class="stage" aria-hidden="true" (pointerdown)="onPointerDown($event)" (pointermove)="onPointerMove($event)"
          (pointerup)="onPointerUp($event)" (pointercancel)="onPointerCancel($event)" (pointerleave)="onPointerLeave($event)"></div>
        @if (!ready()) { <div class="wait" role="status"><span class="spinner"></span> Loading 3D view…</div> }
        <div class="labels">
          @for (s of shelves(); track s.category) {
            <div role="group" [attr.aria-label]="s.category + ' shelf'">
              <div #tag class="shelf-anchor" [attr.data-id]="'shelf:' + s.index"><span class="shelf-tag" aria-hidden="true">{{ s.category }}</span></div>
              @for (c of s.containers; track c.ingredientId) {
                @let lit = matched().has(c.ingredientId);
                <div #anchor class="shelf-anchor" [attr.data-id]="c.ingredientId" [class.on]="focused()?.ingredientId === c.ingredientId" [class.dim]="!lit"
                  (mouseenter)="focused.set(c)" (mouseleave)="leave(anchor)" (focusin)="focusOn(c)" (focusout)="blur(anchor, $event)">
                  <button type="button" class="shelf-label glass" [class.hit]="lit && filtering()" [attr.aria-disabled]="lit ? null : true"
                    [attr.tabindex]="lit ? null : -1" [attr.aria-label]="labelFor(c)" (click)="choose(c)" (keydown.escape)="focused.set(null)"><span>{{ c.name }}</span></button>
                  @if (c.level !== 'ok') {
                    <span class="jar-flag" [class.out]="c.level === 'empty'" aria-hidden="true">{{ c.level === 'empty' ? 'Out' : 'Low' }}</span>
                  }
                  @if (focused()?.ingredientId === c.ingredientId) {
                    <div class="shelf-tip" aria-hidden="true">
                      <b>{{ c.name }}</b>
                      <dl>
                        <dt>On hand</dt><dd class="num">{{ c.onHand | qty }} {{ c.unit }}</dd>
                        <dt>Reorder level</dt><dd class="num">{{ c.reorderLevel | qty }} {{ c.unit }}</dd>
                        <dt>Avg cost</dt><dd class="num">{{ c.avgUnitCost | money }}</dd>
                      </dl>
                      <p class="tip-status" [attr.data-level]="c.level">{{ statusOf(c) }}</p>
                      @if (c.noReorderLevel) { <p class="tip-note">No reorder level set</p> }
                    </div>
                  }
                </div>
              }
            </div>
          }
        </div>
      </div>
    } @else {
      <p class="muted">3D view isn't available on this device.</p>
    }
  `,
  styles: [`
    :host { display: block; }
    :host > .muted { margin: 0; padding: 1.2rem 1.35rem; }
    .shelf-controls { display: flex; flex-wrap: wrap; align-items: center; gap: .6rem 1rem; padding: .9rem 1.35rem; border-bottom: 1px solid var(--border); }
    .shelf-controls .pill-row { flex: 1 1 auto; }
    .match-count { font-size: 12.5px; color: var(--text-muted); white-space: nowrap; }
    .shelf-search { flex: 0 1 220px; min-width: 170px; }
    .shelf-search .input { min-height: 36px; font-size: 13.5px; }
    .scene { position: relative; isolation: isolate; overflow-x: clip; }
    .stage { position: absolute; inset: 0; line-height: 0; touch-action: pan-y; }
    .stage.pannable { cursor: grab; }
    .stage.pannable:active { cursor: grabbing; }
    .stage.over { cursor: pointer; }
    .wait { position: absolute; inset: 0; display: flex; align-items: center; justify-content: center; gap: .6rem;
      color: var(--text-muted); font-size: 13px; pointer-events: none; }
    .labels { position: absolute; inset: 0; pointer-events: none; }
    .shelf-anchor { position: absolute; left: 0; top: 0; z-index: var(--z, 0); }
    .shelf-anchor:not(.placed) { visibility: hidden; }
    .shelf-anchor.on { z-index: 20000; }
    .shelf-tag { position: absolute; right: 12px; top: 0; transform: translateY(-50%); max-width: 92px; overflow: hidden;
      text-overflow: ellipsis; white-space: nowrap; color: var(--text-muted); font: 600 11px/1.2 var(--font-display);
      letter-spacing: .05em; text-transform: uppercase; }
    /* at most one slot wide (1.3 units × PPU, less a gap), two lines */
    .shelf-label { pointer-events: auto; position: absolute; left: 0; bottom: 6px; transform: translateX(-50%);
      width: max-content; max-width: 62px; padding: 2px 3px; border: 1px solid var(--border-strong); border-radius: 5px;
      color: var(--text); font: 600 10px/1.2 var(--font); text-align: center; cursor: pointer;
      transition: border-color var(--dur-1), color var(--dur-1), opacity var(--dur-1); }
    .shelf-label span { display: -webkit-box; -webkit-box-orient: vertical; -webkit-line-clamp: 2; overflow: hidden; overflow-wrap: anywhere; }
    .shelf-label:hover, .on .shelf-label, .shelf-label.hit { color: var(--accent-text); border-color: var(--accent); }
    .dim .shelf-label { opacity: .45; cursor: default; }
    .jar-flag { position: absolute; left: 0; top: 18px; transform: translateX(-50%); pointer-events: none; padding: 1px 4px;
      border-radius: 4px; background: var(--amber-soft); color: var(--amber); font: 700 9px/1.3 var(--font);
      letter-spacing: .06em; text-transform: uppercase; }
    .jar-flag.out { background: var(--red-soft); color: var(--red); }
    .dim .jar-flag { opacity: .35; }
    .shelf-tip { pointer-events: none; position: absolute; left: var(--dx, -110px); bottom: 46px; width: 220px;
      padding: .65rem .8rem .6rem; background: var(--surface-1); border: 1px solid var(--border-strong);
      border-radius: var(--radius-sm); box-shadow: var(--shadow-pop); font-size: 12.5px; line-height: 1.4; }
    .below .shelf-tip { bottom: auto; top: 10px; }
    .shelf-tip b { display: block; font-size: 13px; }
    .shelf-tip dl { display: grid; grid-template-columns: auto 1fr; gap: .15rem .8rem; margin: .4rem 0 .35rem; }
    .shelf-tip dt { color: var(--text-muted); }
    .shelf-tip dd, .shelf-tip p { margin: 0; }
    .tip-status { font-weight: 600; color: var(--green); }
    .tip-status[data-level="low"] { color: var(--amber); }
    .tip-status[data-level="empty"] { color: var(--red); }
    .tip-note { color: var(--text-soft); }
  `],
})
export class StockShelvesComponent implements OnDestroy {
  readonly stock = input.required<StockItemResponse[]>();
  readonly ingredients = input.required<IngredientResponse[]>();
  /** The ingredient id of a container clicked on the canvas, or whose label was clicked or activated with Enter or Space. */
  readonly pick = output<string>();
  /** three failed to load or the renderer could not start; the page shows its table instead. */
  readonly unavailable = output<void>();

  private theme = inject(ThemeService);
  private loadThree = inject(THREE_LOADER);
  /** Whether this device can draw WebGL at all; without it the component is only a note. */
  private readonly webgl = inject(WEBGL_SUPPORTED)();
  private readonly qty = new QtyPipe();

  readonly shelves = computed(() => shelfModel(this.stock(), this.ingredients()));
  /** The category chip that is on; null is All. */
  readonly category = signal<string | null>(null);
  readonly search = signal('');
  /** The container whose label is hovered or focused; its tip is open. */
  readonly focused = signal<ContainerSpec | null>(null);
  /** three has loaded and the rack is built. */
  readonly ready = signal(false);
  /** three failed to load or the renderer could not start: the same fallback as no WebGL. */
  private readonly broken = signal(false);
  readonly can3d = computed(() => this.webgl && !this.broken());
  /** The chosen category while it is on these shelves; after a reload without it, every shelf is lit again. */
  readonly activeCategory = computed(() => {
    const c = this.category();
    return c !== null && this.shelves().some((s) => s.category === c) ? c : null;
  });
  private readonly query = computed(() => fold(this.search().trim()));
  readonly filtering = computed(() => this.activeCategory() !== null || this.query() !== '');
  /** Ingredient ids of the containers the category and the search leave lit. */
  readonly matched = computed<ReadonlySet<string>>(() => {
    const category = this.activeCategory();
    const q = this.query();
    const ids = new Set<string>();
    for (const s of this.shelves()) {
      if (category !== null && s.category !== category) continue;
      for (const c of s.containers) if (!q || fold(c.name).includes(q)) ids.add(c.ingredientId);
    }
    return ids;
  });
  readonly total = computed(() => this.shelves().reduce((n, s) => n + s.containers.length, 0));
  /** The stage grows with the shelves, so every shelf is in frame at the same scale and the page scrolls past them. */
  readonly height = computed(() => Math.max(MIN_HEIGHT, Math.round(rackHeight(this.shelves().length) * PPU)));

  private readonly stageRef = viewChild<ElementRef<HTMLElement>>('stage');
  private readonly anchors = viewChildren<ElementRef<HTMLElement>>('anchor');
  private readonly tags = viewChildren<ElementRef<HTMLElement>>('tag');
  private stage: ThreeStage | null = null;
  private scene: ShelfScene | null = null;
  private host: HTMLElement | null = null;
  /** Bumped on every mount and unmount, so a three import that resolves late knows it is stale. */
  private generation = 0;
  /** Milliseconds into the current pulse. */
  private clock = 0;
  /** The stage width the tip offsets were last worked out for. */
  private placedWidth = 0;
  private readonly motion = typeof matchMedia === 'function' ? matchMedia('(prefers-reduced-motion: reduce)') : null;
  private reduced = this.motion?.matches ?? false;
  private readonly onMotion = (e: { matches: boolean }) => {
    this.reduced = e.matches;
    this.stage?.invalidate(); // one more frame settles the glow, or restarts the pulse
  };
  /** What each label's style was last set to, so an unmoved label is not written again. */
  private readonly labelAt = new WeakMap<HTMLElement, Point>();
  private press: { id: number; x: number; y: number; pan: number; moved: boolean } | null = null;

  constructor() {
    effect(() => { this.theme.effective(); untracked(() => this.scene?.recolour()); });
    effect(() => {
      const lit = this.matched();
      const filtering = this.filtering();
      untracked(() => this.scene?.highlight(lit, filtering));
    });
    // the stage element comes and goes with WebGL support; the scene follows it, and the rack follows the stock
    afterRenderEffect(() => {
      const host = this.stageRef()?.nativeElement ?? null;
      const shelves = this.shelves();
      untracked(() => this.sync(host, shelves));
    });
    // new labels are placed on the next frame
    afterRenderEffect(() => { this.anchors(); this.tags(); untracked(() => this.stage?.invalidate()); });
    this.motion?.addEventListener('change', this.onMotion);
  }

  /** The label's accessible name: the visible name plus every figure and status word the tip shows. */
  labelFor(c: ContainerSpec): string {
    const amount = (n: number) => `${this.qty.transform(n)} ${c.unit}`;
    return `${c.name}: on hand ${amount(c.onHand)}, reorder level ${amount(c.reorderLevel)}, average cost ${formatMoney(c.avgUnitCost)}, `
      + `${STATUS[c.level].toLowerCase()}${c.noReorderLevel ? ', no reorder level set' : ''}`;
  }

  statusOf(c: ContainerSpec): string {
    return STATUS[c.level];
  }

  onSearch(e: Event): void {
    this.search.set((e.target as HTMLInputElement).value);
  }

  /** A click, Enter or Space on a lit label picks it; a dimmed one is disabled. */
  choose(c: ContainerSpec): void {
    if (this.matched().has(c.ingredientId)) this.pick.emit(c.ingredientId);
  }

  /** Focus opens the tip and pans the shelf so the container is in view. */
  focusOn(c: ContainerSpec): void {
    this.focused.set(c);
    if (this.scene && this.host) {
      this.scene.reveal(c, this.host.clientWidth);
      this.stage?.invalidate();
    }
  }

  /** The pointer left a label: close its tip unless the keyboard focus is still there. */
  leave(anchor: HTMLElement): void {
    if (!anchor.contains(document.activeElement)) this.close(anchor);
  }

  /** Focus left a label: close its tip unless the pointer is over it. */
  blur(anchor: HTMLElement, e: FocusEvent): void {
    const next = e.relatedTarget as Node | null;
    if ((next && anchor.contains(next)) || anchor.matches(':hover')) return;
    this.close(anchor);
  }

  onPointerDown(e: PointerEvent): void {
    this.press = { id: e.pointerId, x: e.clientX, y: e.clientY, pan: this.scene?.panX ?? 0, moved: false };
    (e.currentTarget as HTMLElement).setPointerCapture?.(e.pointerId);
  }

  /** A sideways drag pans the shelves; without a press, the pointer turns to a hand over a lit container. */
  onPointerMove(e: PointerEvent): void {
    const press = this.press;
    if (press && e.pointerId === press.id) {
      if (!press.moved && Math.hypot(e.clientX - press.x, e.clientY - press.y) > CLICK_SLOP) press.moved = true;
      if (press.moved && this.scene?.pannable) {
        this.scene.panX = press.pan - (e.clientX - press.x) / PPU;
        this.stage?.invalidate();
      }
      return;
    }
    (e.currentTarget as HTMLElement).classList.toggle('over', !!this.hit(e));
  }

  /** A press that did not turn into a drag picks the lit container under it. */
  onPointerUp(e: PointerEvent): void {
    const press = this.press;
    if (!press || e.pointerId !== press.id) return;
    this.press = null;
    if (press.moved) return;
    const c = this.hit(e);
    if (c) this.pick.emit(c.ingredientId);
  }

  onPointerCancel(e: PointerEvent): void {
    if (this.press?.id === e.pointerId) this.press = null;
  }

  onPointerLeave(e: PointerEvent): void {
    (e.currentTarget as HTMLElement).classList.remove('over');
  }

  ngOnDestroy(): void {
    this.motion?.removeEventListener('change', this.onMotion);
    this.unmount();
  }

  private close(anchor: HTMLElement): void {
    if (this.focused()?.ingredientId === anchor.dataset['id']) this.focused.set(null);
  }

  /** The lit container under the pointer, if any. */
  private hit(e: MouseEvent): ContainerSpec | null {
    const host = this.host;
    if (!host || !this.scene || !host.clientWidth || !host.clientHeight) return null;
    const r = host.getBoundingClientRect();
    return this.scene.pickAt(((e.clientX - r.left) / host.clientWidth) * 2 - 1, 1 - ((e.clientY - r.top) / host.clientHeight) * 2);
  }

  private sync(host: HTMLElement | null, shelves: readonly ShelfSpec[]): void {
    if (host !== this.host) {
      this.unmount();
      if (host) void this.mount(host);
    } else {
      this.scene?.setShelves(shelves, this.matched(), this.filtering());
    }
  }

  private async mount(host: HTMLElement): Promise<void> {
    const run = ++this.generation;
    this.host = host;
    let three: Three;
    try {
      three = await this.loadThree();
    } catch {
      if (run === this.generation) this.fail();
      return;
    }
    if (run !== this.generation) return; // the page or the view changed while three was loading
    try {
      this.stage = new ThreeStage(three, host);
      this.scene = new ShelfScene(three, this.stage);
    } catch {
      this.unmount();
      this.fail();
      return;
    }
    this.scene.setShelves(this.shelves(), this.matched(), this.filtering());
    this.stage.setAnimator((dt) => this.frame(dt));
    this.ready.set(true);
  }

  private fail(): void {
    this.broken.set(true);
    this.unavailable.emit();
  }

  private unmount(): void {
    this.generation++;
    this.stage?.destroy();
    this.stage = null;
    this.scene = null;
    this.host = null;
    this.press = null;
    this.ready.set(false);
  }

  /** One frame: pulse the low fills, frame the camera, then pin every label. Loops while a lit low container pulses. */
  private frame(dt: number): boolean {
    const { scene, host } = this;
    if (!scene || !host) return false;
    const pulsing = !this.reduced && scene.lowLit;
    this.clock = pulsing ? this.clock + dt : 0;
    scene.glow(pulsing ? GLOW + GLOW_SWING * Math.sin((2 * Math.PI * this.clock) / PULSE_MS) : GLOW);
    const w = host.clientWidth;
    const h = host.clientHeight;
    scene.view(w, h);
    host.classList.toggle('pannable', scene.pannable);
    const points = scene.screenPoints(w, h);
    const resized = w !== this.placedWidth; // the tip offsets clamp to the width, so they follow it
    this.placedWidth = w;
    for (const { nativeElement: a } of this.tags()) this.place(a, points, w, false, false);
    for (const { nativeElement: a } of this.anchors()) this.place(a, points, w, true, resized);
    return pulsing;
  }

  /**
   * Moves one label to its spot; it is only written when it moved by more than half a pixel or changed depth rank.
   * A container label also gets its tip's sideways offset and flip, when it moved or the stage width changed.
   */
  private place(a: HTMLElement, points: ReadonlyMap<string, Point>, w: number, tip: boolean, resized: boolean): void {
    const p = points.get(a.dataset['id'] ?? '');
    if (!p) return;
    let last = this.labelAt.get(a);
    const fresh = !last;
    if (!last) this.labelAt.set(a, (last = { x: NaN, y: NaN, z: NaN }));
    const moved = !(Math.abs(p.x - last.x) <= 0.5 && Math.abs(p.y - last.y) <= 0.5);
    if (moved) {
      last.x = p.x;
      last.y = p.y;
      a.style.transform = `translate(${p.x.toFixed(1)}px, ${p.y.toFixed(1)}px)`;
    }
    if (tip && (moved || resized)) {
      a.style.setProperty('--dx', `${(clamp(p.x - TIP_W / 2, 8, w - TIP_W - 8) - p.x).toFixed(1)}px`);
      a.classList.toggle('below', p.y < TIP_FLIP_Y);
    }
    if (p.z !== last.z) {
      last.z = p.z;
      a.style.setProperty('--z', String(p.z));
    }
    if (fresh) a.classList.add('placed');
  }
}
