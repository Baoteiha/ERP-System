import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { CylinderGeometry, InstancedMesh, Matrix4, Mesh, MeshPhysicalMaterial, MeshStandardMaterial, Quaternion, Scene, Vector3 } from 'three';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { IngredientResponse, StockItemResponse } from '../../core/models';
import { stubLocalStorage } from '../../core/test-storage';
import { ThemeService } from '../../core/theme.service';
import { FakeRenderer, stubStageGlobals, threeWithFakeRenderer } from '../../three/test-three';
import { THREE_LOADER, WEBGL_SUPPORTED } from '../../three/webgl';
import { StockShelvesComponent } from './stock-shelves';

const ing = (id: string, name: string, category?: string, baseUnit = 'ml'): IngredientResponse =>
  ({ id, companyId: 'c', name, baseUnit, category, active: true, version: 0 });
const row = (id: string, onHand: number, reorder: number, below = false, cost = '1500'): StockItemResponse => ({
  ingredientId: id, branchId: 'b', quantityOnHand: String(onHand), reorderLevel: String(reorder),
  avgUnitCost: { amount: cost, currency: 'VND' }, belowReorderLevel: below,
});
// Coffee: Beans (out). Dairy: Cream (low), Milk (ok). Syrups: Sugar syrup (no reorder level).
const INGREDIENTS = [ing('milk', 'Milk', 'Dairy'), ing('cream', 'Cream', 'Dairy'), ing('beans', 'Beans', 'Coffee', 'g'), ing('sugar', 'Sugar syrup', 'Syrups')];
const STOCK = [row('milk', 5000, 2000), row('cream', 500, 1000, true), row('beans', 0, 300, true), row('sugar', 800, 0)];

describe('StockShelvesComponent', () => {
  let loader: ReturnType<typeof vi.fn>;
  let picks: string[];
  let unavailable: number;

  /** Renders the shelves on a device with or without WebGL; `three` loads through the given loader. */
  function start(webgl: boolean, load: () => Promise<unknown> = () => new Promise(() => undefined),
    stock = STOCK, ingredients = INGREDIENTS) {
    loader = vi.fn(load);
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        { provide: WEBGL_SUPPORTED, useValue: () => webgl },
        { provide: THREE_LOADER, useValue: loader },
      ],
    });
    const f = TestBed.createComponent(StockShelvesComponent);
    f.componentRef.setInput('stock', stock);
    f.componentRef.setInput('ingredients', ingredients);
    picks = [];
    unavailable = 0;
    f.componentInstance.pick.subscribe((id) => picks.push(id));
    f.componentInstance.unavailable.subscribe(() => unavailable++);
    f.detectChanges();
    TestBed.tick();
    return f;
  }
  function settle(f: ComponentFixture<StockShelvesComponent>) {
    TestBed.tick();
    f.detectChanges();
  }
  const el = (f: ComponentFixture<StockShelvesComponent>) => f.nativeElement as HTMLElement;
  const labels = (f: ComponentFixture<StockShelvesComponent>) => [...el(f).querySelectorAll<HTMLButtonElement>('button.shelf-label')];
  const label = (f: ComponentFixture<StockShelvesComponent>, name: string) => labels(f).find((b) => b.textContent?.trim() === name)!;
  const pills = (f: ComponentFixture<StockShelvesComponent>) =>
    [...el(f).querySelectorAll<HTMLButtonElement>('[role="group"][aria-label="Shelf category"] button.pill')];
  const text = (n: Element) => n.textContent!.trim().replace(/\s+/g, ' ');

  it('shows only a note, and never loads three, when the device has no WebGL', () => {
    const f = start(false);
    expect(el(f).querySelector('p.muted')?.textContent).toContain("3D view isn't available on this device.");
    expect(el(f).querySelector('.stage')).toBeNull();
    expect(labels(f).length).toBe(0);
    expect(loader).not.toHaveBeenCalled();
  });

  it('groups the rows into one shelf per category, with a pill per shelf and a labelled search', () => {
    const f = start(true);
    const shelves = f.componentInstance.shelves();
    expect(shelves.map((s) => s.category)).toEqual(['Coffee', 'Dairy', 'Syrups']);
    expect(shelves[1].containers.map((c) => c.ingredientId)).toEqual(['cream', 'milk']);
    expect(pills(f).map(text)).toEqual(['All', 'Coffee', 'Dairy', 'Syrups']);
    expect(pills(f)[0].getAttribute('aria-pressed')).toBe('true');
    expect(el(f).querySelector('label[for="shelf-search"]')).not.toBeNull();
    expect(el(f).querySelector('input#shelf-search.input')).not.toBeNull();
    expect(labels(f).map(text)).toEqual(['Beans', 'Cream', 'Milk', 'Sugar syrup']); // shelf by shelf, in reading order
    expect(el(f).querySelector('.stage')?.getAttribute('aria-hidden')).toBe('true');
    expect(el(f).querySelector('[role="status"].wait')?.textContent).toContain('Loading 3D view…');
    expect(loader).toHaveBeenCalledTimes(1);
  });

  it('disables and skips the labels a category and a search leave out, and says how many match', () => {
    const f = start(true);
    f.componentInstance.category.set('Dairy');
    f.componentInstance.search.set('mil');
    settle(f);
    expect(label(f, 'Milk').hasAttribute('aria-disabled')).toBe(false);
    for (const name of ['Beans', 'Cream', 'Sugar syrup']) {
      expect(label(f, name).getAttribute('aria-disabled')).toBe('true');
      expect(label(f, name).getAttribute('tabindex')).toBe('-1');
    }
    expect(pills(f).find((p) => text(p) === 'Dairy')!.getAttribute('aria-pressed')).toBe('true');
    expect(text(el(f).querySelector('.match-count[role="status"]')!)).toBe('1 of 4 match');
  });

  it('filters by typing in the search box, ignoring case and Vietnamese accents', () => {
    const named = [ing('a', 'Sữa tươi', 'Dairy'), ing('b', 'Đường nâu', 'Dry'), ing('c', 'Trà đen', 'Tea')];
    const f = start(true, undefined, [row('a', 1, 1), row('b', 1, 1), row('c', 1, 1)], named);
    const input = el(f).querySelector<HTMLInputElement>('#shelf-search')!;
    input.value = 'SUA';
    input.dispatchEvent(new Event('input'));
    settle(f);
    expect(f.componentInstance.search()).toBe('SUA');
    expect(labels(f).filter((b) => !b.hasAttribute('aria-disabled')).map(text)).toEqual(['Sữa tươi']);
    f.componentInstance.search.set('duong');
    settle(f);
    expect(labels(f).filter((b) => !b.hasAttribute('aria-disabled')).map(text)).toEqual(['Đường nâu']);
  });

  it('falls back to every shelf when the chosen category is gone after a reload', () => {
    const f = start(true);
    f.componentInstance.category.set('Syrups');
    settle(f);
    f.componentRef.setInput('stock', [row('milk', 1, 1)]);
    settle(f);
    expect(labels(f).every((b) => !b.hasAttribute('aria-disabled'))).toBe(true);
    expect(pills(f).map(text)).toEqual(['All', 'Dairy']);
    expect(pills(f)[0].getAttribute('aria-pressed')).toBe('true');
  });

  it('emits pick with the ingredient id on a click (or Enter) on a label, but not on a dimmed one', () => {
    const f = start(true);
    label(f, 'Milk').click();
    expect(picks).toEqual(['milk']);
    f.componentInstance.search.set('mil');
    settle(f);
    label(f, 'Cream').click();
    expect(picks).toEqual(['milk']);
  });

  it('names each label with every figure and status word its tip shows', () => {
    const f = start(true);
    expect(label(f, 'Cream').getAttribute('aria-label')!.replace(/\s/g, ' '))
      .toBe('Cream: on hand 500 ml, reorder level 1.000 ml, average cost 1.500 ₫, below reorder');
    for (const name of ['Beans', 'Cream', 'Milk', 'Sugar syrup']) {
      const b = label(f, name);
      b.focus();
      settle(f);
      const tip = el(f).querySelector('.shelf-tip')!;
      expect(tip.getAttribute('aria-hidden')).toBe('true'); // the name already says it all
      const said = b.getAttribute('aria-label')!.replace(/\s/g, ' ').toLowerCase();
      const shown = [tip.querySelector('b')!, ...tip.querySelectorAll('dd, p')].map((n) => text(n).replace(/\s/g, ' ').toLowerCase());
      expect(shown.length).toBeGreaterThanOrEqual(5);
      shown.forEach((v) => expect(said).toContain(v));
      expect(b.hasAttribute('aria-describedby')).toBe(false);
    }
    expect(label(f, 'Beans').getAttribute('aria-label')).toContain('out of stock');
    expect(label(f, 'Sugar syrup').getAttribute('aria-label')).toContain('no reorder level set');
  });

  it('builds nothing when it is destroyed before three has loaded', async () => {
    let resolve!: (m: unknown) => void;
    const f = start(true, () => new Promise((r) => (resolve = r)));
    expect(loader).toHaveBeenCalledTimes(1);
    const WebGLRenderer = vi.fn();
    f.destroy();
    resolve({ WebGLRenderer });
    await Promise.resolve();
    await Promise.resolve();
    expect(WebGLRenderer).not.toHaveBeenCalled();
  });

  /** The real three module builds the scene; only the renderer is a stand-in. */
  describe('with the scene mounted', () => {
    let frames: ReturnType<typeof stubStageGlobals>;
    let renderers: FakeRenderer[];
    let reduceMotion: boolean;
    let motionListeners: ((e: { matches: boolean }) => void)[];
    let motionRemoved: unknown[];

    beforeEach(() => {
      stubLocalStorage();
      frames = stubStageGlobals();
      reduceMotion = false;
      motionListeners = [];
      motionRemoved = [];
      vi.stubGlobal('matchMedia', (query: string) => ({
        matches: query.includes('reduced-motion') ? reduceMotion : false,
        addEventListener: (_: string, fn: (e: { matches: boolean }) => void) => {
          if (query.includes('reduced-motion')) motionListeners.push(fn);
        },
        removeEventListener: (_: string, fn: unknown) => motionRemoved.push(fn),
      }));
      vi.spyOn(HTMLElement.prototype, 'clientWidth', 'get').mockReturnValue(800);
      vi.spyOn(HTMLElement.prototype, 'clientHeight', 'get').mockReturnValue(360);
      for (const [token, value] of [['--accent', '#00ff00'], ['--amber', '#ffaa00'], ['--red', '#ff0000'], ['--surface-3', '#333333']]) {
        document.documentElement.style.setProperty(token, value);
      }
    });
    afterEach(() => {
      vi.unstubAllGlobals();
      vi.restoreAllMocks();
      for (const token of ['--accent', '--amber', '--red', '--surface-3']) document.documentElement.style.removeProperty(token);
    });

    /** Renders the shelves and waits until three has loaded and drawn the first frame. */
    async function mount(stock = STOCK, ingredients = INGREDIENTS, load?: () => Promise<unknown>) {
      const three = threeWithFakeRenderer();
      renderers = three.renderers;
      const f = start(true, load ?? three.load, stock, ingredients);
      if (!load) await vi.waitFor(() => expect(renderers.length).toBe(1));
      settle(f);
      frames.flush(0);
      return f;
    }
    const scene = () => renderers[0].render.mock.lastCall![0] as Scene;
    const batch = (name: string) => scene().getObjectByName(name) as InstancedMesh;
    const planks = () => scene().getObjectByName('planks')!.children as Mesh[];
    const scaleAt = (m: InstancedMesh, i: number) => {
      const matrix = new Matrix4();
      m.getMatrixAt(i, matrix);
      const s = new Vector3();
      matrix.decompose(new Vector3(), new Quaternion(), s);
      return s;
    };
    const anchor = (f: ComponentFixture<StockShelvesComponent>, id: string) =>
      el(f).querySelector<HTMLElement>(`.shelf-anchor[data-id="${id}"]`)!;
    const at = (a: HTMLElement) => {
      const m = /translate\(([-\d.]+)px, ([-\d.]+)px\)/.exec(a.style.transform);
      return m ? { x: Number(m[1]), y: Number(m[2]) } : null;
    };
    const press = (f: ComponentFixture<StockShelvesComponent>, type: string, x: number, y: number) =>
      el(f).querySelector('.stage')!.dispatchEvent(new MouseEvent(type, { bubbles: true, clientX: x, clientY: y }));

    it('lays one plank per shelf and draws the containers as instanced glass shells and level fills', async () => {
      const f = await mount();
      expect(planks().map((p) => Number(p.position.y.toFixed(3)))).toEqual([0, -2.4, -4.8]);
      expect(planks().every((p) => (p.material as MeshStandardMaterial).color.getHexString() === '333333')).toBe(true);

      const shells = batch('shells');
      expect(shells).toBeInstanceOf(InstancedMesh);
      expect(shells.count).toBe(4);
      const glass = shells.material as MeshPhysicalMaterial;
      expect(glass).toBeInstanceOf(MeshPhysicalMaterial);
      expect([glass.transparent, glass.opacity, glass.roughness]).toEqual([true, 0.18, 0.1]);
      const g = (shells.geometry as CylinderGeometry).parameters;
      expect([g.radiusTop, g.radiusBottom, g.height, g.radialSegments]).toEqual([0.45, 0.45, 1.4, 24]);

      expect([batch('fill-ok').count, batch('fill-low').count, batch('fill-empty').count]).toEqual([2, 1, 1]);
      expect((batch('fill-ok').material as MeshStandardMaterial).color.getHexString()).toBe('00ff00');
      expect((batch('fill-low').material as MeshStandardMaterial).color.getHexString()).toBe('ffaa00');
      expect((batch('fill-empty').material as MeshStandardMaterial).color.getHexString()).toBe('ff0000');
      // Cream: 500 / (3 × 1000) full; Beans: nothing on hand, so the thinnest sliver
      const cream = scaleAt(batch('fill-low'), 0);
      expect([cream.x, cream.y, cream.z].map((v) => Number(v.toFixed(4)))).toEqual([0.4, Number(((500 / 3000) * 1.36).toFixed(4)), 0.4]);
      expect(Number(scaleAt(batch('fill-empty'), 0).y.toFixed(4))).toBe(Number((0.02 * 1.36).toFixed(4)));
      f.destroy();
    });

    it('dims what the filter leaves out to a quarter, and lifts the matches with a white glow', async () => {
      const f = await mount();
      const okFill = () => batch('fill-ok').material as MeshStandardMaterial;
      expect(okFill().emissive.getHexString()).toBe('00ff00');
      f.componentInstance.category.set('Dairy');
      f.componentInstance.search.set('mil');
      settle(f);
      frames.flush(16);
      expect([batch('shells').count, batch('shells-dim').count]).toEqual([1, 3]);
      expect([batch('fill-ok').count, batch('fill-ok-dim').count]).toEqual([1, 1]);
      expect([batch('fill-low').count, batch('fill-low-dim').count]).toEqual([0, 1]);
      const dim = batch('fill-low-dim').material as MeshStandardMaterial;
      expect([dim.transparent, dim.opacity]).toEqual([true, 0.25]);
      expect(okFill().emissive.getHexString()).not.toBe('00ff00'); // whiter than the fill
      expect((batch('shells').material as MeshPhysicalMaterial).emissiveIntensity).toBeGreaterThan(0);

      f.componentInstance.search.set('');
      f.componentInstance.category.set(null);
      settle(f);
      expect([batch('shells').count, batch('shells-dim').count]).toEqual([4, 0]);
      expect(okFill().emissive.getHexString()).toBe('00ff00');
      f.destroy();
    });

    it('rebuilds the containers on the same stage when the stock changes, freeing the old instances', async () => {
      const f = await mount();
      const old = [batch('shells'), batch('fill-ok')];
      const freed = vi.fn();
      old.forEach((m) => m.addEventListener('dispose', freed));
      f.componentRef.setInput('stock', [row('milk', 5000, 2000), row('beans', 900, 300)]);
      settle(f);
      frames.flush(16);
      expect(freed).toHaveBeenCalledTimes(2);
      expect(batch('shells').count).toBe(2);
      expect([batch('fill-ok').count, batch('fill-low').count, batch('fill-empty').count]).toEqual([2, 0, 0]);
      expect(planks().length).toBe(2);
      expect(renderers.length).toBe(1);
      expect(renderers[0].dispose).not.toHaveBeenCalled();
      expect(anchor(f, 'cream')).toBeNull();
      f.destroy();
    });

    it('pins each label above its container and each category tag beside its plank', async () => {
      const f = await mount();
      const [beans, cream, milk] = ['beans', 'cream', 'milk'].map((id) => anchor(f, id));
      expect([beans, cream, milk].every((a) => a.classList.contains('placed'))).toBe(true);
      expect(at(cream)!.x).toBeLessThan(at(milk)!.x); // slots run left to right
      expect(at(cream)!.y).toBeCloseTo(at(milk)!.y, 0); // on the same shelf
      expect(at(beans)!.y).toBeLessThan(at(cream)!.y); // the first shelf is on top
      const tag = anchor(f, 'shelf:1');
      expect(tag.textContent?.trim()).toBe('Dairy');
      expect(at(tag)!.x).toBeLessThan(at(cream)!.x);
      expect(tag.querySelector('[aria-hidden="true"]')).not.toBeNull();
      expect(milk.closest('[role="group"]')?.getAttribute('aria-label')).toBe('Dairy shelf');
      f.destroy();
    });

    it('leaves a label alone when its container has not moved', async () => {
      reduceMotion = true;
      const f = await mount();
      const milk = anchor(f, 'milk');
      milk.style.transform = 'none'; // sentinel: a rewrite would replace it
      const renders = renderers[0].render.mock.calls.length;
      TestBed.inject(ThemeService).set('light'); // recolours and asks for one more frame
      settle(f);
      frames.flush(100);
      expect(renderers[0].render.mock.calls.length).toBe(renders + 1);
      expect(milk.style.transform).toBe('none');
      f.destroy();
    });

    it('recolours the fills from the tokens when the theme switches', async () => {
      const f = await mount();
      document.documentElement.style.setProperty('--amber', '#123456');
      TestBed.inject(ThemeService).set('light');
      settle(f);
      expect((batch('fill-low').material as MeshStandardMaterial).color.getHexString()).toBe('123456');
      expect((batch('fill-low-dim').material as MeshStandardMaterial).color.getHexString()).toBe('123456');
      f.destroy();
    });

    it('pulses the low fills between .3 and .8 every 1.6 s, but never under reduced motion', async () => {
      let f = await mount();
      const glow = () => (batch('fill-low').material as MeshStandardMaterial).emissiveIntensity;
      expect(glow()).toBeCloseTo(0.55, 5);
      frames.flush(400); // a quarter period: the top of the pulse
      expect(glow()).toBeCloseTo(0.8, 5);
      frames.flush(1200); // three quarters: the bottom
      expect(glow()).toBeCloseTo(0.3, 5);
      f.destroy();
      TestBed.resetTestingModule();

      reduceMotion = true;
      f = await mount();
      frames.flush(400);
      frames.flush(1200);
      expect(glow()).toBeCloseTo(0.55, 5);
      f.destroy();
    });

    it('stops the pulse when reduced motion is turned on mid-visit, and stops listening on destroy', async () => {
      const f = await mount();
      const glow = () => (batch('fill-low').material as MeshStandardMaterial).emissiveIntensity;
      frames.flush(400);
      expect(glow()).toBeCloseTo(0.8, 5);
      motionListeners.forEach((fn) => fn({ matches: true }));
      frames.flush(500);
      expect(glow()).toBeCloseTo(0.55, 5);
      const renders = renderers[0].render.mock.calls.length;
      frames.flush(900);
      expect(renderers[0].render.mock.calls.length).toBe(renders); // the loop has stopped
      f.destroy();
      expect(motionRemoved).toEqual(motionListeners);
    });

    it('shows the tip on focus, hides it on Escape with focus kept on the label, and picks on Enter', async () => {
      const f = await mount();
      const milk = label(f, 'Milk');
      milk.focus();
      settle(f);
      expect(el(f).querySelector('.shelf-tip b')?.textContent).toBe('Milk');
      milk.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
      settle(f);
      expect(el(f).querySelector('.shelf-tip')).toBeNull();
      expect(document.activeElement).toBe(milk);
      milk.click(); // Enter on a button is a click with detail 0
      expect(picks).toEqual(['milk']);
      f.destroy();
    });

    it('picks the container under a click on the canvas, but not a dimmed one or the end of a drag', async () => {
      const f = await mount();
      const jar = (id: string) => { const p = at(anchor(f, id))!; return { x: p.x, y: p.y + 25 }; }; // just below the rim
      const milk = jar('milk');
      press(f, 'pointerdown', milk.x, milk.y);
      press(f, 'pointerup', milk.x, milk.y);
      expect(picks).toEqual(['milk']);

      press(f, 'pointerdown', milk.x, milk.y);
      press(f, 'pointermove', milk.x + 30, milk.y);
      press(f, 'pointerup', milk.x + 30, milk.y);
      expect(picks).toEqual(['milk']);

      f.componentInstance.search.set('mil');
      settle(f);
      const cream = jar('cream');
      press(f, 'pointerdown', cream.x, cream.y);
      press(f, 'pointerup', cream.x, cream.y);
      expect(picks).toEqual(['milk']);
      f.destroy();
    });

    it('starts at the left end of a long shelf, pans with a sideways drag within its ends, and brings a focused label into view', async () => {
      const many = Array.from({ length: 20 }, (_, i) => ing(`i${i}`, `Item ${String(i).padStart(2, '0')}`, 'Dry'));
      const f = await mount(many.map((i) => row(i.id, 10, 1)), many);
      const first = () => at(anchor(f, 'i0'))!.x;
      const start = first();
      expect(start).toBeGreaterThan(0);
      expect(start).toBeLessThan(200); // the shelf's start is in frame
      expect(at(anchor(f, 'i19'))!.x).toBeGreaterThan(800); // its end is off to the right
      expect(el(f).querySelector('.stage')!.classList.contains('pannable')).toBe(true);

      press(f, 'pointerdown', 400, 100);
      press(f, 'pointermove', 250, 100);
      frames.flush(16);
      expect(first()).toBeCloseTo(start - 150, 0); // the shelf follows the pointer
      press(f, 'pointermove', 1400, 100); // past the left end
      frames.flush(32);
      expect(first()).toBeCloseTo(start, 0);
      press(f, 'pointerup', 1400, 100);
      expect(picks).toEqual([]);

      label(f, 'Item 19').focus();
      settle(f);
      frames.flush(48);
      const last = at(anchor(f, 'i19'))!.x;
      expect(last).toBeGreaterThan(0);
      expect(last).toBeLessThan(800);
      f.destroy();
    });

    it('centres a rack that fits and does not pan it', async () => {
      const f = await mount();
      const stage = el(f).querySelector('.stage')!;
      expect(stage.classList.contains('pannable')).toBe(false);
      const before = at(anchor(f, 'milk'))!.x;
      press(f, 'pointerdown', 400, 100);
      press(f, 'pointermove', 200, 100);
      frames.flush(16);
      expect(at(anchor(f, 'milk'))!.x).toBeCloseTo(before, 0);
      f.destroy();
    });

    it('destroys the stage when the page is left', async () => {
      const f = await mount();
      expect(el(f).querySelector('canvas')).not.toBeNull();
      f.destroy();
      expect(renderers[0].forceContextLoss).toHaveBeenCalled();
      expect(renderers[0].dispose).toHaveBeenCalled();
    });

    it('shows the note and tells the page when three fails to load', async () => {
      const f = await mount(STOCK, INGREDIENTS, () => Promise.reject(new Error('offline')));
      await vi.waitFor(() => {
        settle(f);
        expect(el(f).querySelector('p.muted')?.textContent).toContain("3D view isn't available on this device.");
      });
      expect(unavailable).toBe(1);
      expect(el(f).querySelector('.stage')).toBeNull();
      f.destroy();
    });
  });
});
