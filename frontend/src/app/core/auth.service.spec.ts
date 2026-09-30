import { stubLocalStorage } from './test-storage';
import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { AuthService } from './auth.service';
import { MeResponse } from './models';

describe('AuthService', () => {
  let ctrl: HttpTestingController;

  const setup = () => {
    TestBed.configureTestingModule({
      providers: [provideZonelessChangeDetection(), provideHttpClient(), provideHttpClientTesting()],
    });
    ctrl = TestBed.inject(HttpTestingController);
    return TestBed.inject(AuthService);
  };

  beforeEach(() => stubLocalStorage());

  it('logout clears both tokens and the stored branch', async () => {
    localStorage.setItem('erpcafe.access', 'a');
    localStorage.setItem('erpcafe.refresh', 'r');
    localStorage.setItem('erpcafe.branch', 'b1');
    const auth = setup();

    const done = auth.logout();
    ctrl.expectOne('/api/v1/auth/logout').flush({});
    await done;

    expect(localStorage.getItem('erpcafe.access')).toBeNull();
    expect(localStorage.getItem('erpcafe.refresh')).toBeNull();
    expect(localStorage.getItem('erpcafe.branch')).toBeNull();
    expect(auth.activeBranchId()).toBeNull();
  });

  it('replaces a stored branch that is not in the user branches', () => {
    localStorage.setItem('erpcafe.branch', 'b1');
    const auth = setup();

    auth.loadMe().subscribe();
    ctrl.expectOne('/api/v1/auth/me').flush({ branchIds: ['b2'], permissions: [] } as unknown as MeResponse);

    expect(auth.activeBranchId()).toBe('b2');
    expect(localStorage.getItem('erpcafe.branch')).toBe('b2');
  });
});
