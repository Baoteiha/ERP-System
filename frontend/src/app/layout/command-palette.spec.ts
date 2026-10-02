import { TestBed } from '@angular/core/testing';
import { Component, provideZonelessChangeDetection } from '@angular/core';
import { provideRouter, Router } from '@angular/router';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { stubLocalStorage } from '../core/test-storage';
import { AuthService } from '../core/auth.service';
import { OverlayStack } from '../shared/overlay-stack';
import { CommandPaletteComponent, PaletteService, filterCommands, Command } from './command-palette';

const cmd = (id: string, group: Command['group'], label: string): Command => ({ id, group, label, icon: 'grid', run: () => undefined });

describe('filterCommands', () => {
  const all = [cmd('a', 'Go to', 'Purchase Orders'), cmd('b', 'Go to', 'Products'), cmd('c', 'Actions', 'New purchase order')];
  it('matches a subsequence case-insensitively and keeps group order', () => {
    expect(filterCommands(all, 'po').map((c) => c.id)).toEqual(['a', 'b', 'c']);
    expect(filterCommands(all, 'prod').map((c) => c.id)).toEqual(['b']);
  });
  it('returns nothing for a query that matches no label', () => {
    expect(filterCommands(all, 'zzz')).toEqual([]);
  });
});

describe('CommandPaletteComponent', () => {
  const key = (k: string, mods: Partial<KeyboardEventInit> = {}) =>
    document.dispatchEvent(new KeyboardEvent('keydown', { key: k, bubbles: true, ...mods }));
  const mac = /Mac|iPhone|iPad/.test(navigator.platform || navigator.userAgent);
  const mod: Partial<KeyboardEventInit> = mac ? { metaKey: true } : { ctrlKey: true };
  const wrongMod: Partial<KeyboardEventInit> = mac ? { ctrlKey: true } : { metaKey: true };
  const labels = (f: { nativeElement: HTMLElement }) =>
    Array.from(f.nativeElement.querySelectorAll('[role="option"]')).map((li) => (li as HTMLElement).textContent ?? '');
  const setMe = (permissions: string[], branchIds = ['b1']) => {
    const auth = TestBed.inject(AuthService);
    const byBranch: Record<string, string[]> = {};
    for (const b of branchIds) byBranch[b] = permissions;
    auth.me.set({ userId: 'u', email: 'a@b.c', companyId: 'co', branchIds, roles: [], permissions, permissionsByBranch: byBranch });
  };
  const type = (f: { nativeElement: HTMLElement; detectChanges(): void }, value: string) => {
    const input = f.nativeElement.querySelector('.palette-input') as HTMLInputElement;
    input.value = value;
    input.dispatchEvent(new Event('input'));
    f.detectChanges();
  };

  beforeEach(() => {
    stubLocalStorage();
    TestBed.configureTestingModule({
      providers: [provideZonelessChangeDetection(), provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    TestBed.inject(AuthService).me.set({
      userId: 'u', email: 'a@b.c', companyId: 'co', branchIds: ['b1'], roles: [],
      permissions: ['inventory:read'], permissionsByBranch: { b1: ['inventory:read'] },
    });
    TestBed.inject(AuthService).setActiveBranch('b1');
  });

  it('opens on Ctrl+K and lists only screens the user may open', () => {
    const f = TestBed.createComponent(CommandPaletteComponent);
    f.detectChanges();
    key('k', mod);
    f.detectChanges();
    const labels = Array.from(f.nativeElement.querySelectorAll('[role="option"]')).map((li) => (li as HTMLElement).textContent);
    expect(labels.some((t) => t!.includes('Stock'))).toBe(true);
    expect(labels.some((t) => t!.includes('Orders'))).toBe(false);
  });

  it('does not open over an open dialog', () => {
    TestBed.createComponent(CommandPaletteComponent);
    TestBed.inject(OverlayStack).push({});
    key('k', mod);
    expect(TestBed.inject(PaletteService).isOpen()).toBe(false);
  });

  it('opens on the same keypress when no dialog is up, and ignores the other platform modifier', () => {
    TestBed.createComponent(CommandPaletteComponent);
    key('k', wrongMod);
    expect(TestBed.inject(PaletteService).isOpen()).toBe(false);
    key('k', mod);
    expect(TestBed.inject(PaletteService).isOpen()).toBe(true);
  });

  it('refuses a programmatic open over a dialog', () => {
    TestBed.inject(OverlayStack).push({});
    TestBed.inject(PaletteService).open();
    expect(TestBed.inject(PaletteService).isOpen()).toBe(false);
  });

  it('focuses the search field whenever it opens, however it opened', () => {
    const f = TestBed.createComponent(CommandPaletteComponent);
    f.detectChanges();
    TestBed.inject(PaletteService).open();
    f.detectChanges();
    expect(document.activeElement).toBe(f.nativeElement.querySelector('.palette-input'));
  });

  it('a backdrop click clears the query and returns focus to the opener', () => {
    const opener = document.createElement('button');
    document.body.appendChild(opener);
    opener.focus();
    const f = TestBed.createComponent(CommandPaletteComponent);
    f.detectChanges();
    TestBed.inject(PaletteService).open();
    f.detectChanges();
    type(f, 'stock');
    (f.nativeElement.querySelector('.palette-backdrop') as HTMLElement).click();
    f.detectChanges();
    expect(TestBed.inject(PaletteService).isOpen()).toBe(false);
    expect(document.activeElement).toBe(opener);
    TestBed.inject(PaletteService).open();
    f.detectChanges();
    expect((f.nativeElement.querySelector('.palette-input') as HTMLInputElement).value).toBe('');
    opener.remove();
  });

  it('puts focus on the page when the opener vanished while the palette was open', () => {
    const opener = document.createElement('button');
    document.body.appendChild(opener);
    opener.focus();
    const f = TestBed.createComponent(CommandPaletteComponent);
    f.detectChanges();
    TestBed.inject(PaletteService).open();
    f.detectChanges();
    const page = document.createElement('main');
    page.id = 'main-content';
    page.tabIndex = -1;
    document.body.appendChild(page);
    opener.remove();
    TestBed.inject(PaletteService).close();
    f.detectChanges();
    expect(document.activeElement).toBe(page);
    page.remove();
  });

  it('ignores keys while an IME is composing', () => {
    const f = TestBed.createComponent(CommandPaletteComponent);
    const nav = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
    TestBed.inject(PaletteService).open();
    f.detectChanges();
    key('Enter', { isComposing: true });
    expect(nav).not.toHaveBeenCalled();
    expect(TestBed.inject(PaletteService).isOpen()).toBe(true);
  });

  it('ArrowUp from the first row wraps to the last and scrolls it into view', () => {
    const scroll = vi.fn();
    HTMLElement.prototype.scrollIntoView = scroll;
    const f = TestBed.createComponent(CommandPaletteComponent);
    TestBed.inject(PaletteService).open();
    f.detectChanges();
    key('ArrowUp');
    f.detectChanges();
    const rows = Array.from(f.nativeElement.querySelectorAll('[role="option"]')) as HTMLElement[];
    expect(rows[rows.length - 1].getAttribute('aria-selected')).toBe('true');
    expect(rows[rows.length - 1].textContent).toContain('Sign out');
    expect(scroll).toHaveBeenCalledWith({ block: 'nearest' });
  });

  it('lists write actions only with the permission', () => {
    const f = TestBed.createComponent(CommandPaletteComponent);
    TestBed.inject(PaletteService).open();
    f.detectChanges();
    expect(labels(f).some((t) => t.includes('Adjust stock'))).toBe(false);
    setMe(['inventory:read', 'inventory:write', 'purchasing:write', 'sales:write']);
    f.detectChanges();
    const l = labels(f);
    expect(l.some((t) => t.includes('Adjust stock'))).toBe(true);
    expect(l.some((t) => t.includes('New purchase order'))).toBe(true);
    expect(l.some((t) => t.includes('New order'))).toBe(true);
  });

  it('announces the result count politely', () => {
    const f = TestBed.createComponent(CommandPaletteComponent);
    TestBed.inject(PaletteService).open();
    f.detectChanges();
    type(f, 'qqqq');
    expect(f.nativeElement.querySelector('[role="status"]')?.textContent).toContain('No matches');
  });

  it('keeps the options inside role=group wrappers', () => {
    const f = TestBed.createComponent(CommandPaletteComponent);
    TestBed.inject(PaletteService).open();
    f.detectChanges();
    expect(f.nativeElement.querySelector('[role="group"] [role="option"]')).not.toBeNull();
  });

  describe('recents', () => {
    const open = () => {
      const f = TestBed.createComponent(CommandPaletteComponent);
      TestBed.inject(PaletteService).open();
      f.detectChanges();
      return f;
    };
    const recentLabels = (f: { nativeElement: HTMLElement }) =>
      Array.from(f.nativeElement.querySelectorAll('[role="group"]'))
        .filter((g) => g.textContent!.trim().startsWith('Recent'))
        .flatMap((g) => Array.from(g.querySelectorAll('[role="option"]')).map((o) => o.textContent ?? ''));

    it('caps at 4, drops paths the user may not open, and shows only for an empty query', () => {
      localStorage.setItem('erpcafe.recent', JSON.stringify(['/inventory/stock', '/orders', '/inventory/items', '/inventory/ingredients', '/inventory/suppliers', '/']));
      setMe(['inventory:read']);
      const f = open();
      const r = recentLabels(f);
      expect(r.length).toBe(4);
      expect(r.some((t) => t.includes('Orders'))).toBe(false);
      type(f, 'stock');
      expect(recentLabels(f)).toEqual([]);
    });

    it('survives storage that throws', () => {
      vi.stubGlobal('localStorage', { getItem: () => { throw new Error('blocked'); }, setItem: () => { throw new Error('blocked'); } });
      const f = open();
      expect(recentLabels(f)).toEqual([]);
      expect(labels(f).length).toBeGreaterThan(0);
    });
  });

  describe('Switch branch', () => {
    @Component({ template: '' })
    class Blank {}

    it('is not offered for the active branch, and leaves a page the new branch closes', async () => {
      TestBed.resetTestingModule();
      stubLocalStorage();
      TestBed.configureTestingModule({
        providers: [provideZonelessChangeDetection(), provideRouter([{ path: 'reports', component: Blank }, { path: '', component: Blank }]), provideHttpClient(), provideHttpClientTesting()],
      });
      const auth = TestBed.inject(AuthService);
      auth.me.set({
        userId: 'u', email: 'a@b.c', companyId: 'co', branchIds: ['b1', 'b2'], roles: [],
        permissions: ['report:read'], permissionsByBranch: { b1: ['report:read'], b2: [] },
      });
      auth.setActiveBranch('b1');
      const router = TestBed.inject(Router);
      await router.navigateByUrl('/reports');
      const f = TestBed.createComponent(CommandPaletteComponent);
      f.componentRef.setInput('branches', [
        { id: 'b1', companyId: 'co', name: 'Main', code: 'M', active: true },
        { id: 'b2', companyId: 'co', name: 'Annex', code: 'A', active: true },
      ]);
      TestBed.inject(PaletteService).open();
      f.detectChanges();
      expect(labels(f).some((t) => t.includes('Switch branch: Main'))).toBe(false);
      const row = (Array.from(f.nativeElement.querySelectorAll('[role="option"]')) as HTMLElement[]).find((o) => o.textContent!.includes('Switch branch: Annex'))!;
      row.click();
      await new Promise((resolve) => setTimeout(resolve));
      expect(auth.activeBranchId()).toBe('b2');
      expect(router.url).toBe('/');
    });
  });

  it('closes on Esc only when it is the top overlay', () => {
    TestBed.createComponent(CommandPaletteComponent);
    const palette = TestBed.inject(PaletteService);
    const stack = TestBed.inject(OverlayStack);
    palette.open();
    const dialog = {};
    stack.push(dialog);
    key('Escape');
    expect(palette.isOpen()).toBe(true);
    stack.remove(dialog);
    key('Escape');
    expect(palette.isOpen()).toBe(false);
    expect(stack.size).toBe(0);
  });

  it('runs the active command on Enter and closes', () => {
    const f = TestBed.createComponent(CommandPaletteComponent);
    const router = TestBed.inject(Router);
    const nav = vi.spyOn(router, 'navigateByUrl').mockResolvedValue(true);
    TestBed.inject(PaletteService).open();
    f.detectChanges();
    const input = f.nativeElement.querySelector('.palette-input') as HTMLInputElement;
    input.value = 'stock';
    input.dispatchEvent(new Event('input'));
    f.detectChanges();
    key('Enter');
    expect(nav).toHaveBeenCalledWith('/inventory/stock');
    expect(TestBed.inject(PaletteService).isOpen()).toBe(false);
  });

  it('shows a no-results row', () => {
    const f = TestBed.createComponent(CommandPaletteComponent);
    TestBed.inject(PaletteService).open();
    f.detectChanges();
    const input = f.nativeElement.querySelector('.palette-input') as HTMLInputElement;
    input.value = 'qqqq';
    input.dispatchEvent(new Event('input'));
    f.detectChanges();
    expect(f.nativeElement.querySelector('.palette-empty')?.textContent).toContain('qqqq');
  });
});
