import { Injectable, computed, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, tap } from 'rxjs';
import { LoginRequest, MeResponse, TokenResponse } from './models';

const ACCESS_KEY = 'erpcafe.access';
const REFRESH_KEY = 'erpcafe.refresh';
const BRANCH_KEY = 'erpcafe.branch';

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
        // default the active branch to the first one the user can access
        if (!this.activeBranchId() && me.branchIds?.length) {
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

  logout(callServer = true) {
    const token = this.refreshToken();
    if (callServer && token) {
      this.http.post(`${this.base}/logout`, { refreshToken: token }).subscribe({ error: () => {} });
    }
    this.accessToken.set(null);
    this.refreshToken.set(null);
    this.me.set(null);
    localStorage.removeItem(ACCESS_KEY);
    localStorage.removeItem(REFRESH_KEY);
  }

  private storeTokens(res: TokenResponse) {
    this.accessToken.set(res.accessToken);
    this.refreshToken.set(res.refreshToken);
    localStorage.setItem(ACCESS_KEY, res.accessToken);
    localStorage.setItem(REFRESH_KEY, res.refreshToken);
  }
}
