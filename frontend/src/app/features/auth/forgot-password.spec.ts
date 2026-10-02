import { stubLocalStorage } from '../../core/test-storage';
import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { authHeaderInterceptor, errorInterceptor } from '../../core/http.interceptors';
import { ToastService } from '../../core/toast.service';
import { ForgotPasswordComponent } from './forgot-password';

describe('ForgotPasswordComponent', () => {
  let ctrl: HttpTestingController;
  let page: ForgotPasswordComponent;

  beforeEach(() => {
    stubLocalStorage();
    localStorage.setItem('erpcafe.access', 'stale');
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        provideHttpClient(withInterceptors([authHeaderInterceptor, errorInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    ctrl = TestBed.inject(HttpTestingController);
    page = TestBed.createComponent(ForgotPasswordComponent).componentInstance;
  });

  afterEach(() => ctrl.verify());

  it('asks for a link, signed out, and confirms alike whatever the email', () => {
    page.email = ' cashier@esscafe.vn ';
    page.submit();

    const req = ctrl.expectOne('/api/v1/auth/password-reset/request');
    expect(req.request.body).toEqual({ email: 'cashier@esscafe.vn' });
    expect(req.request.headers.has('Authorization')).toBe(false);
    req.flush(null, { status: 202, statusText: 'Accepted' });

    expect(page.sent()).toBe(true);
    expect(page.error()).toBe('');
  });

  it('sends nothing without an email', () => {
    page.submit();

    ctrl.expectNone('/api/v1/auth/password-reset/request');
    expect(page.sent()).toBe(false);
  });

  it('reports a malformed email on the page, not in a toast', () => {
    const toast = vi.spyOn(TestBed.inject(ToastService), 'error');
    page.email = 'not-an-email';
    page.submit();

    ctrl.expectOne('/api/v1/auth/password-reset/request')
      .flush({ code: 'VALIDATION' }, { status: 400, statusText: 'Bad Request' });

    expect(page.sent()).toBe(false);
    expect(page.error()).toBe('Enter a valid email address.');
    expect(toast).not.toHaveBeenCalled();
  });

  it('says plainly when password reset is switched off', () => {
    page.email = 'cashier@esscafe.vn';
    page.submit();

    ctrl.expectOne('/api/v1/auth/password-reset/request')
      .flush({ code: 'NOT_FOUND' }, { status: 404, statusText: 'Not Found' });

    expect(page.sent()).toBe(false);
    expect(page.error()).toBe("Password reset isn't available here. Ask your manager or the owner to reset your password.");
  });

  it('reports an unreachable server', () => {
    page.email = 'cashier@esscafe.vn';
    page.submit();

    ctrl.expectOne('/api/v1/auth/password-reset/request').error(new ProgressEvent('error'));

    expect(page.error()).toBe('Cannot reach the server. Check your connection and try again.');
  });
});
