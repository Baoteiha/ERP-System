import { stubLocalStorage } from './test-storage';
import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection, EnvironmentInjector, runInInjectionContext } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { PartialMatchRouteSnapshot, Route, Router, UrlSegment, UrlTree, provideRouter } from '@angular/router';
import { Observable, firstValueFrom, isObservable } from 'rxjs';
import { permissionGuard } from './permission.guard';
import { AuthService } from './auth.service';
import { MeResponse } from './models';

describe('permissionGuard', () => {
  let ctrl: HttpTestingController;
  let auth: AuthService;

  beforeEach(() => {
    stubLocalStorage();
    TestBed.configureTestingModule({
      providers: [provideZonelessChangeDetection(), provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    ctrl = TestBed.inject(HttpTestingController);
    auth = TestBed.inject(AuthService);
  });

  const run = (...perms: string[]) =>
    runInInjectionContext(TestBed.inject(EnvironmentInjector), () =>
      permissionGuard(...perms)({} as Route, [] as UrlSegment[], {} as PartialMatchRouteSnapshot),
    ) as Observable<boolean | UrlTree> | boolean | UrlTree;

  const me = (permissions: string[]) =>
    ({ branchIds: [], permissions, permissionsByBranch: {} }) as unknown as MeResponse;

  it('redirects a cashier asking for /users to /', () => {
    auth.me.set(me(['sales:write']));
    const result = run('user:read') as UrlTree;
    expect(TestBed.inject(Router).serializeUrl(result)).toBe('/');
  });

  it('lets an owner through', () => {
    auth.me.set(me(['user:read']));
    expect(run('user:read')).toBe(true);
  });

  it('redirects at a branch whose role lacks the permission', () => {
    auth.me.set({ ...me(['report:read']), branchIds: ['b1', 'b2'],
      permissionsByBranch: { b1: ['report:read'], b2: [] } });
    auth.setActiveBranch('b2');
    const result = run('report:read') as UrlTree;
    expect(TestBed.inject(Router).serializeUrl(result)).toBe('/');
  });

  it('opens company-level pages from any branch the user holds', () => {
    auth.me.set({ ...me(['user:read']), branchIds: ['b1', 'b2'],
      permissionsByBranch: { b1: ['user:read'], b2: [] } });
    auth.setActiveBranch('b2');
    expect(run('user:read')).toBe(true);
  });

  it('loads the identity first when it is not known yet', async () => {
    auth.accessToken.set('a');
    const result = run('user:read');
    expect(isObservable(result)).toBe(true);
    const done = firstValueFrom(result as Observable<boolean | UrlTree>);
    ctrl.expectOne('/api/v1/auth/me').flush(me(['user:read']));
    expect(await done).toBe(true);
  });
});
