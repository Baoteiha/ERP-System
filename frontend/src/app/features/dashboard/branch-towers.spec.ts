import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpRequest, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { afterEach, describe, expect, it, vi } from 'vitest';
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

  it('shows the table with a note, and never loads three, when the device has no WebGL', () => {
    const f = start(false);
    ctrl.expectOne(isOutlets).flush([outlet('Q1', '1887000'), outlet('HN', '862000', '-5000')]);
    settle(f);
    expect(f.componentInstance.mode()).toBe('table');
    const rows = el(f).querySelectorAll('table.data tbody tr');
    expect(rows.length).toBe(2);
    expect(rows[1].textContent).toContain('Branch HN');
    expect(el(f).querySelector('p.muted')?.textContent).toContain("3D view isn't available on this device.");
    expect(el(f).querySelector('.stage')).toBeNull();
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

  it('draws the 3D stage with a focusable label per tower, and a tip on focus', () => {
    const f = start(true);
    ctrl.expectOne(isOutlets).flush([outlet('Q1', '1887000', '1887000', 16), outlet('HN', '862000', '-5000', 5)]);
    settle(f);
    expect(f.componentInstance.mode()).toBe('3d');
    expect(el(f).querySelector('.stage')?.getAttribute('aria-hidden')).toBe('true');
    expect(loader).toHaveBeenCalledTimes(1);

    const labels = [...el(f).querySelectorAll<HTMLButtonElement>('button.tower-label')];
    expect(labels.map((b) => b.textContent?.trim())).toEqual(['Q1', 'HN']);
    expect(labels[0].getAttribute('aria-label')).toMatch(/^Branch Q1: net sales 1\.887\.000\s?₫, 16 orders$/);
    expect(labels[0].closest('[aria-hidden="true"]')).toBeNull(); // labels stay in the accessibility tree

    labels[1].focus();
    settle(f);
    expect(f.componentInstance.focused()?.code).toBe('HN');
    const tip = el(f).querySelector('.tower-tip')!;
    expect(tip.textContent).toContain('Branch HN');
    expect(tip.textContent).toContain('-5.000');
    expect(tip.querySelector('a')?.getAttribute('href')).toBe('/reports');

    labels[1].dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    settle(f);
    expect(f.componentInstance.focused()).toBeNull();
    expect(el(f).querySelector('.tower-tip')).toBeNull();
  });

  it('switches to the table on request, without the no-WebGL note', () => {
    const f = start(true);
    ctrl.expectOne(isOutlets).flush([outlet('Q1', '100')]);
    settle(f);
    const toggle = [...el(f).querySelectorAll('.btn-ghost.btn-sm')].find((b) => b.textContent?.includes('View as table'));
    (toggle as HTMLButtonElement).click();
    settle(f);
    expect(f.componentInstance.mode()).toBe('table');
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
});
