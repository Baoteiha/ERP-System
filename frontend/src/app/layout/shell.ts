import { Component, HostListener, computed, inject, signal } from '@angular/core';
import { NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { filter } from 'rxjs';
import { AuthService } from '../core/auth.service';
import { OrganizationService } from '../core/api.services';
import { BranchResponse } from '../core/models';
import { IconComponent } from '../shared/icon';

interface NavItem { label: string; icon: string; path: string; perms?: string[]; }
interface NavSection { title: string; items: NavItem[]; }

@Component({
  selector: 'app-shell',
  standalone: true,
  imports: [RouterOutlet, RouterLink, RouterLinkActive, IconComponent],
  template: `
    <a class="skip-link" href="#main-content">Skip to main content</a>
    <div class="shell" [class.nav-open]="mobileNav()">
      <!-- Sidebar -->
      <aside class="nav" id="app-nav" aria-label="Primary">
        <div class="brand">
          <span class="logo" aria-hidden="true">
            <app-icon name="coffee" [size]="22" [stroke]="1.8" />
          </span>
          <div class="brand-txt"><b>ERP-Cafe</b><small>Coffee operations</small></div>
        </div>

        <nav class="nav-scroll">
          @for (section of visibleSections(); track section.title) {
            <div class="nav-section">
              <div class="nav-title">{{ section.title }}</div>
              @for (item of section.items; track item.path) {
                <a [routerLink]="item.path" routerLinkActive="active"
                   ariaCurrentWhenActive="page"
                   [routerLinkActiveOptions]="{ exact: item.path === '/' }"
                   class="nav-link" (click)="mobileNav.set(false)">
                  <span class="nav-ic" aria-hidden="true"><app-icon [name]="item.icon" [size]="19" /></span>{{ item.label }}
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
          <button class="btn btn-sm btn-ghost btn-icon" (click)="logout()" title="Sign out" aria-label="Sign out">
            <app-icon name="logout" [size]="15" />
          </button>
        </div>
      </aside>

      <!-- Main -->
      <div class="main">
        <header class="topbar">
          <button class="btn btn-icon btn-ghost hamburger" (click)="mobileNav.set(!mobileNav())"
                  aria-label="Toggle menu" aria-controls="app-nav" [attr.aria-expanded]="mobileNav()">
            <app-icon name="menu" [size]="18" />
          </button>
          <div class="spacer"></div>

          <div class="branch-switch">
            <app-icon class="bs-ic" name="pin" [size]="15" aria-hidden="true" />
            <span class="bs-label">Branch</span>
            <select class="select" [value]="auth.activeBranchId() || ''" (change)="onBranchChange($event)" aria-label="Active branch">
              @if (!branches().length) { <option value="">No branches</option> }
              @for (b of branches(); track b.id) {
                <option [value]="b.id">{{ b.name }}</option>
              }
            </select>
          </div>
        </header>

        <main class="content" id="main-content" tabindex="-1">
          <router-outlet />
        </main>
      </div>

      @if (mobileNav()) { <div class="scrim" (click)="mobileNav.set(false)" aria-hidden="true"></div> }
    </div>
  `,
  styleUrl: './shell.scss',
})
export class ShellComponent {
  auth = inject(AuthService);
  private org = inject(OrganizationService);
  private router = inject(Router);

  mobileNav = signal(false);
  branches = signal<BranchResponse[]>([]);

  private readonly sections: NavSection[] = [
    { title: 'Overview', items: [
      { label: 'Dashboard', path: '/', icon: 'grid' },
    ]},
    { title: 'Point of Sale', items: [
      { label: 'New Order', path: '/pos', perms: ['sales:write'], icon: 'cart' },
      { label: 'Orders', path: '/orders', perms: ['sales:read'], icon: 'receipt' },
    ]},
    { title: 'Catalog', items: [
      { label: 'Products', path: '/catalog/products', perms: ['catalog:read'], icon: 'coffee' },
      { label: 'Categories', path: '/catalog/categories', perms: ['catalog:read'], icon: 'tag' },
      { label: 'Modifiers', path: '/catalog/modifiers', perms: ['catalog:read'], icon: 'sliders' },
    ]},
    { title: 'Inventory', items: [
      { label: 'Stock', path: '/inventory/stock', perms: ['inventory:read'], icon: 'box' },
      { label: 'Ingredients', path: '/inventory/ingredients', perms: ['inventory:read'], icon: 'flask' },
      { label: 'Suppliers', path: '/inventory/suppliers', perms: ['inventory:read'], icon: 'truck' },
      { label: 'Purchase Orders', path: '/inventory/purchase-orders', perms: ['purchasing:read'], icon: 'clipboard' },
    ]},
    { title: 'Staff', items: [
      { label: 'Team', path: '/staff/team', perms: ['staff:read'], icon: 'users' },
      { label: 'Shifts', path: '/staff/shifts', perms: ['staff:read'], icon: 'clock' },
    ]},
    { title: 'Insights', items: [
      { label: 'Reports', path: '/reports', perms: ['report:read'], icon: 'trending-up' },
    ]},
    { title: 'Organization', items: [
      { label: 'Companies', path: '/org/companies', perms: ['company:read'], icon: 'building' },
      { label: 'Branches', path: '/org/branches', perms: ['branch:read'], icon: 'pin' },
      { label: 'Users', path: '/org/users', perms: ['user:read'], icon: 'user' },
      { label: 'Roles', path: '/org/roles', perms: ['role:read'], icon: 'key' },
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

    // Move focus to the content region after in-app navigation so screen
    // reader and keyboard users land on the new page, not stale nav state.
    let firstNav = true;
    this.router.events.pipe(filter((e) => e instanceof NavigationEnd)).subscribe(() => {
      if (firstNav) { firstNav = false; return; }
      document.getElementById('main-content')?.focus({ preventScroll: false });
    });
  }

  @HostListener('document:keydown.escape')
  onEscape() { if (this.mobileNav()) this.mobileNav.set(false); }

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
