import { inject } from '@angular/core';
import { CanMatchFn, Router } from '@angular/router';
import { map } from 'rxjs';
import { AuthService } from './auth.service';

/** Matches a route only for users holding one of the permissions; everyone else lands on the dashboard. */
export const permissionGuard = (...perms: string[]): CanMatchFn => () => {
  const auth = inject(AuthService);
  const router = inject(Router);
  const decide = () => (auth.canAny(...perms) ? true : router.parseUrl('/'));
  // identity is hydrated by the shell, which has not rendered yet on a fresh page load
  if (auth.me() || !auth.isAuthenticated()) return decide();
  return auth.loadMe().pipe(map(decide));
};
