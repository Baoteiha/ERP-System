import { stubLocalStorage } from '../core/test-storage';
import { TestBed } from '@angular/core/testing';
import { Component, provideZonelessChangeDetection } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Router, provideRouter } from '@angular/router';
import { AuthService } from '../core/auth.service';
import { ShellComponent } from './shell';

@Component({ template: '' })
class BlankComponent {}

describe('ShellComponent branch switch', () => {
  let router: Router;
  let shell: ShellComponent;

  beforeEach(async () => {
    stubLocalStorage();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([{ path: 'reports', component: BlankComponent }, { path: '', component: BlankComponent }]),
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    });
    const auth = TestBed.inject(AuthService);
    // reads reports at b1 only
    auth.me.set({
      userId: 'u', email: 'a@b.c', companyId: 'co', branchIds: ['b1', 'b2', 'b3'], roles: [],
      permissions: ['report:read'], permissionsByBranch: { b1: ['report:read'], b2: [], b3: ['report:read'] },
    });
    auth.setActiveBranch('b1');
    router = TestBed.inject(Router);
    await router.navigateByUrl('/reports');
    shell = TestBed.createComponent(ShellComponent).componentInstance;
  });

  const switchTo = async (branchId: string) => {
    shell.onBranchChange({ target: { value: branchId } } as unknown as Event);
    await new Promise((resolve) => setTimeout(resolve));
  };

  it('leaves a page the new branch role does not open', async () => {
    await switchTo('b2');
    expect(router.url).toBe('/');
  });

  it('stays on a page the new branch role opens', async () => {
    await switchTo('b3');
    expect(router.url).toBe('/reports');
  });

  it('drops a branch from the selector once the user no longer holds it', () => {
    const branch = (id: string) => ({ id, companyId: 'co', name: id, code: id, active: true });
    TestBed.inject(HttpTestingController).expectOne((r) => r.url.endsWith('/branches'))
      .flush({ content: ['b1', 'b2', 'b3'].map(branch), page: 0, size: 20, totalElements: 3, totalPages: 1 });
    expect(shell.branches().map((b) => b.id)).toEqual(['b1', 'b2', 'b3']);

    const auth = TestBed.inject(AuthService);
    auth.me.set({ ...auth.me()!, branchIds: ['b1', 'b3'] });

    expect(shell.branches().map((b) => b.id)).toEqual(['b1', 'b3']);
  });
});
