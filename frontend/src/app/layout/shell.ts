import { Component, computed, inject, signal } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { AuthService } from '../core/auth.service';
import { OrganizationService } from '../core/api.services';
import { BranchResponse } from '../core/models';

interface NavItem { label: string; icon: string; path: string; perms?: string[]; }
interface NavSection { title: string; items: NavItem[]; }

@Component({
  selector: 'app-shell',
  standalone: true,
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  template: `
    <div class="shell" [class.nav-open]="mobileNav()">
      <!-- Sidebar -->
      <aside class="nav">
        <div class="brand">
          <span class="logo">☕</span>
          <div class="brand-txt"><b>ERP-Cafe</b><small>Coffee operations</small></div>
        </div>

        <nav class="nav-scroll">
          @for (section of visibleSections(); track section.title) {
            <div class="nav-section">
              <div class="nav-title">{{ section.title }}</div>
              @for (item of section.items; track item.path) {
                <a [routerLink]="item.path" routerLinkActive="active"
                   [routerLinkActiveOptions]="{ exact: item.path === '/' }"
                   class="nav-link" (click)="mobileNav.set(false)">
                  <span class="nav-ic">{{ item.icon }}</span>{{ item.label }}
                </a>
              }
            </div>
          }
        </nav>

        <div class="nav-foot">
          <div class="user">
            <div class="avatar">{{ initials() }}</div>
            <div class="user-txt">
              <b>{{ auth.me()?.email || 'User' }}</b>
              <small>{{ (auth.me()?.roles || []).join(', ') || '—' }}</small>
            </div>
          </div>
          <button class="btn btn-sm btn-ghost" (click)="logout()" title="Sign out">⏻</button>
        </div>
      </aside>

      <!-- Main -->
      <div class="main">
        <header class="topbar">
          <button class="btn btn-icon btn-ghost hamburger" (click)="mobileNav.set(!mobileNav())">☰</button>
          <div class="spacer"></div>

          <div class="branch-switch">
            <span class="bs-label">Branch</span>
            <select class="select" [value]="auth.activeBranchId() || ''" (change)="onBranchChange($event)">
              @if (!branches().length) { <option value="">No branches</option> }
              @for (b of branches(); track b.id) {
                <option [value]="b.id">{{ b.name }}</option>
              }
            </select>
          </div>
        </header>

        <main class="content">
          <router-outlet />
        </main>
      </div>

      @if (mobileNav()) { <div class="scrim" (click)="mobileNav.set(false)"></div> }
    </div>
  `,
  styleUrl: './shell.scss',
})
export class ShellComponent {
  auth = inject(AuthService);
  private org = inject(OrganizationService);

  mobileNav = signal(false);
  branches = signal<BranchResponse[]>([]);

  private readonly sections: NavSection[] = [
    { title: 'Overview', items: [
      { label: 'Dashboard', icon: '▦', path: '/' },
    ]},
    { title: 'Point of Sale', items: [
      { label: 'New Order', icon: '🛒', path: '/pos', perms: ['sales:write'] },
      { label: 'Orders', icon: '🧾', path: '/orders', perms: ['sales:read'] },
    ]},
    { title: 'Catalog', items: [
      { label: 'Products', icon: '🥤', path: '/catalog/products', perms: ['catalog:read'] },
      { label: 'Categories', icon: '🏷', path: '/catalog/categories', perms: ['catalog:read'] },
      { label: 'Modifiers', icon: '⚙', path: '/catalog/modifiers', perms: ['catalog:read'] },
    ]},
    { title: 'Inventory', items: [
      { label: 'Stock', icon: '📦', path: '/inventory/stock', perms: ['inventory:read'] },
      { label: 'Ingredients', icon: '🧂', path: '/inventory/ingredients', perms: ['inventory:read'] },
      { label: 'Suppliers', icon: '🚚', path: '/inventory/suppliers', perms: ['inventory:read'] },
      { label: 'Purchase Orders', icon: '📋', path: '/inventory/purchase-orders', perms: ['purchasing:read'] },
    ]},
    { title: 'Organization', items: [
      { label: 'Companies', icon: '🏢', path: '/org/companies', perms: ['company:read'] },
      { label: 'Branches', icon: '📍', path: '/org/branches', perms: ['branch:read'] },
      { label: 'Users', icon: '👤', path: '/org/users', perms: ['user:read'] },
      { label: 'Roles', icon: '🔑', path: '/org/roles', perms: ['role:read'] },
    ]},
  ];

  visibleSections = computed(() => {
    this.auth.me(); // track permission changes
    return this.sections
      .map((s) => ({ ...s, items: s.items.filter((i) => !i.perms || this.auth.canAny(...i.perms)) }))
      .filter((s) => s.items.length > 0);
  });

  initials = computed(() => {
    const email = this.auth.me()?.email ?? '';
    return email.slice(0, 2).toUpperCase() || 'U';
  });

  constructor() {
    // hydrate identity + branch list on entry
    if (this.auth.isAuthenticated() && !this.auth.me()) {
      this.auth.loadMe().subscribe({ next: () => this.loadBranches() });
    } else {
      this.loadBranches();
    }
  }

  private loadBranches() {
    this.org.listBranches().subscribe({
      next: (page) => {
        const mine = this.auth.me()?.branchIds ?? [];
        const list = mine.length ? page.content.filter((b) => mine.includes(b.id)) : page.content;
        this.branches.set(list.length ? list : page.content);
        if (!this.auth.activeBranchId() && this.branches().length) {
          this.auth.setActiveBranch(this.branches()[0].id);
        }
      },
      error: () => {},
    });
  }

  onBranchChange(e: Event) {
    const id = (e.target as HTMLSelectElement).value;
    if (id) this.auth.setActiveBranch(id);
  }

  logout() { this.auth.logout(); location.href = '/login'; }
}
