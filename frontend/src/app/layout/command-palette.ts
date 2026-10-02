import { Component, ElementRef, HostListener, Injectable, afterNextRender, computed, inject, input, signal, Injector } from '@angular/core';
import { Router } from '@angular/router';
import { AuthService } from '../core/auth.service';
import { BranchResponse } from '../core/models';
import { writePref } from '../core/prefs';
import { ThemeService } from '../core/theme.service';
import { IconComponent } from '../shared/icon';
import { OverlayStack } from '../shared/overlay-stack';
import { NAV_SECTIONS } from './nav';

const RECENT_KEY = 'erpcafe.recent';
const GROUPS: Command['group'][] = ['Recent', 'Go to', 'Actions'];

/** One thing the palette can do: where it sits, how it reads and what it runs. */
export interface Command { id: string; group: 'Recent' | 'Go to' | 'Actions'; label: string; icon: string; hint?: string; run(): void; }

/**
 * Case-insensitive match on the label, grouped Recent, Go to, Actions with the original order kept inside
 * each group. Labels that contain the query as plain text win; only when none does does it fall back to a
 * looser subsequence match (so "po" finds Purchase Orders and Products, but "prod" finds only Products).
 */
export function filterCommands(all: Command[], query: string): Command[] {
  const q = query.trim().toLowerCase();
  const subsequence = (label: string) => {
    let i = 0;
    const l = label.toLowerCase();
    for (const ch of q) { i = l.indexOf(ch, i); if (i < 0) return false; i++; }
    return true;
  };
  const exact = all.filter((c) => c.label.toLowerCase().includes(q));
  const kept = exact.length ? exact : all.filter((c) => subsequence(c.label));
  return GROUPS.flatMap((g) => kept.filter((c) => c.group === g));
}

/** Open/closed state of the command palette; it sits in the overlay stack while open. */
@Injectable({ providedIn: 'root' })
export class PaletteService {
  private overlays = inject(OverlayStack);
  private state = signal(false);
  readonly isOpen = this.state.asReadonly();

  open() { this.overlays.push(this); this.state.set(true); }
  close() { this.overlays.remove(this); this.state.set(false); }
  toggle() { if (this.state()) this.close(); else this.open(); }
}

/** The ⌘K palette: search screens, jump to them and run quick actions by keyboard. */
@Component({
  selector: 'app-command-palette',
  standalone: true,
  imports: [IconComponent],
  template: `
    @if (palette.isOpen()) {
      <div class="palette-backdrop" (click)="palette.close()">
        <div class="palette glass" role="dialog" aria-modal="true" aria-label="Command palette" (click)="$event.stopPropagation()">
          <input #field class="palette-input" role="combobox" aria-expanded="true" aria-controls="palette-list"
                 aria-label="Search commands" [attr.aria-activedescendant]="activeId()" autocomplete="off"
                 placeholder="Search or jump to…" [value]="query()" (input)="onInput($event)" />
          <ul id="palette-list" role="listbox" aria-label="Commands">
            @for (c of results(); track c.id; let i = $index) {
              @if (i === 0 || results()[i - 1].group !== c.group) {
                <li role="presentation" class="palette-group">{{ c.group }}</li>
              }
              <li role="option" class="palette-row" [id]="'cmd-' + c.id" [attr.aria-selected]="i === active()"
                  (click)="run(c)" (mousemove)="active.set(i)">
                <app-icon [name]="c.icon" [size]="16" aria-hidden="true" />
                <span class="palette-label">{{ c.label }}</span>
                @if (c.hint) { <kbd>{{ c.hint }}</kbd> }
              </li>
            } @empty {
              <li class="palette-empty">No matches for "{{ query() }}"</li>
            }
          </ul>
        </div>
      </div>
    }
  `,
  styles: [`
    .palette-backdrop { position: fixed; inset: 0; z-index: 110; background: var(--scrim);
      display: flex; justify-content: center; align-items: flex-start; padding-top: 14vh; }
    .palette { width: min(640px, 92vw); max-height: 70vh; display: flex; flex-direction: column;
      border-radius: var(--radius-lg); border: 1px solid var(--hairline); box-shadow: var(--shadow-pop); overflow: hidden; }
    .palette-input { width: 100%; padding: 1rem 1.25rem; font: inherit; font-size: 16px; color: var(--text);
      background: transparent; border: 0; border-bottom: 1px solid var(--hairline); }
    .palette-input:focus-visible { outline: 2px solid var(--accent); outline-offset: -2px; }
    ul { list-style: none; margin: 0; padding: .4rem; overflow-y: auto; }
    .palette-group { padding: .6rem .75rem .25rem; font-size: 11px; text-transform: uppercase;
      letter-spacing: .06em; color: var(--text-muted); }
    .palette-row { display: flex; align-items: center; gap: .75rem; min-height: 44px; padding: 0 .75rem;
      border-radius: var(--radius-md); cursor: pointer; color: var(--text); }
    .palette-row[aria-selected='true'] { background: var(--accent-soft); color: var(--accent-text); }
    .palette-label { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    kbd { margin-left: auto; font-size: 11px; color: var(--text-muted); }
    .palette-empty { padding: 1rem .75rem; color: var(--text-muted); }
  `],
})
export class CommandPaletteComponent {
  /** the branches the user holds, passed in by the shell */
  branches = input<BranchResponse[]>([]);

  palette = inject(PaletteService);
  private auth = inject(AuthService);
  private router = inject(Router);
  private theme = inject(ThemeService);
  private overlays = inject(OverlayStack);
  private injector = inject(Injector);
  private host = inject(ElementRef<HTMLElement>);
  private opener: HTMLElement | null = null;

  query = signal('');
  active = signal(0);
  private recent = signal<string[]>(readRecent());

  private nav = computed(() => {
    this.auth.me(); // track permission changes
    return NAV_SECTIONS.flatMap((s) => s.items
      .filter((i) => !i.perms || this.auth.canAny(...i.perms))
      .map((i) => ({ ...i, section: s.title })));
  });

  private commands = computed<Command[]>(() => {
    this.auth.me();
    const go = (path: string) => () => { this.remember(path); void this.router.navigateByUrl(path); };
    const nav = this.nav();
    const recents: Command[] = this.recent()
      .map((p) => nav.find((n) => n.path === p))
      .filter((n) => !!n)
      .map((n) => ({ id: 'recent:' + n.path, group: 'Recent' as const, label: n.label, icon: n.icon, hint: n.section, run: go(n.path) }));
    const goTo: Command[] = nav.map((n) => ({ id: 'go:' + n.path, group: 'Go to' as const, label: n.label, icon: n.icon, hint: n.section, run: go(n.path) }));
    const actions: Command[] = [];
    const to = (id: string, label: string, icon: string, perm: string, url: string) => {
      if (this.auth.can(perm)) actions.push({ id, group: 'Actions', label, icon, run: () => void this.router.navigateByUrl(url) });
    };
    to('act:new-order', 'New order', 'cart', 'sales:write', '/pos');
    to('act:new-po', 'New purchase order', 'clipboard', 'purchasing:write', '/inventory/purchase-orders?new=1');
    to('act:adjust', 'Adjust stock', 'sliders', 'inventory:write', '/inventory/stock?action=adjust');
    for (const b of this.branches()) {
      actions.push({ id: 'branch:' + b.id, group: 'Actions', label: 'Switch branch: ' + b.name, icon: 'pin', run: () => this.auth.setActiveBranch(b.id) });
    }
    actions.push({ id: 'act:theme', group: 'Actions', label: 'Change theme', icon: 'moon', run: () => this.theme.cycle() });
    actions.push({ id: 'act:signout', group: 'Actions', label: 'Sign out', icon: 'logout', run: async () => { await this.auth.logout(); location.href = '/login'; } });
    return [...recents, ...goTo, ...actions];
  });

  results = computed(() => {
    const q = this.query();
    const all = q.trim() ? this.commands().filter((c) => c.group !== 'Recent') : this.commands();
    return filterCommands(all, q);
  });

  activeId = computed(() => { const c = this.results()[this.active()]; return c ? 'cmd-' + c.id : null; });

  onInput(e: Event) {
    this.query.set((e.target as HTMLInputElement).value);
    this.active.set(0);
  }

  run(c: Command) {
    this.close();
    c.run();
  }

  private close() {
    this.palette.close();
    this.query.set('');
    this.active.set(0);
    this.opener?.focus();
    this.opener = null;
  }

  private remember(path: string) {
    const next = [path, ...this.recent().filter((p) => p !== path)].slice(0, 4);
    this.recent.set(next);
    writePref(RECENT_KEY, JSON.stringify(next));
  }

  @HostListener('document:keydown', ['$event'])
  onKey(e: KeyboardEvent) {
    if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
      e.preventDefault();
      if (this.palette.isOpen()) { if (this.overlays.isTop(this.palette, e)) this.close(); return; }
      if (this.overlays.size > 0) return; // a dialog or drawer is up
      this.opener = document.activeElement instanceof HTMLElement ? document.activeElement : null;
      this.palette.open();
      afterNextRender(() => (this.host.nativeElement as HTMLElement).querySelector<HTMLInputElement>('.palette-input')?.focus(), { injector: this.injector });
      return;
    }
    if (!this.palette.isOpen() || !this.overlays.isTop(this.palette, e)) return;
    const n = this.results().length;
    switch (e.key) {
      case 'ArrowDown': e.preventDefault(); if (n) this.active.set((this.active() + 1) % n); break;
      case 'ArrowUp': e.preventDefault(); if (n) this.active.set((this.active() - 1 + n) % n); break;
      case 'Enter': { e.preventDefault(); const c = this.results()[this.active()]; if (c) this.run(c); break; }
      case 'Escape': e.preventDefault(); this.close(); break;
      case 'Tab': e.preventDefault(); break; // focus stays in the search field
    }
  }
}

/** Recent paths from storage; a missing, broken or blocked entry reads as none. */
function readRecent(): string[] {
  try {
    const v = JSON.parse(localStorage.getItem(RECENT_KEY) ?? '[]');
    return Array.isArray(v) ? v.filter((p): p is string => typeof p === 'string').slice(0, 4) : [];
  } catch { return []; }
}
