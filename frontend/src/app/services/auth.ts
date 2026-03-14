import { Injectable, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Router } from '@angular/router';
import { tap } from 'rxjs/operators';

export interface User {
  _id: string;
  username: string;
  email: string;
  role: string;
}

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly TOKEN_KEY = 'access_token';
  currentUser = signal<User | null>(null);

  constructor(private http: HttpClient, private router: Router) {}

  register(username: string, email: string, password: string, role: string) {
    return this.http.post<{ success: boolean; message: string }>(
      '/api/auth/register', { username, email, password, role }
    );
  }

  login(email: string, password: string) {
    return this.http.post<{ success: boolean; data: { accessToken: string; user: User } }>(
      '/api/auth/login', { email, password }
    ).pipe(tap(res => {
      localStorage.setItem(this.TOKEN_KEY, res.data.accessToken);
      this.currentUser.set(res.data.user);
    }));
  }

  logout() {
    return this.http.post('/api/auth/logout', {}).pipe(tap(() => {
      localStorage.removeItem(this.TOKEN_KEY);
      this.currentUser.set(null);
      this.router.navigate(['/login']);
    }));
  }

  refresh() {
    return this.http.post<{ success: boolean; data: { accessToken: string } }>(
      '/api/auth/refresh', {}
    ).pipe(tap(res => {
      localStorage.setItem(this.TOKEN_KEY, res.data.accessToken);
    }));
  }

  getToken(): string | null {
    return localStorage.getItem(this.TOKEN_KEY);
  }

  isLoggedIn(): boolean {
    return !!this.getToken();
  }

  loadCurrentUser() {
    return this.http.get<{ success: boolean; data: User }>('/api/auth/me')
      .pipe(tap(res => this.currentUser.set(res.data)));
  }
}
