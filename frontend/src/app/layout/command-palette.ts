import { Component, ElementRef, HostListener, Injectable, Injector, afterNextRender, computed, effect, inject, input, signal, untracked, viewChild } from '@angular/core';
import { Router } from '@angular/router';
import { AuthService } from '../core/auth.service';
import { BranchResponse } from '../core/models';
import { writePref } from '../core/prefs';
import { ThemeService } from '../core/theme.service';
import { IconComponent } from '../shared/icon';
import { OverlayStack } from '../shared/overlay-stack';
import { BranchSwitcher } from './branch-switch';
import { visibleNav } from './nav';

const RECENT_KEY = 'erpcafe.recent';
const IS_MAC = /Mac|iPhone|iPad/.test(navigator.platform || navigator.userAgent);
/** The shortcut as shown on screen and read aloud: ⌘K on macOS, Ctrl K elsewhere. */
export const PALETTE_SHORTCUT = IS_MAC ? { kbd: '⌘K', aria: 'Command K' } : { kbd: 'Ctrl K', aria: 'Control K' };
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

  /** Opens unless a dialog or drawer is already up. */
  open() {
    if (!this.state() && this.overlays.size > 0) return;
    this.overlays.push(this);
    this.state.set(true);
  }
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
      <div class="palette-backdrop" role="presentation" (click)="$event.target === $event.currentTarget && palette.close()">
        <div class="palette glass" role="dialog" aria-modal="true" aria-label="Command palette">
          <label for="palette-input" class="sr-only">Search or jump to</label>
          <input #field id="palette-input" class="palette-input" role="combobox" aria-expanded="true" aria-controls="palette-list"
                 [attr.aria-activedescendant]="activeId()" autocomplete="off"
                 placeholder="Search or jump to…" [value]="query()" (input)="onInput($event)" />
          <div id="palette-list" role="listbox" aria-label="Commands" (mousedown)="$event.preventDefault()">
            @for (g of groups(); track g.name; let gi = $index) {
              <div role="group" [attr.aria-labelledby]="'palette-g-' + gi">
                <div class="palette-group" role="presentation" [id]="'palette-g-' + gi">{{ g.name }}</div>
                @for (r of g.items; track r.c.id) {
                  <!-- eslint-disable-next-line @angular-eslint/template/click-events-have-key-events -->
                  <div role="option" tabindex="-1" class="palette-row" [id]="'cmd-' + r.c.id" [attr.aria-selected]="r.i === active()"
                       (click)="run(r.c)" (mousemove)="active.set(r.i)">
                    <app-icon [name]="r.c.icon" [size]="16" aria-hidden="true" />
                    <span class="palette-label">{{ r.c.label }}</span>
                    @if (r.c.hint) { <kbd>{{ r.c.hint }}</kbd> }
                  </div>
                }
              </div>
            }
          </div>
          @if (!results().length) { <div class="palette-empty" aria-hidden="true">No matches for "{{ query() }}"</div> }
          <div class="sr-only" role="status" aria-live="polite">{{ results().length ? results().length + ' results' : 'No matches for "' + query() + '"' }}</div>
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
    #palette-list { margin: 0; padding: .4rem; overflow-y: auto; }
    .palette-group { padding: .6rem .75rem .25rem; font-size: 11px; text-transform: uppercase;
      letter-spacing: .06em; color: var(--text-muted); }
    .palette-row { display: flex; align-items: center; gap: .75rem; min-height: 44px; padding: 0 .75rem;
      border-radius: var(--radius-sm); cursor: pointer; color: var(--text); }
    .palette-row:focus { outline: none; }
    .palette-row[aria-selected='true'] { background: var(--accent-soft); color: var(--accent-text); box-shadow: inset 2px 0 0 var(--accent); }
    @media (forced-colors: active) { .palette-row[aria-selected='true'] { outline: 2px solid Highlight; } }
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
  private switcher = inject(BranchSwitcher);
  private field = viewChild<ElementRef<HTMLInputElement>>('field');
  private opener: HTMLElement | null = null;
  private wasOpen = false;

  query = signal('');
  active = signal(0);
  private recent = signal<string[]>(readRecent());

  private nav = computed(() => visibleNav(this.auth).flatMap((s) => s.items.map((i) => ({ ...i, section: s.title }))));

  private commands = computed<Command[]>(() => {
    this.auth.me();
    const go = (path: string) => () => { this.remember(path); void this.router.navigateByUrl(path); };
    const nav = this.nav();
    const recents: Command[] = this.recent()
      .map((p) => nav.find((n) => n.path === p))
      .filter((n) => !!n)
      .slice(0, 4)
      .map((n) => ({ id: 'recent:' + n.path, group: 'Recent' as const, label: n.label, icon: n.icon, hint: n.section, run: go(n.path) }));
    const goTo: Command[] = nav.map((n) => ({ id: 'go:' + n.path, group: 'Go to' as const, label: n.label, icon: n.icon, hint: n.section, run: go(n.path) }));
    const actions: Command[] = [];
    const to = (id: string, label: string, icon: string, perm: string, url: string) => {
      if (this.auth.can(perm)) actions.push({ id, group: 'Actions', label, icon, run: () => void this.router.navigateByUrl(url) });
    };
    to('act:new-order', 'New order', 'cart', 'sales:write', '/pos');
    to('act:new-po', 'New purchase order', 'clipboard', 'purchasing:write', '/inventory/purchase-orders?new=1');
    to('act:adjust', 'Adjust stock', 'sliders', 'inventory:write', '/inventory/stock?action=adjust');
    for (const b of this.branches().filter((x) => x.id !== this.auth.activeBranchId())) {
      actions.push({ id: 'branch:' + b.id, group: 'Actions', label: 'Switch branch: ' + b.name, icon: 'pin', run: () => this.switcher.switchTo(b.id) });
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

  /** results grouped for display, each row carrying its index in the flat list */
  groups = computed(() => {
    const all = this.results();
    return GROUPS
      .map((name) => ({ name, items: all.map((c, i) => ({ c, i })).filter((r) => r.c.group === name) }))
      .filter((g) => g.items.length);
  });

  activeId = computed(() => { const c = this.results()[this.active()]; return c ? 'cmd-' + c.id : null; });

  constructor() {
    // every way of opening or closing lands here: focus in on open, reset and focus back on close
    effect(() => {
      if (this.palette.isOpen()) {
        untracked(() => {
          this.wasOpen = true;
          this.opener = document.activeElement instanceof HTMLElement ? document.activeElement : null;
          afterNextRender(() => this.field()?.nativeElement.focus(), { injector: this.injector });
        });
      } else {
        untracked(() => {
          this.query.set('');
          this.active.set(0);
          if (this.wasOpen) {
          // the opener may have been removed while the palette was up: fall back to the trigger, then the page
          const target = this.opener?.isConnected ? this.opener
            : document.querySelector<HTMLElement>('.palette-trigger') ?? document.getElementById('main-content');
          target?.focus();
          this.wasOpen = false;
          }
          this.opener = null;
        });
      }
    });
    // keep the highlighted row visible in the scrolling list
    effect(() => {
      const id = this.activeId();
      if (id && this.palette.isOpen()) {
        afterNextRender(() => document.getElementById(id)?.scrollIntoView?.({ block: 'nearest' }), { injector: this.injector });
      }
    });
  }

  onInput(e: Event) {
    this.query.set((e.target as HTMLInputElement).value);
    this.active.set(0);
  }

  run(c: Command) {
    this.palette.close();
    c.run();
  }

  private remember(path: string) {
    const next = [path, ...this.recent().filter((p) => p !== path)].slice(0, 4);
    this.recent.set(next);
    writePref(RECENT_KEY, JSON.stringify(next));
  }

  @HostListener('document:keydown', ['$event'])
  onKey(e: KeyboardEvent) {
    if (e.isComposing) return;
    if ((IS_MAC ? e.metaKey : e.ctrlKey) && e.key.toLowerCase() === 'k') {
      e.preventDefault();
      if (!this.palette.isOpen()) this.palette.open();
      else if (this.overlays.isTop(this.palette, e)) this.palette.close();
      return;
    }
    if (!this.palette.isOpen() || !this.overlays.isTop(this.palette, e)) return;
    const n = this.results().length;
    switch (e.key) {
      case 'ArrowDown': e.preventDefault(); if (n) this.active.set((this.active() + 1) % n); break;
      case 'ArrowUp': e.preventDefault(); if (n) this.active.set((this.active() - 1 + n) % n); break;
      case 'Enter': { e.preventDefault(); const c = this.results()[this.active()]; if (c) this.run(c); break; }
      case 'Escape': e.preventDefault(); this.palette.close(); break;
      case 'Tab': e.preventDefault(); break; // focus stays in the search field
    }
  }
}

/** Recent paths from storage; a missing, broken or blocked entry reads as none. */
function readRecent(): string[] {
  try {
    const v = JSON.parse(localStorage.getItem(RECENT_KEY) ?? '[]');
    return Array.isArray(v) ? v.filter((p): p is string => typeof p === 'string') : [];
  } catch { return []; }
}
