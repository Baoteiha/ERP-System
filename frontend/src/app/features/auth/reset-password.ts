import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { IdentityService } from '../../core/api.services';
import { AuthService } from '../../core/auth.service';
import { IconComponent } from '../../shared/icon';
import { RESET_UNAVAILABLE } from './forgot-password';

/** Sets a new password with the token of a mailed reset link (?token=...). */
@Component({
  selector: 'app-reset-password',
  standalone: true,
  imports: [FormsModule, RouterLink, IconComponent],
  template: `
    <div class="auth-page">
      <form class="auth-card card glass" (ngSubmit)="submit()">
        <svg class="logo-mark lg" viewBox="0 0 24 24" aria-hidden="true"><path d="M12 2 21 7v10l-9 5-9-5V7z"/><circle cx="12" cy="12" r="3"/></svg>
        <h1>Choose a new password</h1>
        @if (!token) {
          <p class="soft">This link is incomplete. Open the link from the email again, or ask for a new one.</p>
          <p class="auth-links"><a routerLink="/forgot-password">Request a new link</a></p>
        } @else {
          <p class="soft">At least 8 characters. You will be signed out everywhere, and sign in again with it.</p>

          <div class="field">
            <label for="password">New password</label>
            <div class="pw-wrap">
              <input id="password" class="input" [type]="showPw() ? 'text' : 'password'" name="password"
                     autocomplete="new-password" [(ngModel)]="password" required />
              <button type="button" class="btn btn-sm btn-ghost btn-icon pw-toggle"
                      (click)="showPw.set(!showPw())" [attr.aria-pressed]="showPw()"
                      [attr.aria-label]="showPw() ? 'Hide password' : 'Show password'">
                <app-icon [name]="showPw() ? 'eye-off' : 'eye'" [size]="17" />
              </button>
            </div>
          </div>
          <div class="field">
            <label for="confirm">Repeat the new password</label>
            <input id="confirm" class="input" [type]="showPw() ? 'text' : 'password'" name="confirm"
                   autocomplete="new-password" [(ngModel)]="confirm" required />
          </div>

          @if (error()) {
            <div class="auth-error" role="alert">
              {{ error() }}
              @if (expired()) { <a routerLink="/forgot-password">Request a new link.</a> }
            </div>
          }

          <button class="btn btn-primary btn-lg btn-block" type="submit" [disabled]="loading()">
            @if (loading()) { <span class="spinner"></span> Saving… } @else { Set password }
          </button>
        }
        <p class="auth-links"><a routerLink="/login">Back to sign in</a></p>
      </form>
    </div>
  `,
  styleUrl: './login.scss',
})
export class ResetPasswordComponent {
  private identity = inject(IdentityService);
  private auth = inject(AuthService);
  private router = inject(Router);

  readonly token = inject(ActivatedRoute).snapshot.queryParamMap.get('token');
  password = '';
  confirm = '';
  loading = signal(false);
  error = signal('');
  /** the server refused the token: used, expired, or never issued */
  expired = signal(false);
  showPw = signal(false);

  submit() {
    if (this.loading() || !this.token) return;
    this.expired.set(false);
    if (this.password.length < 8) { this.error.set('Use at least 8 characters.'); return; }
    if (this.password !== this.confirm) { this.error.set('The two passwords differ.'); return; }
    this.error.set('');
    this.loading.set(true);
    this.identity.confirmPasswordReset({ token: this.token, newPassword: this.password }).subscribe({
      next: () => {
        this.loading.set(false);
        // the reset ended the account's sessions; a till signed in as someone else must not stay so, on the
        // server either (logout revokes its refresh token; for the reset account's it is already revoked)
        void this.auth.logout();
        void this.router.navigate(['/login'], { queryParams: { reset: 'done' } });
      },
      error: (err) => {
        this.loading.set(false);
        if (err?.status === 422) {
          this.expired.set(true);
          this.error.set('This link is invalid, already used or expired.');
        } else if (err?.status === 404) {
          this.error.set(RESET_UNAVAILABLE);
        } else if (err?.status === 400) {
          this.error.set('That password is not accepted. Use 8 to 72 characters.');
        } else if (err?.status === 0) {
          this.error.set('Cannot reach the server. Check your connection and try again.');
        } else {
          this.error.set('Unable to set the password. Try again.');
        }
      },
    });
  }
}
