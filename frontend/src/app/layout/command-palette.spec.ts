import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
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
    key('k', { ctrlKey: true });
    f.detectChanges();
    const labels = Array.from(f.nativeElement.querySelectorAll('[role="option"]')).map((li) => (li as HTMLElement).textContent);
    expect(labels.some((t) => t!.includes('Stock'))).toBe(true);
    expect(labels.some((t) => t!.includes('Orders'))).toBe(false);
  });

  it('does not open over an open dialog', () => {
    TestBed.createComponent(CommandPaletteComponent);
    TestBed.inject(OverlayStack).push({});
    key('k', { metaKey: true });
    expect(TestBed.inject(PaletteService).isOpen()).toBe(false);
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
