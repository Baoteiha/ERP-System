import { stubLocalStorage } from './test-storage';
import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { firstValueFrom } from 'rxjs';
import { Router, provideRouter } from '@angular/router';
import { AuthService } from './auth.service';
import { MeResponse } from './models';

describe('AuthService', () => {
  let ctrl: HttpTestingController;

  const setup = () => {
    TestBed.configureTestingModule({
      providers: [provideZonelessChangeDetection(), provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    ctrl = TestBed.inject(HttpTestingController);
    return TestBed.inject(AuthService);
  };

  beforeEach(() => stubLocalStorage());

  it('logout clears both tokens and the stored branch', async () => {
    localStorage.setItem('erpcafe.access', 'a');
    localStorage.setItem('erpcafe.refresh', 'r');
    localStorage.setItem('erpcafe.branch', 'b1');
    const auth = setup();

    const done = auth.logout();
    ctrl.expectOne('/api/v1/auth/logout').flush({});
    await done;

    expect(localStorage.getItem('erpcafe.access')).toBeNull();
    expect(localStorage.getItem('erpcafe.refresh')).toBeNull();
    expect(localStorage.getItem('erpcafe.branch')).toBeNull();
    expect(auth.activeBranchId()).toBeNull();
  });

  it('replaces a stored branch that is not in the user branches', () => {
    localStorage.setItem('erpcafe.branch', 'b1');
    const auth = setup();

    auth.loadMe().subscribe();
    ctrl.expectOne('/api/v1/auth/me')
      .flush({ branchIds: ['b2'], permissions: [], permissionsByBranch: {} } as unknown as MeResponse);

    expect(auth.activeBranchId()).toBe('b2');
    expect(localStorage.getItem('erpcafe.branch')).toBe('b2');
  });

  it('clears a stale active branch when the user has no branches', () => {
    localStorage.setItem('erpcafe.branch', 'b1');
    const auth = setup();

    auth.loadMe().subscribe();
    ctrl.expectOne('/api/v1/auth/me')
      .flush({ branchIds: [], permissions: [], permissionsByBranch: {} } as unknown as MeResponse);

    expect(auth.activeBranchId()).toBeNull();
    expect(localStorage.getItem('erpcafe.branch')).toBeNull();
  });

  describe('permissions per branch', () => {
    // manager at b1, cashier at b2: the server weighs each request at the branch it names
    const me = {
      userId: 'u', email: 'a@b.c', companyId: 'co', branchIds: ['b1', 'b2'], roles: ['MANAGER', 'CASHIER'],
      permissions: ['sales:write', 'sales:refund', 'user:write'],
      permissionsByBranch: { b1: ['sales:write', 'sales:refund', 'user:write'], b2: ['sales:write'] },
    } satisfies MeResponse;

    it('weighs a permission at the active branch', () => {
      const auth = setup();
      auth.me.set(me);

      auth.setActiveBranch('b2');
      expect(auth.can('sales:refund')).toBe(false);
      expect(auth.can('sales:write')).toBe(true);

      auth.setActiveBranch('b1');
      expect(auth.can('sales:refund')).toBe(true);
    });

    it('weighs company-level administration against every branch held', () => {
      const auth = setup();
      auth.me.set(me);
      auth.setActiveBranch('b2');

      expect(auth.can('user:write')).toBe(true);
    });

    it('weighs every branch when none is active', () => {
      const auth = setup();
      auth.me.set(me);

      expect(auth.activeBranchId()).toBeNull();
      expect(auth.can('sales:refund')).toBe(true);
    });
  });

  describe('across tabs', () => {
    const storageEvent = (key: string | null, newValue: string | null) =>
      window.dispatchEvent(new StorageEvent('storage', { key, newValue }));

    it('picks up tokens another tab stored', () => {
      localStorage.setItem('erpcafe.access', 'old');
      localStorage.setItem('erpcafe.refresh', 'r1');
      const auth = setup();

      storageEvent('erpcafe.access', 'new');
      storageEvent('erpcafe.refresh', 'r2');

      expect(auth.accessToken()).toBe('new');
      expect(auth.refreshToken()).toBe('r2');
    });

    it('treats a token removed by another tab as a logout and leaves for /login', () => {
      localStorage.setItem('erpcafe.access', 'old');
      localStorage.setItem('erpcafe.refresh', 'r1');
      localStorage.setItem('erpcafe.branch', 'b1');
      const auth = setup();
      const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);

      storageEvent('erpcafe.access', null);

      expect(auth.accessToken()).toBeNull();
      expect(auth.isAuthenticated()).toBe(false);
      expect(auth.activeBranchId()).toBeNull();
      expect(navigate).toHaveBeenCalledWith(['/login']);
    });

    it('leaves for /login when another tab clears all storage', () => {
      localStorage.setItem('erpcafe.access', 'old');
      localStorage.setItem('erpcafe.branch', 'b1');
      const auth = setup();
      const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
      localStorage.clear();

      storageEvent(null, null);

      expect(auth.accessToken()).toBeNull();
      expect(auth.activeBranchId()).toBeNull();
      expect(navigate).toHaveBeenCalledWith(['/login']);
    });

    it('follows a branch change made in another tab', () => {
      localStorage.setItem('erpcafe.access', 'old');
      localStorage.setItem('erpcafe.branch', 'b1');
      const auth = setup();

      storageEvent('erpcafe.branch', 'b2');
      expect(auth.activeBranchId()).toBe('b2');
      storageEvent('erpcafe.branch', null);
      expect(auth.activeBranchId()).toBeNull();
    });

    describe('refresh lock', () => {
      afterEach(() => vi.unstubAllGlobals());

      const stubLocks = () => {
        const request = vi.fn((_name: string, cb: () => Promise<unknown>) => cb());
        vi.stubGlobal('navigator', { locks: { request } });
        return request;
      };

      it('refreshes inside the erpcafe-refresh lock', async () => {
        const request = stubLocks();
        localStorage.setItem('erpcafe.access', 'old');
        localStorage.setItem('erpcafe.refresh', 'r1');
        const auth = setup();

        const done = firstValueFrom(auth.refresh());
        ctrl.expectOne('/api/v1/auth/refresh').flush({ accessToken: 'new', refreshToken: 'r2', tokenType: 'Bearer' });
        await done;

        expect(request.mock.calls[0][0]).toBe('erpcafe-refresh');
        expect(auth.accessToken()).toBe('new');
      });

      it('reuses the tokens another tab stored while waiting for the lock', async () => {
        const request = vi.fn(async (_name: string, cb: () => Promise<unknown>) => {
          // the other tab finished its refresh while we queued
          localStorage.setItem('erpcafe.access', 'other-new');
          localStorage.setItem('erpcafe.refresh', 'other-r2');
          return cb();
        });
        vi.stubGlobal('navigator', { locks: { request } });
        localStorage.setItem('erpcafe.access', 'old');
        localStorage.setItem('erpcafe.refresh', 'r1');
        const auth = setup();

        const res = await firstValueFrom(auth.refresh());

        ctrl.expectNone('/api/v1/auth/refresh');
        expect(res.accessToken).toBe('other-new');
        expect(auth.accessToken()).toBe('other-new');
        expect(auth.refreshToken()).toBe('other-r2');
      });
    });
  });
});
