import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { IdentityService } from '../../core/api.services';

/** The server answers 404 while password reset is switched off (prod, until mail can be sent). */
export const RESET_UNAVAILABLE = "Password reset isn't available here. Ask your manager or the owner to reset your password.";

function requestErrorMessage(status: number | undefined): string {
  switch (status) {
    case 400: return 'Enter a valid email address.';
    case 404: return RESET_UNAVAILABLE;
    case 0: return 'Cannot reach the server. Check your connection and try again.';
    default: return 'Unable to send the link. Try again.';
  }
}

/** Asks for a password-reset link; the answer is the same whether or not the email has an account. */
@Component({
  selector: 'app-forgot-password',
  standalone: true,
  imports: [FormsModule, RouterLink],
  template: `
    <div class="auth-page">
      <form class="auth-card card glass" (ngSubmit)="submit()">
        <svg class="logo" viewBox="0 0 24 24" aria-hidden="true"><path d="M12 2 21 7v10l-9 5-9-5V7z"/><circle cx="12" cy="12" r="3"/></svg>
        <h1>Forgot your password?</h1>
        @if (sent()) {
          <p class="soft">If an account uses <strong>{{ email.trim() }}</strong>, a link to choose a new password
            is on its way. It works once, and only for a short while.</p>
        } @else {
          <p class="soft">Enter your account's email and we will send you a link to choose a new password.</p>

          <div class="field">
            <label for="email">Email</label>
            <input id="email" class="input" type="email" name="email" autocomplete="username"
                   [(ngModel)]="email" required />
          </div>

          @if (error()) { <div class="auth-error" role="alert">{{ error() }}</div> }

          <button class="btn btn-primary btn-lg btn-block" type="submit" [disabled]="loading()">
            @if (loading()) { <span class="spinner"></span> Sending… } @else { Send reset link }
          </button>
        }
        <p class="auth-links"><a routerLink="/login">Back to sign in</a></p>
      </form>
    </div>
  `,
  styleUrl: './login.scss',
})
export class ForgotPasswordComponent {
  private identity = inject(IdentityService);

  email = '';
  loading = signal(false);
  sent = signal(false);
  error = signal('');

  submit() {
    const email = this.email.trim();
    if (this.loading() || !email) return;
    this.error.set('');
    this.loading.set(true);
    this.identity.requestPasswordReset(email).subscribe({
      next: () => {
        this.loading.set(false);
        this.sent.set(true);
      },
      error: (err) => {
        this.loading.set(false);
        this.error.set(requestErrorMessage(err?.status));
      },
    });
  }
}
