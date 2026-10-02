import { ElementRef, Component, HostListener, Injector, afterNextRender, computed, inject, signal } from '@angular/core';
import { NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { filter } from 'rxjs';
import { AuthService } from '../core/auth.service';
import { OrganizationService } from '../core/api.services';
import { BranchResponse } from '../core/models';
import { IconComponent } from '../shared/icon';
import { DensityService } from '../core/density.service';
import { ThemeService } from '../core/theme.service';
import { readPref, writePref } from '../core/prefs';
import { NAV_SECTIONS } from './nav';
import { CommandPaletteComponent, PaletteService } from './command-palette';

const SIDEBAR_KEY = 'erpcafe.sidebar';

@Component({
  selector: 'app-shell',
  standalone: true,
  imports: [RouterOutlet, RouterLink, RouterLinkActive, IconComponent, CommandPaletteComponent],
  template: `
    <a class="skip-link" href="#main-content">Skip to main content</a>
    <div class="shell" [class.nav-open]="mobileNav()" [class.railed]="railed()">
      <aside class="nav" id="app-nav" aria-label="Primary">
        <div class="brand">
          <svg class="logo" viewBox="0 0 24 24" aria-hidden="true"><path d="M12 2 21 7v10l-9 5-9-5V7z"/><circle cx="12" cy="12" r="3"/></svg>
          <span class="brand-txt">ERP-Cafe</span>
        </div>
        <nav class="nav-scroll">
          @for (section of visibleSections(); track section.title) {
            <div class="nav-section">
              <div class="nav-title">{{ section.title }}</div>
              @for (item of section.items; track item.path) {
                <a [routerLink]="item.path" routerLinkActive="active" ariaCurrentWhenActive="page"
                   [routerLinkActiveOptions]="{ exact: item.path === '/' }" class="nav-link"
                   [attr.title]="railed() ? item.label : null" (click)="mobileNav.set(false)">
                  <app-icon [name]="item.icon" [size]="18" aria-hidden="true" /><span class="nav-label">{{ item.label }}</span>
                </a>
              }
            </div>
          }
        </nav>
        <button type="button" class="btn btn-ghost btn-icon rail-toggle" (click)="toggleRail()"
                [attr.aria-label]="collapsed() ? 'Expand sidebar' : 'Collapse sidebar'" [attr.aria-pressed]="collapsed()">
          <app-icon name="panel-left" [size]="17" />
        </button>
      </aside>

      <div class="main">
        <header class="topbar" [class.glass]="scrolled()">
          <button class="btn btn-icon btn-ghost hamburger" (click)="mobileNav.set(!mobileNav())"
                  aria-label="Toggle menu" aria-controls="app-nav" [attr.aria-expanded]="mobileNav()"><app-icon name="menu" [size]="18" /></button>
          <div class="crumbs">
            @if (pageTitle().label) {
              <span class="crumb-section">{{ pageTitle().section }}</span><span class="crumb-sep" aria-hidden="true">/</span><b>{{ pageTitle().label }}</b>
            }
          </div>
          <button type="button" class="palette-trigger" (click)="palette.open()" aria-label="Search or jump to (Control K)">
            <app-icon name="search" [size]="15" /><span class="pt-txt">Search or jump to…</span><kbd>⌘K</kbd>
          </button>
          <div class="spacer"></div>
          <div class="branch-switch">
            <select class="select" [value]="auth.activeBranchId() || ''" (change)="onBranchChange($event)" aria-label="Active branch">
              @if (!branches().length) { <option value="">No branches</option> }
              @for (b of branches(); track b.id) {
                <option [value]="b.id">{{ b.name }} · {{ b.code }}</option>
              }
            </select>
          </div>
          <button type="button" class="btn btn-ghost btn-icon" (click)="theme.cycle()"
                  [attr.aria-label]="'Theme: ' + theme.pref() + '. Change theme'">
            <app-icon [name]="theme.pref() === 'system' ? 'monitor' : theme.pref() === 'dark' ? 'moon' : 'sun'" [size]="17" />
          </button>
          <div class="user-menu">
            <button type="button" class="avatar-btn" (click)="toggleUserMenu()" aria-haspopup="menu"
                    [attr.aria-expanded]="userMenu()" aria-label="Account menu"><span class="avatar">{{ initials() }}</span></button>
            @if (userMenu()) {
              <div class="menu glass" role="menu" tabindex="-1" (keydown)="onMenuKey($event)">
                <div class="menu-who"><b class="truncate">{{ auth.me()?.email || 'User' }}</b><small class="truncate">{{ (auth.me()?.roles || []).join(', ') || '—' }}</small></div>
                <button type="button" role="menuitemcheckbox" [attr.aria-checked]="density.pref() === 'compact'" class="menu-item" (click)="density.toggle()">
                  <app-icon name="table" [size]="15" /> Compact tables
                </button>
                <button type="button" role="menuitem" class="menu-item" (click)="logout()"><app-icon name="logout" [size]="15" /> Sign out</button>
              </div>
            }
          </div>
        </header>
        <main class="content" id="main-content" tabindex="-1"><router-outlet /></main>
      </div>
      <app-command-palette [branches]="branches()" />
      @if (mobileNav()) { <div class="scrim" (click)="mobileNav.set(false)" aria-hidden="true"></div> }
    </div>
  `,
  styleUrl: './shell.scss',
})
export class ShellComponent {
  auth = inject(AuthService);
  private org = inject(OrganizationService);
  private router = inject(Router);
  private injector = inject(Injector);
  private host = inject(ElementRef<HTMLElement>);
  theme = inject(ThemeService);
  density = inject(DensityService);
  palette = inject(PaletteService);

  mobileNav = signal(false);
  private allBranches = signal<BranchResponse[]>([]);
  /** the company's branches the user holds; follows /auth/me, so a branch lost mid-session drops out */
  branches = computed(() => {
    const mine = this.auth.me()?.branchIds ?? [];
    return this.allBranches().filter((b) => mine.includes(b.id));
  });

  scrolled = signal(false);
  userMenu = signal(false);
  /** the saved sidebar choice; the POS screen rails the sidebar without changing it */
  collapsed = signal(readPref(SIDEBAR_KEY, ['expanded', 'rail'] as const, 'expanded') === 'rail');
  private url = signal(this.router.url);
  railed = computed(() => {
    const path = this.url().split(/[?#]/)[0];
    return this.collapsed() || path === '/pos' || path.startsWith('/pos/');
  });

  /** the longest nav path the URL sits under, on a segment boundary; '/' matches only exactly */
  pageTitle = computed(() => {
    const path = this.url().split(/[?#]/)[0];
    let best: { section: string; label: string; len: number } | null = null;
    for (const section of NAV_SECTIONS) {
      for (const item of section.items) {
        const hit = item.path === '/' ? path === '/' : path === item.path || path.startsWith(item.path + '/');
        if (hit && (!best || item.path.length > best.len)) best = { section: section.title, label: item.label, len: item.path.length };
      }
    }
    return best ? { section: best.section, label: best.label } : { section: 'ERP-Cafe', label: '' };
  });

  visibleSections = computed(() => {
    this.auth.me(); // track permission changes
    return NAV_SECTIONS
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
      this.url.set(this.router.url);
      if (firstNav) { firstNav = false; return; }
      document.getElementById('main-content')?.focus({ preventScroll: false });
    });
  }

  @HostListener('window:scroll')
  onScroll() { this.scrolled.set(window.scrollY > 4); }

  @HostListener('document:keydown.escape')
  onEscape() {
    if (this.mobileNav()) this.mobileNav.set(false);
    if (this.userMenu()) this.closeUserMenu(true);
  }

  @HostListener('document:click', ['$event'])
  onDocumentClick(e: Event) {
    if (this.userMenu() && !(e.target as Element | null)?.closest('.user-menu')) this.userMenu.set(false);
  }

  toggleUserMenu() {
    if (this.userMenu()) { this.userMenu.set(false); return; }
    this.userMenu.set(true);
    afterNextRender(() => this.menuItems()[0]?.focus(), { injector: this.injector });
  }

  private menuItems(): HTMLElement[] {
    return Array.from((this.host.nativeElement as HTMLElement).querySelectorAll<HTMLElement>('.menu [role^="menuitem"]'));
  }

  private closeUserMenu(restoreFocus: boolean) {
    this.userMenu.set(false);
    if (restoreFocus) (this.host.nativeElement as HTMLElement).querySelector<HTMLElement>('.avatar-btn')?.focus();
  }

  onMenuKey(e: KeyboardEvent) {
    const items = this.menuItems();
    const i = items.indexOf(document.activeElement as HTMLElement);
    const go = (n: number) => { e.preventDefault(); items[(n + items.length) % items.length]?.focus(); };
    switch (e.key) {
      case 'ArrowDown': go(i + 1); break;
      case 'ArrowUp': go(i - 1); break;
      case 'Home': go(0); break;
      case 'End': go(items.length - 1); break;
      case 'Tab': this.closeUserMenu(false); break;
    }
  }

  toggleRail() {
    this.collapsed.update((c) => !c);
    writePref(SIDEBAR_KEY, this.collapsed() ? 'rail' : 'expanded');
  }

  private loadBranches() {
    this.org.listBranches().subscribe({
      next: (page) => {
        this.allBranches.set(page.content);
        if (!this.auth.activeBranchId() && this.branches().length) {
          this.auth.setActiveBranch(this.branches()[0].id);
        }
      },
      error: () => {},
    });
  }

  onBranchChange(e: Event) {
    const id = (e.target as HTMLSelectElement).value;
    if (!id) return;
    this.auth.setActiveBranch(id);
    // permissions follow the branch, but route guards run only on navigation: leave a page this branch closes
    const path = this.router.url.split(/[?#]/)[0];
    const page = NAV_SECTIONS.flatMap((s) => s.items).find((i) => i.path === path);
    if (page?.perms && !this.auth.canAny(...page.perms)) void this.router.navigateByUrl('/');
  }

  async logout() { await this.auth.logout(); location.href = '/login'; }
}
