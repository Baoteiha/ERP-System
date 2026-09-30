import { HttpContextToken, HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Router } from '@angular/router';
import { Observable, catchError, finalize, map, shareReplay, switchMap, tap, throwError } from 'rxjs';
import { AuthService } from './auth.service';
import { ToastService } from './toast.service';
import { ProblemDetail } from './models';

const AUTH_FREE = ['/auth/login', '/auth/refresh', '/auth/logout'];
const isAuthFree = (url: string) => AUTH_FREE.some((p) => url.includes(p));

export const SUPPRESS_ERROR_TOAST = new HttpContextToken<boolean>(() => false);

/** Attach bearer token + X-Branch-Id to outgoing API calls. */
export const authHeaderInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(AuthService);
  if (!req.url.startsWith('/api') || isAuthFree(req.url)) return next(req);

  const token = auth.accessToken();
  const branchId = auth.activeBranchId();
  const headers: Record<string, string> = {};
  if (token) headers['Authorization'] = `Bearer ${token}`;
  if (branchId) headers['X-Branch-Id'] = branchId;
  return next(Object.keys(headers).length ? req.clone({ setHeaders: headers }) : req);
};

/** Single in-flight token refresh shared by every request that hit a 401. */
@Injectable({ providedIn: 'root' })
export class RefreshCoordinator {
  private auth = inject(AuthService);
  private router = inject(Router);
  private inFlight: Observable<string> | null = null;

  /** Emits the new access token; on failure logs the user out and errors every waiter. */
  refreshed(): Observable<string> {
    if (!this.inFlight) {
      this.inFlight = this.auth.refresh().pipe(
        map((res) => res.accessToken),
        tap({
          error: () => {
            void this.auth.logout(false);
            this.router.navigate(['/login']);
          },
        }),
        finalize(() => (this.inFlight = null)),
        shareReplay({ bufferSize: 1, refCount: false }),
      );
    }
    return this.inFlight;
  }
}

/** Transparently refresh on 401, surface API errors as toasts. */
export const errorInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(AuthService);
  const router = inject(Router);
  const toast = inject(ToastService);
  const coordinator = inject(RefreshCoordinator);

  const surface = (err: HttpErrorResponse) => {
    if (!req.context.get(SUPPRESS_ERROR_TOAST)) {
      if (err.status === 0) {
        toast.error('Cannot reach the server. Check your connection and try again.');
      } else if (err.status !== 401) {
        const pd = err.error as ProblemDetail | undefined;
        toast.error(pd?.detail || pd?.title || err.message || 'Something went wrong');
      }
    }
    return throwError(() => err);
  };

  return next(req).pipe(
    catchError((err: HttpErrorResponse) => {
      if (err.status === 401 && !isAuthFree(req.url)) {
        if (auth.refreshToken()) {
          // only the refresh itself may log out; the retried request's errors propagate normally
          return coordinator.refreshed().pipe(
            switchMap((token) => next(req.clone({ setHeaders: { Authorization: `Bearer ${token}` } }))),
            catchError((e: HttpErrorResponse) => surface(e)),
          );
        }
        void auth.logout(false);
        router.navigate(['/login']);
        return throwError(() => err);
      }
      return surface(err);
    }),
  );
};
