import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { AuthService } from '../../core/auth.service';

@Component({
  selector: 'app-login',
  standalone: true,
  imports: [FormsModule],
  template: `
    <div class="auth-wrap">
      <div class="auth-art">
        <div class="art-inner">
          <div class="art-logo">☕</div>
          <h1>ERP-Cafe</h1>
          <p>Multi-branch operations for coffee retail — catalog, inventory, purchasing and point of sale in one place.</p>
          <ul class="art-list">
            <li>◦ Branch-scoped stock &amp; costing</li>
            <li>◦ Recipe-driven COGS on every sale</li>
            <li>◦ Purchase-to-receive workflow</li>
          </ul>
        </div>
      </div>

      <div class="auth-form">
        <form class="auth-card" (ngSubmit)="submit()">
          <h2>Welcome back</h2>
          <p class="soft">Sign in to your workspace</p>

          <div class="field">
            <label for="email">Email</label>
            <input id="email" class="input" type="email" name="email" autocomplete="username"
                   [(ngModel)]="email" placeholder="admin@esscafe.vn" required />
          </div>
          <div class="field">
            <label for="password">Password</label>
            <input id="password" class="input" type="password" name="password" autocomplete="current-password"
                   [(ngModel)]="password" placeholder="••••••••" required />
          </div>

          @if (error()) { <div class="auth-error">{{ error() }}</div> }

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
