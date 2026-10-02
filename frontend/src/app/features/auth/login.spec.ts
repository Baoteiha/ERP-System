import { stubLocalStorage } from '../../core/test-storage';
import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { LoginComponent } from './login';

describe('LoginComponent', () => {
  let ctrl: HttpTestingController;
  let login: LoginComponent;

  beforeEach(() => {
    stubLocalStorage();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    });
    ctrl = TestBed.inject(HttpTestingController);
    login = TestBed.createComponent(LoginComponent).componentInstance;
  });

  const failWith = (status: number) => {
    login.submit();
    ctrl.expectOne('/api/v1/auth/login').flush({ code: 'X' }, { status, statusText: 'err' });
  };

  it('starts with an empty form', () => {
    expect(login.email).toBe('');
    expect(login.password).toBe('');
  });

  it('reports bad credentials on 401', () => {
    failWith(401);
    expect(login.error()).toBe('Invalid email or password.');
  });

  it('reports throttling on 429', () => {
    failWith(429);
    expect(login.error()).toBe('Too many attempts. Wait a minute and try again.');
  });

  it('reports an unreachable server on status 0', () => {
    login.submit();
    ctrl.expectOne('/api/v1/auth/login').error(new ProgressEvent('error'));
    expect(login.error()).toBe('Cannot reach the server. Check your connection and try again.');
  });
});

describe('LoginComponent after a password reset', () => {
  const open = (query: Record<string, string>) => {
    stubLocalStorage();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: ActivatedRoute, useValue: { snapshot: { queryParamMap: convertToParamMap(query) } } },
      ],
    });
    return TestBed.createComponent(LoginComponent).componentInstance;
  };

  it('says the password was changed', () => {
    expect(open({ reset: 'done' }).resetDone).toBe(true);
  });

  it('says nothing on an ordinary visit', () => {
    expect(open({}).resetDone).toBe(false);
  });
});
