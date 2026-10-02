import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpRequest, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import type { Mesh, MeshStandardMaterial, Object3D, Scene } from 'three';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { stubLocalStorage } from '../../core/test-storage';
import { ThemeService } from '../../core/theme.service';
import { FakeRenderer, stubStageGlobals, threeWithFakeRenderer } from '../../three/test-three';
import { THREE_LOADER, WEBGL_SUPPORTED } from '../../three/webgl';
import { BranchTowersComponent } from './branch-towers';

describe('BranchTowersComponent', () => {
  const outlet = (code: string, netSales: string, netMargin = netSales, orderCount = 3) => ({
    branchId: 'id-' + code, name: 'Branch ' + code, code,
    summary: {
      orderCount, grossSales: netSales, discountTotal: '0', netSales, taxTotal: '0', grandTotal: netSales,
      cogs: '0', laborCost: '0', grossMargin: netSales, netMargin,
    },
  });
  const isOutlets = (r: HttpRequest<unknown>) => r.url === '/api/v1/reports/outlets';
  let ctrl: HttpTestingController;
  let loader: ReturnType<typeof vi.fn>;

  /** Renders the panel on a device with or without WebGL; `three` loads through the given loader. */
  function start(webgl: boolean, load: () => Promise<unknown> = () => new Promise(() => undefined)) {
    loader = vi.fn(load);
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(), provideHttpClient(), provideHttpClientTesting(), provideRouter([]),
        { provide: WEBGL_SUPPORTED, useValue: () => webgl },
        { provide: THREE_LOADER, useValue: loader },
      ],
    });
    ctrl = TestBed.inject(HttpTestingController);
    const f = TestBed.createComponent(BranchTowersComponent);
    f.detectChanges();
    TestBed.tick();
    return f;
  }
  function settle(f: ComponentFixture<BranchTowersComponent>) {
    TestBed.tick();
    f.detectChanges();
  }
  const el = (f: ComponentFixture<BranchTowersComponent>) => f.nativeElement as HTMLElement;
  const viewSwitch = (f: ComponentFixture<BranchTowersComponent>) =>
    el(f).querySelector('[role="group"][aria-label="Branch performance view"]');
  const labels = (f: ComponentFixture<BranchTowersComponent>) => [...el(f).querySelectorAll<HTMLButtonElement>('button.tower-label')];
  afterEach(() => ctrl.verify());

  it('requests the outlets once on start and again, for seven days, when the period changes', () => {
    const f = start(false);
    const first = ctrl.match(isOutlets);
    expect(first.length).toBe(1);

    f.componentInstance.period.set(7);
    settle(f);
    const second = ctrl.match(isOutlets);
    expect(second.length).toBe(1);
    expect(first[0].cancelled).toBe(true); // switchMap drops the stale request
    const from = Date.parse(second[0].request.params.get('from')!);
    const to = Date.parse(second[0].request.params.get('to')!);
    expect(to - from).toBe(7 * 86_400_000);
    second[0].flush([]);
  });

  it('shows only the table and a note, and never loads three, when the device has no WebGL', () => {
    const f = start(false);
    ctrl.expectOne(isOutlets).flush([outlet('Q1', '1887000'), outlet('HN', '862000', '-5000')]);
    settle(f);
    expect(f.componentInstance.mode()).toBe('table');
    const rows = el(f).querySelectorAll('table.data tbody tr');
    expect(rows.length).toBe(2);
    expect(rows[1].textContent).toContain('Branch HN');
    expect(el(f).querySelector('p.muted')?.textContent).toContain("3D view isn't available on this device.");
    expect(el(f).querySelector('.stage')).toBeNull();
    expect(viewSwitch(f)).toBeNull();
    expect(loader).not.toHaveBeenCalled();
  });

  it('shows the empty state when no branch has figures for the period', () => {
    const f = start(false);
    ctrl.expectOne(isOutlets).flush([]);
    settle(f);
    expect(f.componentInstance.mode()).toBe('empty');
    expect(el(f).querySelector('.empty')?.textContent).toContain('No branch sales in this period');
  });

  it('shows an alert with Retry when the call fails, and Retry asks again', () => {
    const f = start(false);
    ctrl.expectOne(isOutlets).flush('x', { status: 500, statusText: 'err' });
    settle(f);
    expect(f.componentInstance.mode()).toBe('error');
    const retry = [...el(f).querySelectorAll('[role="alert"] button')].find((b) => b.textContent?.includes('Retry'));
    expect(retry).toBeTruthy();
    (retry as HTMLButtonElement).click();
    settle(f);
    expect(f.componentInstance.mode()).toBe('loading');
    ctrl.expectOne(isOutlets).flush([outlet('Q1', '100')]);
    settle(f);
    expect(f.componentInstance.mode()).toBe('table');
  });

  it('draws an aria-hidden stage that reports its loading, with labels naming the code and every figure', () => {
    const f = start(true);
    ctrl.expectOne(isOutlets).flush([outlet('Q1', '1887000', '1887000', 16), outlet('HN', '862000', '-5000', 1)]);
    settle(f);
    expect(f.componentInstance.mode()).toBe('3d');
    expect(el(f).querySelector('.stage')?.getAttribute('aria-hidden')).toBe('true');
    expect(el(f).querySelector('[role="status"]')?.textContent).toContain('Loading 3D view…');
    expect(loader).toHaveBeenCalledTimes(1);

    const [q1, hn] = labels(f);
    expect([q1.textContent?.trim(), hn.textContent?.trim()]).toEqual(['Q1', 'HN']);
    expect(q1.getAttribute('aria-label')).toMatch(/^Branch Q1 \(Q1\): net sales 1\.887\.000\s₫, 16 orders, net margin 1\.887\.000\s₫$/);
    expect(hn.getAttribute('aria-label')).toMatch(/^Branch HN \(HN\): net sales 862\.000\s₫, 1 order, net margin -5\.000\s₫ \(loss\)$/);
    expect(q1.closest('[aria-hidden="true"]')).toBeNull(); // labels stay in the accessibility tree
  });

  it('switches between 3D and the table with a pressed-state group, without the no-WebGL note', () => {
    const f = start(true);
    ctrl.expectOne(isOutlets).flush([outlet('Q1', '100')]);
    settle(f);
    const [d3, table] = [...viewSwitch(f)!.querySelectorAll('button')];
    expect([d3.textContent?.trim(), table.textContent?.trim()]).toEqual(['3D', 'Table']);
    expect([d3.getAttribute('aria-pressed'), table.getAttribute('aria-pressed')]).toEqual(['true', 'false']);

    table.click();
    settle(f);
    expect(f.componentInstance.mode()).toBe('table');
    expect([d3.getAttribute('aria-pressed'), table.getAttribute('aria-pressed')]).toEqual(['false', 'true']);
    expect(el(f).querySelectorAll('table.data tbody tr').length).toBe(1);
    expect(el(f).textContent).not.toContain("isn't available");
  });

  it('builds nothing when it is destroyed before three has loaded', async () => {
    let resolve!: (m: unknown) => void;
    const f = start(true, () => new Promise((r) => (resolve = r)));
    ctrl.expectOne(isOutlets).flush([outlet('Q1', '100')]);
    settle(f);
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

    beforeEach(() => {
      stubLocalStorage();
      frames = stubStageGlobals();
      reduceMotion = false;
      motionListeners = [];
      vi.stubGlobal('matchMedia', (query: string) => ({
        matches: query.includes('reduced-motion') ? reduceMotion : false,
        addEventListener: (_: string, fn: (e: { matches: boolean }) => void) => {
          if (query.includes('reduced-motion')) motionListeners.push(fn);
        },
        removeEventListener: vi.fn(),
      }));
      vi.spyOn(HTMLElement.prototype, 'clientWidth', 'get').mockReturnValue(800);
      vi.spyOn(HTMLElement.prototype, 'clientHeight', 'get').mockReturnValue(360);
      document.documentElement.style.setProperty('--accent', '#00ff00');
    });
    afterEach(() => {
      vi.unstubAllGlobals();
      vi.restoreAllMocks();
      document.documentElement.style.removeProperty('--accent');
    });

    /** Renders the panel with these outlets and waits until three has loaded and drawn the first frame. */
    async function mount(rows: ReturnType<typeof outlet>[], load?: () => Promise<unknown>) {
      const three = threeWithFakeRenderer();
      renderers = three.renderers;
      const f = start(true, load ?? three.load);
      ctrl.expectOne(isOutlets).flush(rows);
      settle(f);
      if (!load) await vi.waitFor(() => expect(renderers.length).toBe(1));
      settle(f);
      frames.flush(0);
      return f;
    }
    const scene = () => renderers[0].render.mock.lastCall![0] as Scene;
    const meshes = () => scene().getObjectsByProperty('isMesh', true) as Mesh[];
    const anchor = (f: ComponentFixture<BranchTowersComponent>, code: string) =>
      el(f).querySelector<HTMLElement>(`.tower-anchor[data-id="id-${code}"]`)!;
    const at = (a: HTMLElement) => {
      const m = /translate\(([-\d.]+)px, ([-\d.]+)px\)/.exec(a.style.transform);
      return m ? { x: Number(m[1]), y: Number(m[2]) } : null;
    };
    const world = () => scene().children[0] as Object3D;

    it('pins each label to its tower top after the first frame, nearer labels on top', async () => {
      const f = await mount([outlet('Q1', '1887000'), outlet('HN', '862000')]);
      const q1 = anchor(f, 'Q1');
      const hn = anchor(f, 'HN');
      expect(q1.classList.contains('placed')).toBe(true);
      expect(at(q1)!.y).toBeLessThan(at(hn)!.y); // the taller tower's label sits higher
      expect(at(q1)!.x).toBeGreaterThan(0);
      // HN stands on the camera side of the grid, so its label is drawn over Q1's
      expect(Number(hn.style.getPropertyValue('--z'))).toBeGreaterThan(Number(q1.style.getPropertyValue('--z')));
      f.destroy();
    });

    it('keeps every figure of the tip in the label name, so nothing is only visual', async () => {
      const f = await mount([outlet('Q1', '1887000', '1887000', 16), outlet('HN', '862000', '-5000', 5)]);
      const hn = labels(f)[1];
      hn.focus();
      settle(f);
      const tip = el(f).querySelector('.tower-tip')!;
      const name = hn.getAttribute('aria-label')!.replace(/\s/g, ' ');
      const figures = [tip.querySelector('b')!, ...tip.querySelectorAll('dd')].map((n) => n.textContent!.trim().replace(/\s/g, ' '));
      expect(figures.length).toBe(4);
      figures.forEach((v) => expect(name).toContain(v));
      expect(name).toContain('(loss)');
      expect(hn.hasAttribute('aria-describedby')).toBe(false);
      f.destroy();
    });

    it('returns focus to the label on Escape from View report, and toggles the tip on click, Enter and Space', async () => {
      const f = await mount([outlet('Q1', '1887000'), outlet('HN', '862000')]);
      const hn = labels(f)[1];
      hn.focus();
      settle(f);
      const link = el(f).querySelector<HTMLAnchorElement>('.tower-tip a')!;
      link.focus();
      link.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
      settle(f);
      expect(document.activeElement).toBe(hn);
      expect(f.componentInstance.focused()).toBeNull();
      expect(el(f).querySelector('.tower-tip')).toBeNull();

      hn.click(); // Enter or Space on a button is a click with detail 0
      settle(f);
      expect(f.componentInstance.focused()?.code).toBe('HN');
      hn.click();
      settle(f);
      expect(f.componentInstance.focused()).toBeNull();

      // a pointer press on a closed label opens it, a second press closes it
      const press = () => {
        hn.dispatchEvent(new MouseEvent('pointerdown', { bubbles: true }));
        hn.dispatchEvent(new MouseEvent('click', { bubbles: true, detail: 1 }));
        settle(f);
      };
      press();
      expect(f.componentInstance.focused()?.code).toBe('HN');
      press();
      expect(f.componentInstance.focused()).toBeNull();
      f.destroy();
    });

    it('leaves a label alone when its tower has not moved', async () => {
      reduceMotion = true;
      const f = await mount([outlet('Q1', '1887000'), outlet('HN', '862000')]);
      const q1 = anchor(f, 'Q1');
      q1.style.transform = 'none'; // sentinel: a rewrite would replace it
      const renders = renderers[0].render.mock.calls.length;
      TestBed.inject(ThemeService).set('light'); // recolours and asks for one more frame
      settle(f);
      frames.flush(100);
      expect(renderers[0].render.mock.calls.length).toBe(renders + 1);
      expect(q1.style.transform).toBe('none');
      f.destroy();
    });

    it('rebuilds the towers for a new period on the same stage, dropping the old meshes', async () => {
      const f = await mount([outlet('Q1', '1887000'), outlet('HN', '862000', '-5000')]);
      expect(meshes().length).toBe(5); // Q1 body + glow, HN body + glow + loss ring
      f.componentInstance.period.set(7);
      settle(f);
      ctrl.expectOne(isOutlets).flush([outlet('DN', '0')]);
      settle(f);
      frames.flush(16);
      expect(meshes().length).toBe(1); // a zero-sales slab: body only
      expect(renderers.length).toBe(1);
      expect(renderers[0].dispose).not.toHaveBeenCalled();
      f.destroy();
    });

    it('recolours the towers from the tokens when the theme switches', async () => {
      const f = await mount([outlet('Q1', '1887000'), outlet('HN', '862000')]);
      const towerMaterial = () => meshes().map((m) => m.material as MeshStandardMaterial).find((m) => m.emissiveIntensity === 0.45)!;
      expect(towerMaterial().color.getHexString()).toBe('00ff00');
      document.documentElement.style.setProperty('--accent', '#ff0000');
      TestBed.inject(ThemeService).set('light');
      settle(f);
      expect(towerMaterial().color.getHexString()).toBe('ff0000');
      expect(towerMaterial().emissive.getHexString()).toBe('ff0000');
      f.destroy();
    });

    it('destroys the stage when switching to the table and when leaving the page', async () => {
      const f = await mount([outlet('Q1', '1887000')]);
      const [d3, table] = [...viewSwitch(f)!.querySelectorAll('button')];
      table.click();
      settle(f);
      expect(renderers[0].forceContextLoss).toHaveBeenCalled();
      expect(renderers[0].dispose).toHaveBeenCalled();
      expect(el(f).querySelector('canvas')).toBeNull();

      d3.click();
      settle(f);
      await vi.waitFor(() => expect(renderers.length).toBe(2));
      expect(el(f).querySelector('canvas')).not.toBeNull();
      f.destroy();
      expect(renderers[1].dispose).toHaveBeenCalled();
    });

    it('falls back to the table with the note when three fails to load', async () => {
      const f = await mount([outlet('Q1', '1887000')], () => Promise.reject(new Error('offline')));
      await vi.waitFor(() => {
        settle(f);
        expect(f.componentInstance.mode()).toBe('table');
      });
      expect(el(f).querySelector('p.muted')?.textContent).toContain("3D view isn't available on this device.");
      expect(viewSwitch(f)).toBeNull();
      f.destroy();
    });

    it('orbits slowly on its own, but never under reduced motion', async () => {
      let f = await mount([outlet('Q1', '1887000')]);
      frames.flush(1000);
      frames.flush(2000);
      expect(world().rotation.y).toBeCloseTo(2000 * 0.00012, 5);
      f.destroy();
      TestBed.resetTestingModule();

      reduceMotion = true;
      f = await mount([outlet('Q1', '1887000')]);
      frames.flush(1000);
      frames.flush(2000);
      expect(world().rotation.y).toBe(0);
      f.destroy();
    });

    it('stops the orbit when reduced motion is turned on mid-visit', async () => {
      const f = await mount([outlet('Q1', '1887000')]);
      frames.flush(1000);
      expect(world().rotation.y).toBeGreaterThan(0);
      motionListeners.forEach((fn) => fn({ matches: true }));
      frames.flush(2000);
      const angle = world().rotation.y;
      frames.flush(3000);
      frames.flush(4000);
      expect(world().rotation.y).toBe(angle);
      f.destroy();
    });
  });
});
