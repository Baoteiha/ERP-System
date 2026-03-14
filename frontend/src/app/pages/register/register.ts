import { Component } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { AuthService } from '../../services/auth';
import { CommonModule } from '@angular/common';

@Component({
  selector: 'app-register',
  standalone: true,
  imports: [FormsModule, CommonModule, RouterLink],
  templateUrl: './register.html',
})
export class Register {
  username = '';
  email = '';
  password = '';
  role = 'user';
  loading = false;
  error = '';

  roles = [
    { value: 'admin', label: 'Administrator' },
    { value: 'manager', label: 'Manager' },
    { value: 'user', label: 'User' },
  ];

  constructor(private auth: AuthService, private router: Router) {}

  submit() {
    if (!this.username || !this.email || !this.password) return;
    this.loading = true;
    this.error = '';
    this.auth.register(this.username, this.email, this.password, this.role).subscribe({
      next: () => this.router.navigate(['/login']),
      error: (err) => {
        this.error = err.error?.message || 'Registration failed';
        this.loading = false;
      }
    });
  }
}
