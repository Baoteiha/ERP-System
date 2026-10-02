import { stubLocalStorage } from '../core/test-storage';
import { TestBed } from '@angular/core/testing';
import { Component, provideZonelessChangeDetection } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Router, provideRouter } from '@angular/router';
import { AuthService } from '../core/auth.service';
import { ShellComponent } from './shell';

@Component({ template: '' })
class BlankComponent {}

describe('ShellComponent branch switch', () => {
  let router: Router;
  let shell: ShellComponent;

  beforeEach(async () => {
    stubLocalStorage();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([{ path: 'reports', component: BlankComponent }, { path: '', component: BlankComponent }]),
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    });
    const auth = TestBed.inject(AuthService);
    // reads reports at b1 only
    auth.me.set({
      userId: 'u', email: 'a@b.c', companyId: 'co', branchIds: ['b1', 'b2', 'b3'], roles: [],
      permissions: ['report:read'], permissionsByBranch: { b1: ['report:read'], b2: [], b3: ['report:read'] },
    });
    auth.setActiveBranch('b1');
    router = TestBed.inject(Router);
    await router.navigateByUrl('/reports');
    shell = TestBed.createComponent(ShellComponent).componentInstance;
  });

  const switchTo = async (branchId: string) => {
    shell.onBranchChange({ target: { value: branchId } } as unknown as Event);
    await new Promise((resolve) => setTimeout(resolve));
  };

  it('leaves a page the new branch role does not open', async () => {
    await switchTo('b2');
    expect(router.url).toBe('/');
  });

  it('stays on a page the new branch role opens', async () => {
    await switchTo('b3');
    expect(router.url).toBe('/reports');
  });

  it('drops a branch from the selector once the user no longer holds it', () => {
    const branch = (id: string) => ({ id, companyId: 'co', name: id, code: id, active: true });
    TestBed.inject(HttpTestingController).expectOne((r) => r.url.endsWith('/branches'))
      .flush({ content: ['b1', 'b2', 'b3'].map(branch), page: 0, size: 20, totalElements: 3, totalPages: 1 });
    expect(shell.branches().map((b) => b.id)).toEqual(['b1', 'b2', 'b3']);

    const auth = TestBed.inject(AuthService);
    auth.me.set({ ...auth.me()!, branchIds: ['b1', 'b3'] });

    expect(shell.branches().map((b) => b.id)).toEqual(['b1', 'b3']);
  });
});

describe('ShellComponent layout', () => {
  let router: Router;
  beforeEach(async () => {
    stubLocalStorage();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([{ path: 'pos', component: BlankComponent }, { path: 'inventory/stock', component: BlankComponent }, { path: 'inventory/stock/x', component: BlankComponent }, { path: 'posters', component: BlankComponent }, { path: 'nowhere', component: BlankComponent }, { path: '', component: BlankComponent }]),
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    });
    TestBed.inject(AuthService).me.set({
      userId: 'u', email: 'a@b.c', companyId: 'co', branchIds: ['b1'], roles: ['OWNER'],
      permissions: ['inventory:read', 'sales:write'], permissionsByBranch: { b1: ['inventory:read', 'sales:write'] },
    });
    router = TestBed.inject(Router);
  });

  it('remembers a collapsed sidebar', () => {
    const shell = TestBed.createComponent(ShellComponent).componentInstance;
    shell.toggleRail();
    expect(shell.collapsed()).toBe(true);
    expect(localStorage.getItem('erpcafe.sidebar')).toBe('rail');
  });

  it('rails the sidebar on the POS screen without changing the saved choice', async () => {
    const shell = TestBed.createComponent(ShellComponent).componentInstance;
    await router.navigateByUrl('/pos');
    expect(shell.railed()).toBe(true);
    expect(shell.collapsed()).toBe(false);
  });

  it('titles the page from the navigation', async () => {
    const shell = TestBed.createComponent(ShellComponent).componentInstance;
    await router.navigateByUrl('/inventory/stock');
    expect(shell.pageTitle()).toEqual({ section: 'Inventory', label: 'Stock' });
  });

  it('starts collapsed when the saved choice is the rail', () => {
    localStorage.setItem('erpcafe.sidebar', 'rail');
    expect(TestBed.createComponent(ShellComponent).componentInstance.collapsed()).toBe(true);
  });

  it('does not save the sidebar choice when POS rails it', async () => {
    TestBed.createComponent(ShellComponent);
    await router.navigateByUrl('/pos');
    expect(localStorage.getItem('erpcafe.sidebar')).toBeNull();
  });

  it('does not rail on paths that merely start with /pos', async () => {
    const shell = TestBed.createComponent(ShellComponent).componentInstance;
    await router.navigateByUrl('/posters');
    expect(shell.railed()).toBe(false);
  });

  it('titles nested pages from the longest matching path', async () => {
    const shell = TestBed.createComponent(ShellComponent).componentInstance;
    await router.navigateByUrl('/');
    expect(shell.pageTitle()).toEqual({ section: 'Overview', label: 'Dashboard' });
    await router.navigateByUrl('/inventory/stock/x');
    expect(shell.pageTitle()).toEqual({ section: 'Inventory', label: 'Stock' });
  });

  it('hides the crumbs on an unmatched page', async () => {
    const fixture = TestBed.createComponent(ShellComponent);
    await router.navigateByUrl('/nowhere');
    fixture.detectChanges();
    expect(fixture.componentInstance.pageTitle().label).toBe('');
    expect(fixture.nativeElement.querySelector('.crumbs')?.textContent?.trim() ?? '').toBe('');
  });

  describe('user menu keyboard', () => {
    const open = async () => {
      const fixture = TestBed.createComponent(ShellComponent);
      document.body.appendChild(fixture.nativeElement);
      fixture.detectChanges();
      const btn = fixture.nativeElement.querySelector('.avatar-btn') as HTMLButtonElement;
      btn.click();
      fixture.detectChanges();
      await fixture.whenStable();
      return { fixture, btn, items: () => Array.from(fixture.nativeElement.querySelectorAll('[role^="menuitem"]')) as HTMLElement[] };
    };
    const key = (el: Element, k: string) => el.dispatchEvent(new KeyboardEvent('keydown', { key: k, bubbles: true }));

    it('focuses the first item on open', async () => {
      const { items } = await open();
      expect(document.activeElement).toBe(items()[0]);
    });

    it('moves with arrows and wraps', async () => {
      const { fixture, items } = await open();
      key(document.activeElement!, 'ArrowDown');
      expect(document.activeElement).toBe(items()[1]);
      key(document.activeElement!, 'ArrowDown');
      expect(document.activeElement).toBe(items()[0]);
      key(document.activeElement!, 'End');
      expect(document.activeElement).toBe(items()[1]);
      fixture.nativeElement.remove();
    });

    it('closes on Escape and returns focus to the avatar button', async () => {
      const { fixture, btn } = await open();
      key(document.activeElement!, 'Escape');
      fixture.detectChanges();
      expect(fixture.nativeElement.querySelector('.menu')).toBeNull();
      expect(document.activeElement).toBe(btn);
      fixture.nativeElement.remove();
    });
  });
});
