import { stubLocalStorage } from './test-storage';
import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { firstValueFrom } from 'rxjs';
import { Router, provideRouter } from '@angular/router';
import { AuthService } from './auth.service';
import { SUPPRESS_ERROR_TOAST } from './http.interceptors';
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

  it('logout clears the access token, the session flag and the stored branch, and clears the cookie server-side', async () => {
    localStorage.setItem('erpcafe.access', 'a');
    localStorage.setItem('erpcafe.session', '1');
    localStorage.setItem('erpcafe.branch', 'b1');
    const auth = setup();

    const done = auth.logout();
    const req = ctrl.expectOne('/api/v1/auth/logout');
    expect(req.request.withCredentials).toBe(true);
    expect(req.request.headers.get('X-Requested-With')).toBe('erpcafe');
    expect(req.request.body).toBeNull();
    req.flush({});
    await done;

    expect(localStorage.getItem('erpcafe.access')).toBeNull();
    expect(localStorage.getItem('erpcafe.session')).toBeNull();
    expect(localStorage.getItem('erpcafe.branch')).toBeNull();
    expect(auth.activeBranchId()).toBeNull();
  });

  describe('the refresh token', () => {
    const written = () => vi.spyOn(localStorage, 'setItem');

    it('is never written to localStorage, by login or by refresh', async () => {
      const auth = setup();
      const setItem = written();

      auth.login({ email: 'a@b.c', password: 'pw' }).subscribe();
      const login = ctrl.expectOne('/api/v1/auth/login');
      expect(login.request.withCredentials).toBe(true);
      // even a body that still carried one must not reach storage
      login.flush({ accessToken: 'acc', refreshToken: 'SECRET', tokenType: 'Bearer' });
      const done = firstValueFrom(auth.refresh());
      ctrl.expectOne('/api/v1/auth/refresh')
        .flush({ accessToken: 'acc2', refreshToken: 'SECRET2', tokenType: 'Bearer' });
      await done;

      expect(setItem.mock.calls.map(([k]) => k).sort()).toEqual(
        ['erpcafe.access', 'erpcafe.access', 'erpcafe.session', 'erpcafe.session']);
      expect(setItem.mock.calls.map(([, v]) => v).join()).not.toContain('SECRET');
    });

    it('is sent as a cookie: no body, credentials on, and the CSRF header', async () => {
      const auth = setup();

      const done = firstValueFrom(auth.refresh());
      const req = ctrl.expectOne('/api/v1/auth/refresh');
      expect(req.request.body).toBeNull();
      expect(req.request.withCredentials).toBe(true);
      expect(req.request.headers.get('X-Requested-With')).toBe('erpcafe');
      req.flush({ accessToken: 'new', tokenType: 'Bearer' });
      await done;
    });

    it('is removed from localStorage on start when an older build left it there', () => {
      localStorage.setItem('erpcafe.refresh', 'legacy-secret');
      localStorage.setItem('erpcafe.access', 'a');

      const auth = setup();

      expect(localStorage.getItem('erpcafe.refresh')).toBeNull();
      expect(auth.accessToken()).toBe('a');
    });
  });

  describe('restoring a session on start', () => {
    it('uses a stored access token without calling the server', async () => {
      localStorage.setItem('erpcafe.access', 'a');
      localStorage.setItem('erpcafe.session', '1');
      const auth = setup();

      await auth.restoreSession();

      ctrl.expectNone('/api/v1/auth/refresh');
      expect(auth.accessToken()).toBe('a');
    });

    it('does not call refresh for a visitor who never signed in', async () => {
      const auth = setup();

      await auth.restoreSession();

      ctrl.expectNone('/api/v1/auth/refresh');
      expect(auth.isAuthenticated()).toBe(false);
    });

    it('refreshes from the cookie when the flag is set but no access token is stored', async () => {
      localStorage.setItem('erpcafe.session', '1');
      const auth = setup();

      const done = auth.restoreSession();
      ctrl.expectOne('/api/v1/auth/refresh').flush({ accessToken: 'fresh', tokenType: 'Bearer' });
      await done;

      expect(auth.accessToken()).toBe('fresh');
      expect(localStorage.getItem('erpcafe.access')).toBe('fresh');
    });

    it.each([401, 422])('clears the flag and stays signed out when the server refuses the cookie with %i', async (status) => {
      localStorage.setItem('erpcafe.session', '1');
      const auth = setup();

      const done = auth.restoreSession();
      ctrl.expectOne('/api/v1/auth/refresh').flush({}, { status, statusText: 'Refused' });
      await done;

      expect(auth.isAuthenticated()).toBe(false);
      expect(localStorage.getItem('erpcafe.session')).toBeNull();
      ctrl.expectNone('/api/v1/auth/logout');
    });

    it.each([0, 500, 503])('keeps the flag when the refresh fails with %i, so a later start can try again', async (status) => {
      localStorage.setItem('erpcafe.session', '1');
      const auth = setup();

      const done = auth.restoreSession();
      const req = ctrl.expectOne('/api/v1/auth/refresh');
      if (status === 0) req.error(new ProgressEvent('error'));
      else req.flush({}, { status, statusText: 'Down' });
      await done;

      expect(auth.isAuthenticated()).toBe(false);
      expect(localStorage.getItem('erpcafe.session')).toBe('1');
    });

    it('asks the interceptor for no error toast', async () => {
      localStorage.setItem('erpcafe.session', '1');
      const auth = setup();

      const done = auth.restoreSession();
      const req = ctrl.expectOne('/api/v1/auth/refresh');
      expect(req.request.context.get(SUPPRESS_ERROR_TOAST)).toBe(true);
      req.flush({ accessToken: 'fresh', tokenType: 'Bearer' });
      await done;
    });
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

    it('picks up the access token another tab stored', () => {
      localStorage.setItem('erpcafe.access', 'old');
      const auth = setup();

      storageEvent('erpcafe.access', 'new');

      expect(auth.accessToken()).toBe('new');
    });

    it('treats a token removed by another tab as a logout and leaves for /login', () => {
      localStorage.setItem('erpcafe.access', 'old');
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
        const auth = setup();

        const done = firstValueFrom(auth.refresh());
        ctrl.expectOne('/api/v1/auth/refresh').flush({ accessToken: 'new', tokenType: 'Bearer' });
        await done;

        expect(request.mock.calls[0][0]).toBe('erpcafe-refresh');
        expect(auth.accessToken()).toBe('new');
      });

      it('reuses the access token another tab stored while waiting for the lock', async () => {
        const request = vi.fn(async (_name: string, cb: () => Promise<unknown>) => {
          // the other tab finished its refresh while we queued
          localStorage.setItem('erpcafe.access', 'other-new');
          return cb();
        });
        vi.stubGlobal('navigator', { locks: { request } });
        localStorage.setItem('erpcafe.access', 'old');
        const auth = setup();

        const res = await firstValueFrom(auth.refresh());

        ctrl.expectNone('/api/v1/auth/refresh');
        expect(res.accessToken).toBe('other-new');
        expect(auth.accessToken()).toBe('other-new');
      });
    });
  });
});
