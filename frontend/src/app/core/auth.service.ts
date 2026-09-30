import { Injectable, computed, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, catchError, firstValueFrom, of, tap, timeout } from 'rxjs';
import { LoginRequest, MeResponse, TokenResponse } from './models';

const ACCESS_KEY = 'erpcafe.access';
const REFRESH_KEY = 'erpcafe.refresh';
const BRANCH_KEY = 'erpcafe.branch';
const LOGOUT_TIMEOUT_MS = 3000;

/** Holds tokens + the current identity. Backbone of auth + branch scoping. */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private http = inject(HttpClient);
  private base = '/api/v1/auth';

  readonly accessToken = signal<string | null>(localStorage.getItem(ACCESS_KEY));
  readonly refreshToken = signal<string | null>(localStorage.getItem(REFRESH_KEY));
  readonly me = signal<MeResponse | null>(null);

  /** active branch id — sent as X-Branch-Id on scoped requests */
  readonly activeBranchId = signal<string | null>(localStorage.getItem(BRANCH_KEY));

  readonly isAuthenticated = computed(() => !!this.accessToken());
  readonly permissions = computed(() => new Set(this.me()?.permissions ?? []));

  can(permission: string): boolean {
    return this.permissions().has(permission);
  }
  canAny(...permissions: string[]): boolean {
    const set = this.permissions();
    return permissions.some((p) => set.has(p));
  }

  login(body: LoginRequest): Observable<TokenResponse> {
    return this.http.post<TokenResponse>(`${this.base}/login`, body).pipe(
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
        }
      }),
    );
  }

  refresh(): Observable<TokenResponse> {
    return this.http
      .post<TokenResponse>(`${this.base}/refresh`, { refreshToken: this.refreshToken() })
      .pipe(tap((res) => this.storeTokens(res)));
  }

  setActiveBranch(branchId: string) {
    this.activeBranchId.set(branchId);
    localStorage.setItem(BRANCH_KEY, branchId);
  }

  /** Clears local state at once; resolves after the server logout (bounded) so callers can navigate safely. */
  async logout(callServer = true): Promise<void> {
    const token = this.refreshToken();
    this.accessToken.set(null);
    this.refreshToken.set(null);
    this.me.set(null);
    this.activeBranchId.set(null);
    localStorage.removeItem(ACCESS_KEY);
    localStorage.removeItem(REFRESH_KEY);
    localStorage.removeItem(BRANCH_KEY);
    if (callServer && token) {
      await firstValueFrom(
        this.http.post(`${this.base}/logout`, { refreshToken: token }).pipe(
          timeout(LOGOUT_TIMEOUT_MS),
          catchError(() => of(null)),
        ),
      );
    }
  }

  private storeTokens(res: TokenResponse) {
    this.accessToken.set(res.accessToken);
    this.refreshToken.set(res.refreshToken);
    localStorage.setItem(ACCESS_KEY, res.accessToken);
    localStorage.setItem(REFRESH_KEY, res.refreshToken);
  }
}
