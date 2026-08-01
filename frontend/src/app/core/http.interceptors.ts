import { HttpContextToken, HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { BehaviorSubject, catchError, filter, switchMap, take, throwError } from 'rxjs';
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

// shared refresh state so concurrent 401s only trigger one refresh
let refreshing = false;
const refreshed$ = new BehaviorSubject<string | null>(null);

/** Transparently refresh on 401, surface API errors as toasts. */
export const errorInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(AuthService);
  const router = inject(Router);
  const toast = inject(ToastService);

  return next(req).pipe(
    catchError((err: HttpErrorResponse) => {
      // try a one-time refresh on 401 for scoped calls
      if (err.status === 401 && !isAuthFree(req.url) && auth.refreshToken()) {
        if (refreshing) {
          return refreshed$.pipe(
            filter((t) => t !== null),
            take(1),
            switchMap((t) => next(req.clone({ setHeaders: { Authorization: `Bearer ${t}` } }))),
          );
        }
        refreshing = true;
        refreshed$.next(null);
        return auth.refresh().pipe(
          switchMap((res) => {
            refreshing = false;
            refreshed$.next(res.accessToken);
            return next(req.clone({ setHeaders: { Authorization: `Bearer ${res.accessToken}` } }));
          }),
          catchError((refreshErr) => {
            refreshing = false;
            auth.logout(false);
            router.navigate(['/login']);
            return throwError(() => refreshErr);
          }),
        );
      }

      if (req.context.get(SUPPRESS_ERROR_TOAST)) {
        return throwError(() => err);
      }

      if (err.status === 0) {
        toast.error('Cannot reach the server. Is the backend running on :8090?');
      } else if (err.status === 401) {
        auth.logout(false);
        router.navigate(['/login']);
      } else if (err.status !== 422 || !req.url.includes('/orders')) {
        // 422 on POS flows are handled locally; everything else surfaces here
        const pd = err.error as ProblemDetail | undefined;
        const msg = pd?.detail || pd?.title || err.message || 'Something went wrong';
        toast.error(msg);
      }
      return throwError(() => err);
    }),
  );
};
