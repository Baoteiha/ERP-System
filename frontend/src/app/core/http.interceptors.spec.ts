import { stubLocalStorage } from './test-storage';
import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Router, provideRouter } from '@angular/router';
import { authHeaderInterceptor, errorInterceptor } from './http.interceptors';
import { AuthService } from './auth.service';
import { ToastService } from './toast.service';

describe('errorInterceptor', () => {
  let http: HttpClient;
  let ctrl: HttpTestingController;
  let auth: AuthService;
  let navigate: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    stubLocalStorage();
    localStorage.setItem('erpcafe.access', 'old');
    localStorage.setItem('erpcafe.session', '1');
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

  const tokens = { accessToken: 'new', tokenType: 'Bearer' };
  const unauthorized = { status: 401, statusText: 'Unauthorized' };

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

  it('signs out locally, with no second refresh, when the refresh itself answers 401', () => {
    let status = 0;
    http.get('/api/v1/x').subscribe({ error: (e) => (status = e.status) });

    ctrl.expectOne('/api/v1/x').flush({}, unauthorized);
    ctrl.expectOne('/api/v1/auth/refresh').flush({ code: 'UNAUTHORIZED' }, unauthorized);

    ctrl.expectNone('/api/v1/auth/refresh');
    ctrl.expectNone('/api/v1/x');
    ctrl.expectNone('/api/v1/auth/logout');
    expect(status).toBe(401);
    expect(auth.accessToken()).toBeNull();
    expect(localStorage.getItem('erpcafe.session')).toBeNull();
    expect(navigate).toHaveBeenCalledWith(['/login']);
  });

  it('restores no session, with a single refresh call, when the cookie is refused with 401', async () => {
    auth.accessToken.set(null);

    const done = auth.restoreSession();
    ctrl.expectOne('/api/v1/auth/refresh').flush({ code: 'UNAUTHORIZED' }, unauthorized);
    await done;

    ctrl.expectNone('/api/v1/auth/refresh');
    ctrl.expectNone('/api/v1/auth/logout');
    expect(auth.isAuthenticated()).toBe(false);
    expect(localStorage.getItem('erpcafe.session')).toBeNull();
  });

  it('retries with the current token, without a second refresh, when another refresh already finished', () => {
    let ok = false;
    http.get('/api/v1/a').subscribe(() => (ok = true));
    const stale = ctrl.expectOne('/api/v1/a'); // sent with 'old'
    auth.accessToken.set('newer'); // a refresh completed while this request was in flight

    stale.flush({}, { status: 401, statusText: 'Unauthorized' });
    const retry = ctrl.expectOne('/api/v1/a');
    expect(retry.request.headers.get('Authorization')).toBe('Bearer newer');
    retry.flush({});

    ctrl.expectNone('/api/v1/auth/refresh');
    expect(ok).toBe(true);
  });

  describe('a retry with the current token that is refused too', () => {
    const refusedOnce = () => {
      const stale = ctrl.expectOne('/api/v1/a'); // sent with 'old'
      auth.accessToken.set('newer'); // a refresh completed while this request was in flight
      stale.flush({}, unauthorized);
      const retry = ctrl.expectOne('/api/v1/a');
      expect(retry.request.headers.get('Authorization')).toBe('Bearer newer');
      // its auth version moved in between
      retry.flush({}, unauthorized);
    };

    it('goes through one refresh and is sent once more with its token', () => {
      let ok = false;
      http.get('/api/v1/a').subscribe(() => (ok = true));

      refusedOnce();
      ctrl.expectOne('/api/v1/auth/refresh').flush(tokens);
      const last = ctrl.expectOne('/api/v1/a');
      expect(last.request.headers.get('Authorization')).toBe('Bearer new');
      last.flush({});

      expect(ok).toBe(true);
    });

    it('stops after that refresh when the request is refused again, without a loop or a sign-out', () => {
      const logout = vi.spyOn(auth, 'logout');
      let status = 0;
      http.get('/api/v1/a').subscribe({ error: (e) => (status = e.status) });

      refusedOnce();
      ctrl.expectOne('/api/v1/auth/refresh').flush(tokens);
      ctrl.expectOne('/api/v1/a').flush({}, unauthorized);

      ctrl.expectNone('/api/v1/auth/refresh');
      ctrl.expectNone('/api/v1/a');
      expect(status).toBe(401);
      expect(logout).not.toHaveBeenCalled();
    });

    it('signs out locally when that refresh answers 401', () => {
      let status = 0;
      http.get('/api/v1/a').subscribe({ error: (e) => (status = e.status) });

      refusedOnce();
      ctrl.expectOne('/api/v1/auth/refresh').flush({ code: 'UNAUTHORIZED' }, unauthorized);

      ctrl.expectNone('/api/v1/auth/refresh');
      ctrl.expectNone('/api/v1/a');
      expect(status).toBe(401);
      expect(auth.accessToken()).toBeNull();
      expect(navigate).toHaveBeenCalledWith(['/login']);
    });
  });

  it.each(['/api/v1/auth/refresh', '/api/v1/auth/logout'])('sends %s without an Authorization header, whatever is stored', (url) => {
    expect(auth.accessToken()).toBe('old');

    http.post(url, null).subscribe();

    expect(ctrl.expectOne(url).request.headers.has('Authorization')).toBe(false);
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

    const refusedForTheBranch = { code: 'BRANCH_FORBIDDEN', detail: 'No access to branch gone' };

    it('recovers when the retry after a refresh is refused for the branch, without an error toast', () => {
      const toastError = vi.spyOn(TestBed.inject(ToastService), 'error');
      auth.setActiveBranch('gone');
      let status = 0;
      http.get('/api/v1/x').subscribe({ error: (e) => (status = e.status) });

      ctrl.expectOne('/api/v1/x').flush({}, { status: 401, statusText: 'Unauthorized' });
      ctrl.expectOne('/api/v1/auth/refresh').flush(tokens);
      const retry = ctrl.expectOne('/api/v1/x');
      expect(retry.request.headers.get('X-Branch-Id')).toBe('gone');
      retry.flush(refusedForTheBranch, { status: 403, statusText: 'Forbidden' });
      ctrl.expectOne('/api/v1/auth/me').flush(me);

      expect(status).toBe(403);
      expect(auth.activeBranchId()).toBe('kept');
      expect(toastError).not.toHaveBeenCalled();
    });

    it('recovers when the retry with an already refreshed token is refused for the branch', () => {
      auth.setActiveBranch('gone');
      http.get('/api/v1/x').subscribe({ error: () => {} });
      const stale = ctrl.expectOne('/api/v1/x');
      auth.accessToken.set('newer');

      stale.flush({}, { status: 401, statusText: 'Unauthorized' });
      ctrl.expectOne('/api/v1/x').flush(refusedForTheBranch, { status: 403, statusText: 'Forbidden' });
      ctrl.expectOne('/api/v1/auth/me').flush(me);

      expect(auth.activeBranchId()).toBe('kept');
    });

    it('does not recover again for a refusal of a branch already left', () => {
      auth.setActiveBranch('gone');
      http.get('/api/v1/a').subscribe({ error: () => {} });
      http.get('/api/v1/b').subscribe({ error: () => {} });
      const late = ctrl.expectOne('/api/v1/b');

      ctrl.expectOne('/api/v1/a').flush(refusedForTheBranch, { status: 403, statusText: 'Forbidden' });
      ctrl.expectOne('/api/v1/auth/me').flush(me);
      late.flush(refusedForTheBranch, { status: 403, statusText: 'Forbidden' });

      ctrl.expectNone('/api/v1/auth/me');
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

