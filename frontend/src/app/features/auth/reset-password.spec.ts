import { stubLocalStorage } from '../../core/test-storage';
import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { AuthService } from '../../core/auth.service';
import { ResetPasswordComponent } from './reset-password';

describe('ResetPasswordComponent', () => {
  let ctrl: HttpTestingController;

  const open = (token: string | null) => {
    stubLocalStorage();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: ActivatedRoute, useValue: { snapshot: { queryParamMap: convertToParamMap(token ? { token } : {}) } } },
      ],
    });
    ctrl = TestBed.inject(HttpTestingController);
    return TestBed.createComponent(ResetPasswordComponent).componentInstance;
  };

  afterEach(() => ctrl.verify());

  it('sets the password with the link token, signs this browser out, and returns to sign-in', () => {
    const page = open('abc123');
    const auth = TestBed.inject(AuthService);
    auth.accessToken.set('old');
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    page.password = 'new-pass-1';
    page.confirm = 'new-pass-1';

    page.submit();
    const req = ctrl.expectOne('/api/v1/auth/password-reset/confirm');
    expect(req.request.body).toEqual({ token: 'abc123', newPassword: 'new-pass-1' });
    req.flush(null, { status: 204, statusText: 'No Content' });

    expect(auth.accessToken()).toBeNull();
    expect(navigate).toHaveBeenCalledWith(['/login'], { queryParams: { reset: 'done' } });
  });

  it('refuses a short or repeated-wrong password without calling the server', () => {
    const page = open('abc123');

    page.password = 'short';
    page.confirm = 'short';
    page.submit();
    expect(page.error()).toBe('Use at least 8 characters.');

    page.password = 'new-pass-1';
    page.confirm = 'new-pass-2';
    page.submit();
    expect(page.error()).toBe('The two passwords differ.');

    ctrl.expectNone('/api/v1/auth/password-reset/confirm');
  });

  it('explains a used or expired link and offers a new one', () => {
    const page = open('abc123');
    page.password = 'new-pass-1';
    page.confirm = 'new-pass-1';

    page.submit();
    ctrl.expectOne('/api/v1/auth/password-reset/confirm')
      .flush({ code: 'BUSINESS_RULE' }, { status: 422, statusText: 'Unprocessable Content' });

    expect(page.error()).toBe('This link is invalid, already used or expired.');
    expect(page.expired()).toBe(true);
  });

  it('says plainly when password reset is switched off', () => {
    const page = open('abc123');
    page.password = 'new-pass-1';
    page.confirm = 'new-pass-1';

    page.submit();
    ctrl.expectOne('/api/v1/auth/password-reset/confirm')
      .flush({ code: 'NOT_FOUND' }, { status: 404, statusText: 'Not Found' });

    expect(page.error()).toBe("Password reset isn't available here. Ask your manager or the owner to reset your password.");
    expect(page.expired()).toBe(false);
  });

  it('without a token, calls nothing', () => {
    const page = open(null);
    page.password = 'new-pass-1';
    page.confirm = 'new-pass-1';

    page.submit();

    expect(page.token).toBeNull();
    ctrl.expectNone('/api/v1/auth/password-reset/confirm');
  });
});
