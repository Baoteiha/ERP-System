import { stubLocalStorage } from './test-storage';
import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Router, provideRouter } from '@angular/router';
import { authHeaderInterceptor, errorInterceptor } from './http.interceptors';
import { AuthService } from './auth.service';

describe('errorInterceptor', () => {
  let http: HttpClient;
  let ctrl: HttpTestingController;
  let auth: AuthService;
  let navigate: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    stubLocalStorage();
    localStorage.setItem('erpcafe.access', 'old');
    localStorage.setItem('erpcafe.refresh', 'r1');
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        provideHttpClient(withInterceptors([authHeaderInterceptor, errorInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    http = TestBed.inject(HttpClient);
    ctrl = TestBed.inject(HttpTestingController);
    auth = TestBed.inject(AuthService);
    navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
  });

  afterEach(() => ctrl.verify());

  const tokens = { accessToken: 'new', refreshToken: 'r2' };

  it('propagates an error from the retried request without logging out', () => {
    const logout = vi.spyOn(auth, 'logout');
    let status = 0;
    http.get('/api/v1/x').subscribe({ error: (e) => (status = e.status) });

    ctrl.expectOne('/api/v1/x').flush({}, { status: 401, statusText: 'Unauthorized' });
    ctrl.expectOne('/api/v1/auth/refresh').flush(tokens);
    ctrl.expectOne('/api/v1/x').flush({ code: 'CONFLICT' }, { status: 409, statusText: 'Conflict' });

    expect(status).toBe(409);
    expect(logout).not.toHaveBeenCalled();
    expect(navigate).not.toHaveBeenCalled();
  });

  it('logs out on a failed refresh and errors the requests queued behind it', () => {
    let first = 0;
    let second = 0;
    http.get('/api/v1/a').subscribe({ error: (e) => (first = e.status) });
    http.get('/api/v1/b').subscribe({ error: (e) => (second = e.status) });

    ctrl.expectOne('/api/v1/a').flush({}, { status: 401, statusText: 'Unauthorized' });
    ctrl.expectOne('/api/v1/b').flush({}, { status: 401, statusText: 'Unauthorized' });
    ctrl.expectOne('/api/v1/auth/refresh').flush({}, { status: 401, statusText: 'Unauthorized' });

    expect(first).toBe(401);
    expect(second).toBe(401);
    expect(auth.accessToken()).toBeNull();
    expect(navigate).toHaveBeenCalledWith(['/login']);
  });

  it('retries with the current token, without a second refresh, when another refresh already finished', () => {
    let ok = false;
    http.get('/api/v1/a').subscribe(() => (ok = true));
    const stale = ctrl.expectOne('/api/v1/a'); // sent with 'old'
    auth.accessToken.set('newer'); // a refresh completed while this request was in flight
    auth.refreshToken.set('r9');

    stale.flush({}, { status: 401, statusText: 'Unauthorized' });
    const retry = ctrl.expectOne('/api/v1/a');
    expect(retry.request.headers.get('Authorization')).toBe('Bearer newer');
    retry.flush({});

    ctrl.expectNone('/api/v1/auth/refresh');
    expect(ok).toBe(true);
  });

  it('triggers exactly one refresh for concurrent 401s', () => {
    const done: string[] = [];
    http.get('/api/v1/a').subscribe(() => done.push('a'));
    http.get('/api/v1/b').subscribe(() => done.push('b'));

    ctrl.expectOne('/api/v1/a').flush({}, { status: 401, statusText: 'Unauthorized' });
    ctrl.expectOne('/api/v1/b').flush({}, { status: 401, statusText: 'Unauthorized' });
    ctrl.expectOne('/api/v1/auth/refresh').flush(tokens);
    ctrl.expectOne('/api/v1/a').flush({});
    ctrl.expectOne('/api/v1/b').flush({});

    expect(done.sort()).toEqual(['a', 'b']);
  });

  describe('a refused active branch', () => {
    const me = { branchIds: ['kept'], permissions: [], permissionsByBranch: {} };

    it('re-reads the user, which moves to a branch they still hold, and leaves the page', () => {
      const navigateByUrl = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
      auth.setActiveBranch('gone');
      let status = 0;
      http.get('/api/v1/x').subscribe({ error: (e) => (status = e.status) });

      const refused = ctrl.expectOne('/api/v1/x');
      expect(refused.request.headers.get('X-Branch-Id')).toBe('gone');
      refused.flush({ code: 'BRANCH_FORBIDDEN' }, { status: 403, statusText: 'Forbidden' });
      ctrl.expectOne('/api/v1/auth/me').flush(me);

      expect(status).toBe(403);
      expect(auth.activeBranchId()).toBe('kept');
      expect(navigateByUrl).toHaveBeenCalledWith('/');
    });

    it('re-reads the user once for refusals arriving together', () => {
      auth.setActiveBranch('gone');
      http.get('/api/v1/a').subscribe({ error: () => {} });
      http.get('/api/v1/b').subscribe({ error: () => {} });

      ctrl.expectOne('/api/v1/a').flush({ code: 'BRANCH_FORBIDDEN' }, { status: 403, statusText: 'Forbidden' });
      ctrl.expectOne('/api/v1/b').flush({ code: 'BRANCH_FORBIDDEN' }, { status: 403, statusText: 'Forbidden' });
      ctrl.expectOne('/api/v1/auth/me').flush(me);

      expect(auth.activeBranchId()).toBe('kept');
    });

    it('leaves the user alone on a missing permission', () => {
      auth.setActiveBranch('b1');
      http.get('/api/v1/x').subscribe({ error: () => {} });

      ctrl.expectOne('/api/v1/x').flush({ code: 'FORBIDDEN' }, { status: 403, statusText: 'Forbidden' });

      ctrl.expectNone('/api/v1/auth/me');
      expect(auth.activeBranchId()).toBe('b1');
    });
  });
});

