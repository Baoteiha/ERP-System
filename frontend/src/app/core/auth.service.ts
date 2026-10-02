import { DestroyRef, Injectable, Injector, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { HttpClient, HttpContext, HttpErrorResponse } from '@angular/common/http';
import { Observable, catchError, defer, firstValueFrom, from, of, tap, timeout } from 'rxjs';
import { LoginRequest, MeResponse, TokenResponse } from './models';
import { SUPPRESS_ERROR_TOAST } from './http.interceptors';
import { COMPANY_LEVEL_PERMISSIONS } from './permissions';

const ACCESS_KEY = 'erpcafe.access';
/** The refresh token now lives in an HttpOnly cookie; an old build left it here, readable by any script. */
const LEGACY_REFRESH_KEY = 'erpcafe.refresh';
/** Non-secret marker that a cookie session may exist, so a signed-out visitor triggers no refresh call. */
const SESSION_KEY = 'erpcafe.session';
const BRANCH_KEY = 'erpcafe.branch';
const LOGOUT_TIMEOUT_MS = 3000;
const REFRESH_LOCK = 'erpcafe-refresh';
const CSRF_HEADERS = { 'X-Requested-With': 'erpcafe' };

/** Holds tokens + the current identity. Backbone of auth + branch scoping. */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private http = inject(HttpClient);
  private injector = inject(Injector);
  private base = '/api/v1/auth';

  readonly accessToken = signal<string | null>(localStorage.getItem(ACCESS_KEY));
  readonly me = signal<MeResponse | null>(null);

  /** active branch id — sent as X-Branch-Id on scoped requests */
  readonly activeBranchId = signal<string | null>(localStorage.getItem(BRANCH_KEY));

  readonly isAuthenticated = computed(() => !!this.accessToken());
  /** In force for the active branch, weighed as the server weighs a request carrying X-Branch-Id (ADR-0007). */
  readonly permissions = computed(() => {
    const me = this.me();
    if (!me) return new Set<string>();
    const branch = this.activeBranchId();
    // a request naming no branch is weighed against every branch the user holds
    const atBranch = branch ? (me.permissionsByBranch[branch] ?? []) : me.permissions;
    return new Set([...atBranch, ...me.permissions.filter((p) => COMPANY_LEVEL_PERMISSIONS.has(p))]);
  });

  constructor() {
    localStorage.removeItem(LEGACY_REFRESH_KEY);
    // another tab refreshed or logged out: follow its tokens instead of using (and burning) stale ones
    // signals only, never storage writes, so tabs cannot echo each other
    const onStorage = (e: StorageEvent) => {
      if (e.key === null) {
        this.syncTokensFromStorage();
        this.activeBranchId.set(localStorage.getItem(BRANCH_KEY));
      } else if (e.key === ACCESS_KEY) {
        this.accessToken.set(e.newValue);
      } else if (e.key === BRANCH_KEY) {
        this.activeBranchId.set(e.newValue);
      }
      if (!this.accessToken()) this.dropIdentity();
    };
    window.addEventListener('storage', onStorage);
    // a destroyed injector (a torn-down test bed) must not keep answering other tabs
    inject(DestroyRef).onDestroy(() => window.removeEventListener('storage', onStorage));
  }

  can(permission: string): boolean {
    return this.permissions().has(permission);
  }
  /** Whether the user's role at the branch carries the permission, whichever branch is active. */
  canAt(branchId: string, permission: string): boolean {
    return !!this.me()?.permissionsByBranch[branchId]?.includes(permission);
  }
  /** Whether the user holds the OWNER role, which the server lets act on any branch of the company. */
  isOwner(): boolean {
    return !!this.me()?.roles.includes('OWNER');
  }
  canAny(...permissions: string[]): boolean {
    const set = this.permissions();
    return permissions.some((p) => set.has(p));
  }

  /** Whether a refresh cookie may be waiting; the cookie itself is HttpOnly and cannot be read. */
  canRefresh(): boolean {
    return localStorage.getItem(SESSION_KEY) === '1';
  }

  /**
   * On app start: keep a stored access token, else try the cookie once, but only if this browser signed in
   * before. Never rejects; a failed refresh leaves the visitor signed out. Only a definitive refusal
   * (401, 422) forgets the session: an offline or failing server keeps the flag for the next start.
   */
  async restoreSession(): Promise<void> {
    if (this.accessToken() || !this.canRefresh()) return;
    try {
      await firstValueFrom(this.refresh(true));
    } catch (e) {
      if (e instanceof HttpErrorResponse && (e.status === 401 || e.status === 422)) await this.logout(false);
    }
  }

  login(body: LoginRequest): Observable<TokenResponse> {
    return this.http.post<TokenResponse>(`${this.base}/login`, body, { withCredentials: true }).pipe(
      tap((res) => this.storeTokens(res)),
    );
  }

  loadMe(): Observable<MeResponse> {
    return this.http.get<MeResponse>(`${this.base}/me`).pipe(
      tap((me) => {
        this.me.set(me);
        // a stored branch the user no longer (or never) had access to falls back to their first
        const active = this.activeBranchId();
        if (me.branchIds?.length && (!active || !me.branchIds.includes(active))) {
          this.setActiveBranch(me.branchIds[0]);
        } else if (!me.branchIds?.length && active) {
          this.activeBranchId.set(null);
          localStorage.removeItem(BRANCH_KEY);
        }
      }),
    );
  }

  /** Rotates the tokens; tabs queue on one lock so a rotated refresh cookie is never replayed. */
  refresh(quiet = false): Observable<TokenResponse> {
    const locks = typeof navigator === 'undefined' ? undefined : navigator.locks;
    if (!locks) return this.callRefresh(quiet);
    const before = this.accessToken();
    return defer(() =>
      from(locks.request(REFRESH_LOCK, () => firstValueFrom(this.refreshUnlessRotated(before, quiet)))),
    );
  }

  /**
   * Inside the lock: a different access token in storage than the one this call began with means another
   * tab already refreshed (the cookie is shared, so replaying it would look like theft); adopt that result.
   */
  private refreshUnlessRotated(before: string | null, quiet: boolean): Observable<TokenResponse> {
    const storedAccess = localStorage.getItem(ACCESS_KEY);
    if (storedAccess && storedAccess !== before) {
      this.syncTokensFromStorage();
      return of({ accessToken: storedAccess, tokenType: 'Bearer' });
    }
    return this.callRefresh(quiet);
  }

  /** The refresh token rides in the cookie, so there is no body; the header is the CSRF guard the server demands. */
  private callRefresh(quiet: boolean): Observable<TokenResponse> {
    return this.http
      .post<TokenResponse>(`${this.base}/refresh`, null, {
        withCredentials: true,
        headers: CSRF_HEADERS,
        context: new HttpContext().set(SUPPRESS_ERROR_TOAST, quiet),
      })
      .pipe(tap((res) => this.storeTokens(res)));
  }

  private syncTokensFromStorage() {
    this.accessToken.set(localStorage.getItem(ACCESS_KEY));
    if (!this.accessToken()) this.dropIdentity();
  }

  /** Logged out elsewhere: forget the user and leave their pages, so nothing of theirs stays on screen. */
  private dropIdentity() {
    this.me.set(null);
    this.activeBranchId.set(null);
    const router = this.injector.get(Router);
    if (!router.url.startsWith('/login')) void router.navigate(['/login']);
  }

  setActiveBranch(branchId: string) {
    this.activeBranchId.set(branchId);
    localStorage.setItem(BRANCH_KEY, branchId);
  }

  /** Clears local state at once; resolves after the server logout (bounded) so callers can navigate safely. */
  async logout(callServer = true): Promise<void> {
    const hadSession = this.canRefresh();
    this.accessToken.set(null);
    this.me.set(null);
    this.activeBranchId.set(null);
    localStorage.removeItem(ACCESS_KEY);
    localStorage.removeItem(SESSION_KEY);
    localStorage.removeItem(BRANCH_KEY);
    if (callServer && hadSession) {
      await firstValueFrom(
        this.http.post(`${this.base}/logout`, null, { withCredentials: true, headers: CSRF_HEADERS }).pipe(
          timeout(LOGOUT_TIMEOUT_MS),
          catchError(() => of(null)),
        ),
      );
    }
  }

  private storeTokens(res: TokenResponse) {
    this.accessToken.set(res.accessToken);
    localStorage.setItem(ACCESS_KEY, res.accessToken);
    localStorage.setItem(SESSION_KEY, '1');
  }
}
