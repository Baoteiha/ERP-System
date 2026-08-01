import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { AuthService } from '../../core/auth.service';
import { IconComponent } from '../../shared/icon';

@Component({
  selector: 'app-login',
  standalone: true,
  imports: [FormsModule, IconComponent],
  template: `
    <div class="auth-wrap">
      <div class="auth-art">
        <div class="art-inner">
          <div class="art-logo">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><path d="M10 2v2"/><path d="M14 2v2"/><path d="M6 2v2"/><path d="M16 8a1 1 0 0 1 1 1v8a4 4 0 0 1-4 4H7a4 4 0 0 1-4-4V9a1 1 0 0 1 1-1h14a4 4 0 1 1 0 8h-1"/></svg>
          </div>
          <p class="script art-word">ERP-Cafe</p>
          <p>Multi-branch operations for coffee retail — catalog, inventory, purchasing and point of sale in one place.</p>
          <ul class="art-list">
            <li><app-icon name="check" [size]="16" /> Branch-scoped stock &amp; costing</li>
            <li><app-icon name="check" [size]="16" /> Recipe-driven COGS on every sale</li>
            <li><app-icon name="check" [size]="16" /> Purchase-to-receive workflow</li>
          </ul>
        </div>
      </div>

      <div class="auth-form">
        <form class="auth-card" (ngSubmit)="submit()">
          <h1>Welcome back</h1>
          <p class="soft">Sign in to your workspace</p>

          <div class="field">
            <label for="email">Email</label>
            <input id="email" class="input" type="email" name="email" autocomplete="username"
                   [(ngModel)]="email" placeholder="admin@esscafe.vn" required />
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
          </div>

          @if (error()) { <div class="auth-error" role="alert">{{ error() }}</div> }

          <button class="btn btn-primary btn-lg btn-block" type="submit" [disabled]="loading()">
            @if (loading()) { <span class="spinner"></span> Signing in… } @else { Sign in }
          </button>

          <p class="hint-line">Dev bootstrap: <code>admin&#64;esscafe.vn</code> / <code>admin1234</code></p>
        </form>
      </div>
    </div>
  `,
  styleUrl: './login.scss',
})
export class LoginComponent {
  private auth = inject(AuthService);
  private router = inject(Router);

  email = 'admin@esscafe.vn';
  password = 'admin1234';
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
        this.error.set(err?.status === 401 || err?.status === 400
          ? 'Invalid email or password.'
          : 'Unable to sign in. Is the backend running on :8090?');
      },
    });
  }
}
