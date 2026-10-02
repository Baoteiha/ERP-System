import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { AuthService } from '../../core/auth.service';
import { IconComponent } from '../../shared/icon';

function loginErrorMessage(status: number | undefined): string {
  switch (status) {
    case 401: return 'Invalid email or password.';
    case 429: return 'Too many attempts. Wait a minute and try again.';
    case 0: return 'Cannot reach the server. Check your connection and try again.';
    default: return 'Unable to sign in. Try again.';
  }
}

@Component({
  selector: 'app-login',
  standalone: true,
  imports: [FormsModule, IconComponent, RouterLink],
  template: `
    <div class="auth-page">
      <form class="auth-card card glass" (ngSubmit)="submit()">
        <svg class="logo-mark lg" viewBox="0 0 24 24" aria-hidden="true"><path d="M12 2 21 7v10l-9 5-9-5V7z"/><circle cx="12" cy="12" r="3"/></svg>
        <h1>Sign in to ERP-Cafe</h1>
        <p class="soft">Use your work account to continue</p>

          @if (resetDone && !error()) {
            <div class="auth-ok" role="status">Your password was changed. Sign in with the new one.</div>
          }

          <div class="field">
            <label for="email">Email</label>
            <input id="email" class="input" type="email" name="email" autocomplete="username"
                   [(ngModel)]="email" placeholder="you@company.com" required />
          </div>
          <div class="field">
            <label for="password">Password</label>
            <div class="pw-wrap">
              <input id="password" class="input" [type]="showPw() ? 'text' : 'password'" name="password"
                     autocomplete="current-password" [(ngModel)]="password" placeholder="••••••••" required />
              <button type="button" class="btn btn-sm btn-ghost btn-icon pw-toggle"
                      (click)="showPw.set(!showPw())" [attr.aria-pressed]="showPw()"
                      [attr.aria-label]="showPw() ? 'Hide password' : 'Show password'">
                <app-icon [name]="showPw() ? 'eye-off' : 'eye'" [size]="17" />
              </button>
            </div>
            <a class="auth-forgot" routerLink="/forgot-password">Forgot password?</a>
          </div>

          @if (error()) { <div class="auth-error" role="alert">{{ error() }}</div> }

          <button class="btn btn-primary btn-lg btn-block" type="submit" [disabled]="loading()">
            @if (loading()) { <span class="spinner"></span> Signing in… } @else { Sign in }
          </button>
      </form>
    </div>
  `,
  styleUrl: './login.scss',
})
export class LoginComponent {
  private auth = inject(AuthService);
  private router = inject(Router);
  /** arrived from a completed password reset */
  readonly resetDone = inject(ActivatedRoute).snapshot.queryParamMap.get('reset') === 'done';

  email = '';
  password = '';
  loading = signal(false);
  error = signal('');
  showPw = signal(false);

  submit() {
    if (this.loading()) return;
    this.error.set('');
    this.loading.set(true);
    this.auth.login({ email: this.email, password: this.password }).subscribe({
      next: () => {
        this.auth.loadMe().subscribe({
          next: () => { this.loading.set(false); this.router.navigateByUrl('/'); },
          error: () => { this.loading.set(false); this.router.navigateByUrl('/'); },
        });
      },
      error: (err) => {
        this.loading.set(false);
        this.error.set(loginErrorMessage(err?.status));
      },
    });
  }
}
