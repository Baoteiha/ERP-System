import { Component } from '@angular/core';
import { AuthService } from '../../services/auth';
import { CommonModule } from '@angular/common';

@Component({
  selector: 'app-header',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './header.html',
  styleUrl: './header.scss',
})
export class Header {
  constructor(public auth: AuthService) {}

  logout() {
    this.auth.logout().subscribe();
  }
}
