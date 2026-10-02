# Command Centre UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the café theme with a dark-first "command centre" design system, a new app shell with a ⌘K palette, every screen on shared building blocks, and two Three.js views (dashboard branch towers, stock 3D shelves).

**Architecture:**
- **Restyle, don't rename.** The existing global class vocabulary keeps its names and gets the new look in `frontend/src/styles.scss`. The tests rely on `table.data`, `.badge`, `.btn-primary`, `.row-actions`, `.grand` and `app-modal`, and those names stay. Most screens pick up the new look with no template change; screen tasks then remove the café materials and adopt the few new patterns: drawer, KPI tile, avatar.
- **New shared pieces:** theme, density, palette and the 3D helper are small standalone units in `core/`, `layout/` and `shared/`, each with its own spec.
- **Three.js** loads through a dynamic `import()` only inside the two 3D components.
- **3D maths:** the data-to-scene mapping is pure functions, tested without WebGL.

**Tech Stack:** Angular 22 (standalone, zoneless, signals), TypeScript 6, SCSS, Vitest via `ng test`, angular-eslint, Three.js (new, pinned).

**Spec:** `docs/superpowers/specs/2026-10-02-command-centre-ui-design.md`

## Global Constraints

**Branch and commits**
- Work on branch `feat/command-centre-ui`. Never commit to `dev`, `main` or `fix/code-review`. Never push or merge.
- Commit subjects use the form `[Action] Feature`, for example `[Add] Theme and density settings`.
- **No `Co-Authored-By` trailer and no AI attribution of any kind.**
- Stage files by path only. Never use `git add -A` or `git add .`.
- Never stage any of these:
  - `.DS_Store`, `.claude/`, `.hermes/`, `frontend/.claude/`
  - `README.md`, `ERP-Cafe-Kanban.xlsx`, `docs/interview-guide.html`, `erp-cafe-*.html`
  - the uncommitted Docker files: `docker-compose.yml`, `.env.example`, `.env`, `.gitignore`, `backend/Dockerfile`, `backend/.dockerignore`, `frontend/Dockerfile`, `frontend/.dockerignore`, `frontend/nginx.conf`
- Never touch `git stash`.

**Scope**
- Frontend only (`frontend/`). No backend change.
- No new runtime dependency except `three`. Pin its exact version from `npm view three version`, and add `@types/three` as a dev dependency if `three` ships no types of its own.

**Commands**
- Run from `frontend/`: `npm test` (Vitest, single run), `npm run lint` (0 errors), `npx ng build` (production).
- All three must exit 0 at every commit. Report each exit code.

**Tests**
- Existing specs keep passing. A selector may change to follow new markup, but an assertion is never weakened or deleted.

**Budgets and file layout**
- `angular.json` caps component styles at 6 kB (warning) and 8 kB (error), and the initial bundle at 600 kB (warning).
- Large shared CSS goes in `styles.scss` (or a partial it `@use`s), not in component `styles`.

**Classes and Javadoc-style comments**
- New TypeScript classes get a one-line JSDoc purpose comment, matching the existing code.
- New class names in templates are added to `styles.scss` or to the component's own styles. No inline `style=` colour literals: use tokens.

**Accessibility (keep every one)**
- An `id` plus `<label for>` on every control; required fields get `<span class="req">*</span>` and `required`.
- Tables keep `<caption class="sr-only">` and `scope="col"`; money and quantity cells use `.num`.
- Click-to-select rows keep a `<button class="row-open">` in the first cell.
- Destructive actions call `confirm.ask({ danger: true })`.
- `:focus-visible` rings everywhere; skip link plus `<main id="main-content">`; `aria-current` on nav; icon-only buttons have `aria-label`.
- Touch targets are at least 44px on POS.
- Status always has text, never colour alone.
- Respect `prefers-reduced-motion`, `prefers-reduced-transparency` and `prefers-contrast: more`.

**Colour**
- Colour tokens are exactly the values in Task 1. Red, amber and green mean status only.

## Review Focus

1. **The OS switches light/dark while the app is open with Theme = System.** The app follows without a reload. *Pinned in Task 2 by a fake `matchMedia` change.*
2. **`localStorage` throws (private mode, blocked storage).** Theme, density, sidebar and stock-view fall back to their defaults, nothing crashes, and a choice still applies for the visit. *Pinned in Task 2 by a throwing storage stub.*
3. **No WebGL (old PC, GPU disabled).** The 3D panels show the table fallback with a note, never a blank box. *Pinned in Task 14 (`hasWebGL`) and in Tasks 15 and 16 by component specs.*
4. **The branch changes or the user navigates away while a 3D view is open.** The view reloads for the new branch, and leaving the page disposes the renderer. *Pinned in Task 14 (`ThreeStage.destroy` disposes) and Task 16 (branch change reloads).*
5. **⌘K pressed while a dialog or drawer is open.** The palette does not open over it, and Esc closes only the top overlay. *Pinned in Task 4.*

---

## File Structure

| File | Responsibility |
|---|---|
| `src/styles.scss` | Design tokens (dark default + light), base and component classes. Legacy café section kept until Task 17 |
| `src/index.html` | Fonts, theme-color meta, favicon, description |
| `src/app/core/prefs.ts` | Safe `localStorage` read/write for per-browser settings |
| `src/app/core/theme.service.ts` | Theme preference (system/dark/light) → `data-theme` on `<html>` |
| `src/app/core/density.service.ts` | Comfortable/compact → `data-density` on `<html>` |
| `src/app/layout/nav.ts` | Nav sections (moved out of the shell), shared by shell and palette |
| `src/app/layout/shell.ts`, `shell.scss` | Sidebar + rail, top bar, user menu |
| `src/app/layout/command-palette.ts` | ⌘K palette |
| `src/app/shared/modal.ts` | Gains `variant: 'dialog' \| 'drawer'` |
| `src/app/shared/kpi-tile.ts` | Key-number tile |
| `src/app/shared/sparkline.ts` | Tiny SVG trend line |
| `src/app/shared/avatar.ts` | Initials avatar |
| `src/app/shared/icon.ts` | Adds icons: package, sun, moon, monitor, panel-left, command, table, cube, arrow-up, arrow-down |
| `src/app/three/three-stage.ts` | Renderer lifecycle: resize, render-on-demand, pause, dispose |
| `src/app/three/webgl.ts` | `hasWebGL()` detection |
| `src/app/three/tower-model.ts` | Pure: outlets → tower specs |
| `src/app/three/shelf-model.ts` | Pure: stock + ingredients → shelf specs |
| `src/app/features/dashboard/branch-towers.ts` | Dashboard 3D panel |
| `src/app/features/inventory/stock-shelves.ts` | Stock 3D view |

---

### Task 1: Design tokens, fonts and base classes

**Files:**
- Modify: `frontend/src/styles.scss`. Replace everything above the line `/* ==========================================================================\n   lively: physical materials` with the new foundation. Keep the lively section and everything after it unchanged for now.
- Modify: `frontend/src/index.html`

**Interfaces:**
- **Produces CSS custom properties:**
  - `--bg --surface-1 --surface-2 --surface-3 --border --border-strong --border-input`
  - `--text --text-soft --text-muted`
  - `--accent --accent-fill --accent-fill-hover --accent-soft --accent-text`
  - `--green(-soft) --amber(-soft) --red(-soft)`
  - `--glass --scrim --highlight --glow --bg-glow --shadow-pop`
  - `--font --font-display --font-mono`
  - `--radius --radius-sm --radius-lg`
  - `--ease-out --dur-1 --dur-2 --dur-3`
  - `--chart-1 --chart-2`
- **Produces classes:**
  - kept names, restyled: `.btn .btn-primary .btn-accent .btn-tinted .btn-ghost .btn-outline .btn-danger .btn-sm .btn-lg .btn-icon .btn-block .field .two-col .input .select .checkbox .card .card-pad .card-head table.data .row-actions .num .row-open .badge(.dot) .badge-green .badge-amber .badge-red .badge-blue .badge-gold .badge-violet .badge-gray .segmented .pill-row .pill .tabs .page .page-head .toolbar .grid .spacer .muted .soft .right .mono .empty .stack .flex .gap-1/2/3 .ic* .spinner .loading-block .sr-only .skip-link`
  - new: `.glass .skeleton .avatar`
- **Theme hooks:** the light theme applies under `:root[data-theme="light"]`, and under `@media (prefers-color-scheme: light)` for `:root:not([data-theme])`. Density applies under `:root[data-density="compact"]`.
- **Legacy aliases** (deleted in Task 17), so the untouched café sections and component styles still compile:
  - `--surface --hairline --brand --brand-600 --brand-700 --brand-soft --azure --azure-600 --azure-soft --gold --gold-600 --gold-soft --blue --blue-soft --violet --violet-soft`
  - `--material --material-nav --blur --nav-bg --nav-bg-2 --nav-text --nav-text-hi --nav-active`
  - `--radius-xl --shadow-sm --shadow-md --shadow-lg --ease-spring --font-script --line --ink --ink-muted --ring`
  - `--paper --paper-2 --paper-ink --paper-ink-soft --paper-line --paper-accent --wood-hi --wood-mid --wood-lo --grain-img`

- [ ] **Step 1: Record the starting state.**

  Run from `frontend/`: `npm test; echo "test exit $?"; npm run lint; echo "lint exit $?"; npx ng build; echo "build exit $?"`

  Expected: all 0, with 104 tests passing. Write the counts in the report.

- [ ] **Step 2: Replace the top of `styles.scss`** (from line 1 down to, but not including, the "lively: physical materials" banner) with:

```scss
/* ==========================================================================
   ERP-Cafe — Command Centre design system
   Dark by default; light under [data-theme="light"], or from the OS when the
   theme setting is System (no data-theme attribute). Electric indigo accent;
   red / amber / green mean status only. Every text token meets WCAG AA (4.5:1)
   on every surface of its theme; input borders and the focus ring meet 3:1.
   ========================================================================== */

/* Inter, Space Grotesk and JetBrains Mono load via <link> in index.html. */

@mixin light-tokens {
  color-scheme: light;
  --bg:            #F6F7FB;
  --surface-1:     #FFFFFF;
  --surface-2:     #F0F2F8;
  --surface-3:     #E7EAF3;
  --border:        #E3E6EF;
  --border-strong: #D3D7E3;
  --border-input:  #848BA4;
  --text:          #11131C;
  --text-soft:     #4A5068;
  --text-muted:    #5F6680;
  --accent:        #4F56E8;
  --accent-fill:   #4F56E8;
  --accent-fill-hover: #434AD9;
  --accent-soft:   #ECEDFD;
  --accent-text:   #4F56E8;
  --green: #047857;  --green-soft: #E3F4EC;
  --amber: #9A4706;  --amber-soft: #FDF0DD;
  --red:   #B91C1C;  --red-soft:   #FBE9E9;
  --glass:     color-mix(in srgb, #FFFFFF 78%, transparent);
  --scrim:     color-mix(in srgb, #11131C 38%, transparent);
  --highlight: rgba(255, 255, 255, .9);
  --glow:      color-mix(in srgb, #4F56E8 22%, transparent);
  --bg-glow:   radial-gradient(1100px 560px at 0% 0%, color-mix(in srgb, #4F56E8 9%, transparent), transparent 62%);
  --shadow-pop: 0 24px 60px rgba(17, 19, 28, .18), 0 0 0 1px var(--border);
  --chart-1: #4F56E8;
  --chart-2: #0369A1;
}

:root {
  color-scheme: dark;
  --bg:            #0B0D14;
  --surface-1:     #12151F;
  --surface-2:     #181C29;
  --surface-3:     #20253A;
  --border:        #262B3D;
  --border-strong: #343A52;
  --border-input:  #636B8E;
  --text:          #E6E8F2;
  --text-soft:     #A3A9C2;
  --text-muted:    #8A91AE;
  --accent:        #7C83FF;
  --accent-fill:   #5B62F0;
  --accent-fill-hover: #6A71F5;
  --accent-soft:   #1B1D3A;
  --accent-text:   #9AA0FF;
  --green: #34D399;  --green-soft: #0F2A22;
  --amber: #FBBF24;  --amber-soft: #2A2412;
  --red:   #F87171;  --red-soft:   #2C1618;
  --glass:     color-mix(in srgb, #12151F 72%, transparent);
  --scrim:     color-mix(in srgb, #05060A 62%, transparent);
  --highlight: rgba(255, 255, 255, .06);
  --glow:      color-mix(in srgb, #7C83FF 35%, transparent);
  --bg-glow:   radial-gradient(1100px 560px at 0% 0%, color-mix(in srgb, #7C83FF 14%, transparent), transparent 62%);
  --shadow-pop: 0 24px 60px rgba(0, 0, 0, .55), 0 0 0 1px var(--border);
  --chart-1: #7C83FF;
  --chart-2: #38BDF8;

  --font:         "Inter", system-ui, -apple-system, "Segoe UI", Roboto, sans-serif;
  --font-display: "Space Grotesk", "Inter", system-ui, sans-serif;
  --font-mono:    "JetBrains Mono", ui-monospace, "SF Mono", Menlo, Consolas, monospace;

  --radius:    12px;
  --radius-sm: 8px;
  --radius-lg: 16px;

  --ease-out: cubic-bezier(.22, 1, .36, 1);
  --dur-1: 150ms; --dur-2: 220ms; --dur-3: 320ms;

  /* ---- legacy aliases: café-era names still read by screens not yet
     migrated. Task 17 deletes this block. ---------------------------- */
  --surface: var(--surface-1);
  --hairline: var(--border);
  --brand: var(--accent); --brand-600: var(--accent-fill-hover); --brand-700: var(--accent-fill); --brand-soft: var(--accent-soft);
  --azure: var(--accent); --azure-600: var(--accent-text); --azure-soft: var(--accent-soft);
  --gold: var(--amber); --gold-600: var(--amber); --gold-soft: var(--amber-soft);
  --blue: var(--accent); --blue-soft: var(--accent-soft);
  --violet: var(--accent); --violet-soft: var(--accent-soft);
  --material: var(--glass); --material-nav: var(--glass); --blur: saturate(160%) blur(16px);
  --nav-bg: var(--surface-1); --nav-bg-2: var(--surface-2); --nav-text: var(--text-soft); --nav-text-hi: var(--text); --nav-active: var(--surface-3);
  --radius-xl: var(--radius-lg);
  --shadow-sm: inset 0 1px 0 var(--highlight); --shadow-md: 0 8px 24px rgba(0,0,0,.25); --shadow-lg: var(--shadow-pop);
  --ease-spring: var(--ease-out);
  --font-script: var(--font-display);
  --line: var(--border); --ink: var(--text); --ink-muted: var(--text-soft);
  --ring: var(--accent);
  --paper: var(--surface-2); --paper-2: var(--surface-3); --paper-ink: var(--text); --paper-ink-soft: var(--text-soft);
  --paper-line: var(--border); --paper-accent: var(--accent-text);
  --wood-hi: var(--surface-3); --wood-mid: var(--surface-2); --wood-lo: var(--surface-1);
  --grain-img: none;
}

:root[data-theme="light"] { @include light-tokens; }
@media (prefers-color-scheme: light) { :root:not([data-theme]) { @include light-tokens; } }

/* ---- base ------------------------------------------------------------- */
* { box-sizing: border-box; }
html, body { height: 100%; }
body {
  margin: 0;
  font-family: var(--font);
  background: var(--bg-glow), var(--bg);
  background-attachment: fixed;
  color: var(--text);
  font-size: 14.5px;
  line-height: 1.55;
  -webkit-font-smoothing: antialiased;
  font-feature-settings: "cv11", "ss01";
}
a { color: var(--accent-text); text-decoration: none; }
a:hover { text-decoration: underline; }
h1, h2, h3, h4 { margin: 0; font-family: var(--font-display); font-weight: 600; letter-spacing: -.015em; line-height: 1.2; }

/* ---- accessibility primitives ---------------------------------------- */
:where(a, button, input, select, textarea, [tabindex]):focus-visible {
  outline: none;
  box-shadow: 0 0 0 2px var(--bg), 0 0 0 4px var(--accent);
  border-radius: var(--radius-sm);
}
.sr-only {
  position: absolute; width: 1px; height: 1px; padding: 0; margin: -1px;
  overflow: hidden; clip: rect(0, 0, 0, 0); white-space: nowrap; border: 0;
}
.skip-link {
  position: fixed; top: -60px; left: 12px; z-index: 300;
  background: var(--accent-fill); color: #fff; padding: .6rem 1rem;
  border-radius: var(--radius-sm); font-weight: 600; transition: top var(--dur-1) var(--ease-out);
}
.skip-link:focus { top: 12px; text-decoration: none; }

@media (prefers-reduced-motion: reduce) {
  *, *::before, *::after { animation-duration: .001ms !important; animation-iteration-count: 1 !important;
    transition-duration: .001ms !important; scroll-behavior: auto !important; }
}
@media (prefers-reduced-transparency: reduce) {
  :root { --glass: var(--surface-1); --blur: none; }
}
@media (prefers-contrast: more) {
  :root { --border: var(--border-input); --border-strong: var(--border-input); --text-muted: var(--text-soft); }
}

/* ---- glass (floating things only: top bar, dialogs, drawers, palette, toasts) */
.glass {
  background: var(--glass);
  backdrop-filter: saturate(160%) blur(16px); -webkit-backdrop-filter: saturate(160%) blur(16px);
}
@media (prefers-reduced-transparency: reduce) { .glass { backdrop-filter: none; -webkit-backdrop-filter: none; } }

/* ---- buttons ---------------------------------------------------------- */
.btn {
  display: inline-flex; align-items: center; justify-content: center; gap: .5rem;
  min-height: 38px; padding: 0 1rem; border-radius: var(--radius-sm);
  border: 1px solid var(--border); background: var(--surface-2); color: var(--text);
  font: inherit; font-weight: 600; font-size: 13.5px; cursor: pointer; white-space: nowrap;
  transition: background var(--dur-1) var(--ease-out), border-color var(--dur-1), box-shadow var(--dur-1), transform var(--dur-1);
  -webkit-tap-highlight-color: transparent;
}
.btn:hover { background: var(--surface-3); }
.btn:active { transform: translateY(1px); }
.btn:disabled { opacity: .45; cursor: not-allowed; transform: none; }
.btn app-icon, .btn svg { flex-shrink: 0; }
.btn-primary, .btn-accent {
  background: var(--accent-fill); border-color: transparent; color: #fff;
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, .14), 0 8px 24px -10px var(--glow);
}
.btn-primary:hover, .btn-accent:hover { background: var(--accent-fill-hover); }
.btn-tinted { background: var(--accent-soft); border-color: transparent; color: var(--accent-text); }
.btn-tinted:hover { background: color-mix(in srgb, var(--accent-soft) 80%, var(--accent) 12%); }
.btn-ghost { background: transparent; border-color: transparent; }
.btn-ghost:hover { background: var(--surface-2); }
.btn-outline { background: transparent; border-color: var(--border-strong); }
.btn-outline:hover { background: var(--surface-2); border-color: var(--accent); }
.btn-danger { background: var(--red-soft); border-color: color-mix(in srgb, var(--red) 35%, transparent); color: var(--red); }
.btn-danger:hover { background: color-mix(in srgb, var(--red-soft) 70%, var(--red) 14%); }
.btn-sm { min-height: 32px; padding: 0 .7rem; font-size: 12.5px; }
.btn-lg { min-height: 48px; padding: 0 1.4rem; font-size: 15px; border-radius: var(--radius); }
.btn-icon { width: 38px; min-height: 38px; padding: 0; }
.btn-sm.btn-icon { width: 32px; min-height: 32px; }
.btn-block { width: 100%; }

/* ---- form controls ---------------------------------------------------- */
.field { display: flex; flex-direction: column; gap: .4rem; margin-bottom: 1rem; }
.field > label { font-size: 12.5px; font-weight: 600; color: var(--text-soft); }
.field > label .req { color: var(--red); font-weight: 700; }
.two-col { display: grid; grid-template-columns: 1fr 1fr; gap: 0 1rem; }
@media (max-width: 560px) { .two-col { grid-template-columns: 1fr; } }
.field .hint { font-size: 12px; color: var(--text-muted); }
.field .error { font-size: 12px; color: var(--red); font-weight: 600; }
.input, .select, textarea.input {
  min-height: 42px; padding: 0 .8rem; width: 100%;
  border: 1px solid var(--border-input); border-radius: var(--radius-sm);
  background: color-mix(in srgb, var(--bg) 60%, var(--surface-1)); color: var(--text);
  font: inherit; font-size: 15px;
  transition: border-color var(--dur-1), box-shadow var(--dur-1);
}
textarea.input { padding: .6rem .8rem; resize: vertical; min-height: 88px; line-height: 1.5; }
.input:focus, .select:focus, textarea.input:focus {
  outline: none; border-color: var(--accent);
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--accent) 28%, transparent), 0 0 18px -6px var(--glow);
}
.input::placeholder, textarea.input::placeholder { color: var(--text-muted); }
.select {
  appearance: none; cursor: pointer; padding-right: 2.1rem;
  background-image: url("data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' width='12' height='12' viewBox='0 0 24 24' fill='none' stroke='%238A91AE' stroke-width='3' stroke-linecap='round' stroke-linejoin='round'><path d='M6 9l6 6 6-6'/></svg>");
  background-repeat: no-repeat; background-position: right .8rem center;
}
.checkbox { display: inline-flex; align-items: center; gap: .5rem; cursor: pointer; user-select: none; min-height: 32px; }
.checkbox input { width: 18px; height: 18px; accent-color: var(--accent-fill); }

/* ---- panels (.card is the panel) -------------------------------------- */
.card {
  background: var(--surface-1); border: 1px solid var(--border);
  border-radius: var(--radius); box-shadow: inset 0 1px 0 var(--highlight);
}
.card-pad { padding: 1.2rem 1.35rem; }
.card-head {
  display: flex; align-items: center; justify-content: space-between; gap: 1rem;
  padding: .95rem 1.35rem; border-bottom: 1px solid var(--border);
}
.card-head h2, .card-head h3 { font-size: 15px; }

/* ---- tables ----------------------------------------------------------- */
.table-wrap { overflow-x: auto; }
table.data { width: 100%; border-collapse: collapse; font-size: 13.5px; }
table.data th {
  text-align: left; font-weight: 600; font-size: 11px; text-transform: uppercase; letter-spacing: .06em;
  color: var(--text-muted); padding: .7rem 1rem; border-bottom: 1px solid var(--border);
  background: var(--surface-2); position: sticky; top: 0; z-index: 1;
}
table.data td { padding: .8rem 1rem; border-bottom: 1px solid var(--border); vertical-align: middle; }
table.data tbody tr { transition: background var(--dur-1); }
table.data tbody tr:hover { background: color-mix(in srgb, var(--accent) 7%, transparent); }
table.data tbody tr:has(.empty) { cursor: default; }
table.data tbody tr:has(.empty):hover { background: transparent; }
table.data tr:last-child td { border-bottom: none; }
:root[data-density="compact"] table.data td { padding: .42rem .8rem; }
:root[data-density="compact"] table.data th { padding: .5rem .8rem; }
.row-actions { display: flex; gap: .35rem; justify-content: flex-end; align-items: center; }
.num { font-family: var(--font-mono); font-variant-numeric: tabular-nums; text-align: right; letter-spacing: -.02em; }
.row-open { background: none; border: none; padding: 0; font: inherit; font-weight: 600;
  color: var(--accent-text); cursor: pointer; text-align: inherit; }
.row-open:hover { text-decoration: underline; }

/* ---- badges ----------------------------------------------------------- */
.badge {
  display: inline-flex; align-items: center; gap: .35rem; height: 22px; padding: 0 .55rem;
  border-radius: 999px; font-size: 11.5px; font-weight: 600;
  background: var(--surface-3); color: var(--text-soft);
}
.badge.dot::before { content: ""; width: 6px; height: 6px; border-radius: 50%; background: currentColor; box-shadow: 0 0 6px currentColor; }
.badge-green  { background: var(--green-soft);  color: var(--green); }
.badge-amber, .badge-gold { background: var(--amber-soft); color: var(--amber); }
.badge-red    { background: var(--red-soft);    color: var(--red); }
.badge-blue, .badge-violet { background: var(--accent-soft); color: var(--accent-text); }
.badge-gray   { background: var(--surface-3);   color: var(--text-soft); }

/* ---- segmented, pills, tabs ------------------------------------------- */
.segmented { display: inline-flex; background: var(--surface-2); border: 1px solid var(--border);
  border-radius: var(--radius-sm); padding: 3px; gap: 2px; }
.segmented button { border: none; background: transparent; color: var(--text-soft); border-radius: 6px;
  padding: .4rem .9rem; min-height: 32px; font: inherit; font-size: 13px; font-weight: 600; cursor: pointer;
  transition: background var(--dur-1) var(--ease-out), color var(--dur-1); }
.segmented button.active { background: var(--accent-soft); color: var(--accent-text); box-shadow: inset 0 0 0 1px color-mix(in srgb, var(--accent) 35%, transparent); }
.pill-row { display: flex; flex-wrap: wrap; gap: .45rem; }
.pill { border: 1px solid var(--border-strong); background: transparent; color: var(--text-soft);
  border-radius: 999px; padding: .45rem .9rem; min-height: 36px; font: inherit; font-size: 13px; font-weight: 600;
  cursor: pointer; transition: background var(--dur-1), color var(--dur-1), border-color var(--dur-1); }
.pill:hover { border-color: var(--accent); color: var(--text); }
.pill.active { background: var(--accent-fill); color: #fff; border-color: transparent; }
.tabs { display: flex; gap: .25rem; border-bottom: 1px solid var(--border); margin: -.2rem 0 1.2rem; overflow-x: auto; }
.tabs button { background: none; border: none; padding: .6rem .8rem; font: inherit; font-weight: 600; font-size: 13.5px;
  color: var(--text-muted); cursor: pointer; border-bottom: 2px solid transparent; margin-bottom: -1px; white-space: nowrap; }
.tabs button:hover { color: var(--text); }
.tabs button.active { color: var(--accent-text); border-bottom-color: var(--accent); }

/* ---- layout helpers --------------------------------------------------- */
.page { padding: 1.5rem 2rem 2.5rem; max-width: 1600px; }
.page-head { display: flex; align-items: flex-end; justify-content: space-between; gap: 1rem; margin-bottom: 1.4rem; flex-wrap: wrap; }
.page-head h1 { font-size: 24px; }
.page-head .sub { color: var(--text-soft); font-size: 13.5px; margin-top: .25rem; }
.toolbar { display: flex; gap: .6rem; align-items: center; flex-wrap: wrap; }
.grid { display: grid; gap: 1rem; }
.spacer { flex: 1; }
.muted { color: var(--text-muted); }
.soft  { color: var(--text-soft); }
.right { text-align: right; }
.mono  { font-family: var(--font-mono); font-variant-numeric: tabular-nums; letter-spacing: -.02em; }
.stack { display: flex; flex-direction: column; }
.flex  { display: flex; align-items: center; }
.gap-1 { gap: .5rem; } .gap-2 { gap: 1rem; } .gap-3 { gap: 1.5rem; }
@media (max-width: 720px) { .page { padding: 1.1rem 1rem 2rem; } }

.empty { display: flex; flex-direction: column; align-items: center; justify-content: center;
  width: 100%; text-align: center; padding: 3rem 1rem; color: var(--text-muted); }
.empty .big { width: 64px; height: 64px; margin: 0 auto .9rem; border-radius: 18px;
  display: flex; align-items: center; justify-content: center;
  background: var(--accent-soft); color: var(--accent-text); box-shadow: 0 0 24px -8px var(--glow); }
.empty .big app-icon, .empty .big > svg { width: 28px; height: 28px; }

/* ---- avatar (initials) ------------------------------------------------ */
.avatar { width: 28px; height: 28px; border-radius: 50%; flex-shrink: 0;
  display: inline-flex; align-items: center; justify-content: center;
  background: var(--accent-soft); color: var(--accent-text); font-size: 11px; font-weight: 700; }
.name-with-avatar { display: inline-flex; align-items: center; gap: .55rem; min-width: 0; }

/* ---- icons, spinner, skeleton ---------------------------------------- */
app-icon { line-height: 0; }
.ic { width: 18px; height: 18px; flex-shrink: 0; }
.ic-sm { width: 15px; height: 15px; }
.ic-lg { width: 22px; height: 22px; }
.spinner { width: 18px; height: 18px; border: 2px solid color-mix(in srgb, currentColor 28%, transparent);
  border-top-color: currentColor; border-radius: 50%; animation: spin .7s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
.loading-block { display: flex; align-items: center; justify-content: center; gap: .6rem; padding: 3rem; color: var(--text-muted); }
.skeleton { display: block; height: 14px; border-radius: 6px;
  background: linear-gradient(90deg, var(--surface-2), var(--surface-3), var(--surface-2)); background-size: 200% 100%;
  animation: shimmer 1.4s ease-in-out infinite; }
@keyframes shimmer { from { background-position: 100% 0; } to { background-position: -100% 0; } }

::-webkit-scrollbar { width: 12px; height: 12px; }
::-webkit-scrollbar-thumb { background: var(--border-strong); border-radius: 10px; border: 3px solid transparent; background-clip: content-box; }
::-webkit-scrollbar-thumb:hover { background: var(--text-muted); background-clip: content-box; }
```

- [ ] **Step 3: Update `index.html`.**

  1. Replace the Dancing Script `<link>` with:

     ```html
     <link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700&family=JetBrains+Mono:wght@400;600&family=Space+Grotesk:wght@500;600;700&display=swap">
     ```

  2. Set `<meta name="color-scheme" content="dark light">`.
  3. Replace the two theme-color metas with `<meta name="theme-color" content="#0B0D14">`; Task 2 updates it at runtime.
  4. Set the description to `ERP-Cafe — multi-tenant business ERP: sales, catalog, inventory, purchasing, staff and reporting.`
  5. Replace the favicon with an indigo hexagon mark:

     ```html
     <link rel="icon" href="data:image/svg+xml,<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='%237C83FF' stroke-width='2' stroke-linejoin='round'><path d='M12 2 21 7v10l-9 5-9-5V7z'/><circle cx='12' cy='12' r='3' fill='%237C83FF'/></svg>">
     ```

- [ ] **Step 4: Verify.**

  Run `npm test; echo $?; npm run lint; echo $?; npx ng build; echo $?`. Expected: all 0, with the same test count as Step 1.

  If the build warns about a component style budget, that is pre-existing; note it and change nothing.

- [ ] **Step 5: Commit.**

```bash
git add frontend/src/styles.scss frontend/src/index.html
git commit -m "[Change] Command centre design tokens and base styles"
```

---

### Task 2: Theme and density settings

**Files:**
- Create: `frontend/src/app/core/prefs.ts`, `frontend/src/app/core/theme.service.ts`, `frontend/src/app/core/density.service.ts`
- Test: `frontend/src/app/core/theme.service.spec.ts`, `frontend/src/app/core/density.service.spec.ts`
- Modify: `frontend/src/app/app.ts`, which injects both services so they apply at start-up.

**Interfaces:**
- **Produces:**
  - `readPref<T extends string>(key: string, allowed: readonly T[], fallback: T): T`
  - `writePref(key: string, value: string): void`
  - `ThemeService`:
    - `pref: Signal<ThemePref>`
    - `effective: Signal<'dark' | 'light'>`
    - `set(p: ThemePref): void`
    - `cycle(): void` (system → dark → light → system)
    - `type ThemePref = 'system' | 'dark' | 'light'`
  - `DensityService`:
    - `pref: Signal<Density>`
    - `set(d: Density): void`
    - `toggle(): void`
    - `type Density = 'comfortable' | 'compact'`
- **Storage keys:** `erpcafe.theme`, `erpcafe.density`.

- [ ] **Step 1: Write the failing tests.**

`frontend/src/app/core/theme.service.spec.ts`:

```ts
import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { stubLocalStorage } from './test-storage';
import { ThemeService } from './theme.service';

/** A matchMedia double whose (prefers-color-scheme: light) answer the test can flip. */
function fakeMatchMedia(initialLight: boolean) {
  let light = initialLight;
  const listeners: ((e: { matches: boolean }) => void)[] = [];
  const mql = {
    get matches() { return light; },
    addEventListener: (_: string, l: (e: { matches: boolean }) => void) => listeners.push(l),
    removeEventListener: () => undefined,
  };
  vi.stubGlobal('matchMedia', () => mql);
  return { flip(to: boolean) { light = to; listeners.forEach((l) => l({ matches: to })); } };
}

describe('ThemeService', () => {
  const html = () => document.documentElement;
  const service = () => {
    TestBed.configureTestingModule({ providers: [provideZonelessChangeDetection()] });
    const s = TestBed.inject(ThemeService);
    TestBed.tick();
    return s;
  };

  beforeEach(() => { stubLocalStorage(); html().removeAttribute('data-theme'); });
  afterEach(() => vi.unstubAllGlobals());

  it('defaults to system and leaves the OS in charge', () => {
    fakeMatchMedia(false);
    const s = service();
    expect(s.pref()).toBe('system');
    expect(s.effective()).toBe('dark');
    expect(html().hasAttribute('data-theme')).toBe(false);
  });

  it('follows the OS live while the setting is system', () => {
    const os = fakeMatchMedia(false);
    const s = service();
    os.flip(true);
    expect(s.effective()).toBe('light');
  });

  it('an explicit choice sets data-theme and is remembered', () => {
    fakeMatchMedia(false);
    const s = service();
    s.set('light');
    TestBed.tick();
    expect(html().getAttribute('data-theme')).toBe('light');
    expect(localStorage.getItem('erpcafe.theme')).toBe('light');
  });

  it('cycles system → dark → light → system', () => {
    fakeMatchMedia(false);
    const s = service();
    s.cycle(); expect(s.pref()).toBe('dark');
    s.cycle(); expect(s.pref()).toBe('light');
    s.cycle(); expect(s.pref()).toBe('system');
  });

  it('falls back to defaults when storage throws, and still applies a choice', () => {
    fakeMatchMedia(false);
    vi.stubGlobal('localStorage', {
      getItem: () => { throw new Error('blocked'); },
      setItem: () => { throw new Error('blocked'); },
      removeItem: () => undefined,
    });
    const s = service();
    expect(s.pref()).toBe('system');
    s.set('dark');
    TestBed.tick();
    expect(html().getAttribute('data-theme')).toBe('dark');
  });
});
```

`frontend/src/app/core/density.service.spec.ts`:

```ts
import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { stubLocalStorage } from './test-storage';
import { DensityService } from './density.service';

describe('DensityService', () => {
  beforeEach(() => {
    stubLocalStorage();
    document.documentElement.removeAttribute('data-density');
    TestBed.configureTestingModule({ providers: [provideZonelessChangeDetection()] });
  });
  afterEach(() => vi.unstubAllGlobals());

  it('defaults to comfortable', () => {
    const d = TestBed.inject(DensityService);
    TestBed.tick();
    expect(d.pref()).toBe('comfortable');
    expect(document.documentElement.getAttribute('data-density')).toBe('comfortable');
  });

  it('toggle switches to compact and remembers it', () => {
    const d = TestBed.inject(DensityService);
    d.toggle();
    TestBed.tick();
    expect(document.documentElement.getAttribute('data-density')).toBe('compact');
    expect(localStorage.getItem('erpcafe.density')).toBe('compact');
  });

  it('reads a remembered choice', () => {
    localStorage.setItem('erpcafe.density', 'compact');
    expect(TestBed.inject(DensityService).pref()).toBe('compact');
  });
});
```

- [ ] **Step 2: Run them to make sure they fail.**

  Run `npm test`. Expected: FAIL, because the modules `./theme.service` and `./density.service` cannot be resolved.

- [ ] **Step 3: Implement.**

`frontend/src/app/core/prefs.ts`:

```ts
/** Per-browser settings in localStorage; a blocked or throwing storage falls back to the default. */
export function readPref<T extends string>(key: string, allowed: readonly T[], fallback: T): T {
  try {
    const value = localStorage.getItem(key);
    return value !== null && (allowed as readonly string[]).includes(value) ? (value as T) : fallback;
  } catch {
    return fallback;
  }
}

/** Remembers a setting; when storage is blocked the choice simply lasts for this visit. */
export function writePref(key: string, value: string): void {
  try { localStorage.setItem(key, value); } catch { /* storage unavailable */ }
}
```

`frontend/src/app/core/theme.service.ts`:

```ts
import { Injectable, computed, effect, signal } from '@angular/core';
import { readPref, writePref } from './prefs';

export type ThemePref = 'system' | 'dark' | 'light';
const KEY = 'erpcafe.theme';
const ORDER: readonly ThemePref[] = ['system', 'dark', 'light'];
const THEME_COLOR = { dark: '#0B0D14', light: '#F6F7FB' } as const;

/** The colour theme: System follows the OS live; Dark and Light set data-theme on <html>. */
@Injectable({ providedIn: 'root' })
export class ThemeService {
  readonly pref = signal<ThemePref>(readPref(KEY, ORDER, 'system'));
  private readonly systemLight = signal(false);
  readonly effective = computed<'dark' | 'light'>(() => {
    const p = this.pref();
    if (p === 'system') return this.systemLight() ? 'light' : 'dark';
    return p;
  });

  constructor() {
    const mq = typeof matchMedia === 'function' ? matchMedia('(prefers-color-scheme: light)') : null;
    this.systemLight.set(!!mq?.matches);
    mq?.addEventListener('change', (e) => this.systemLight.set(e.matches));

    effect(() => {
      const root = document.documentElement;
      const p = this.pref();
      if (p === 'system') root.removeAttribute('data-theme');
      else root.setAttribute('data-theme', p);
      document.querySelector('meta[name="theme-color"]')?.setAttribute('content', THEME_COLOR[this.effective()]);
    });
  }

  set(p: ThemePref) { this.pref.set(p); writePref(KEY, p); }

  cycle() { this.set(ORDER[(ORDER.indexOf(this.pref()) + 1) % ORDER.length]); }
}
```

`frontend/src/app/core/density.service.ts`:

```ts
import { Injectable, effect, signal } from '@angular/core';
import { readPref, writePref } from './prefs';

export type Density = 'comfortable' | 'compact';
const KEY = 'erpcafe.density';

/** Table and list density, applied as data-density on <html>. */
@Injectable({ providedIn: 'root' })
export class DensityService {
  readonly pref = signal<Density>(readPref(KEY, ['comfortable', 'compact'] as const, 'comfortable'));

  constructor() {
    effect(() => document.documentElement.setAttribute('data-density', this.pref()));
  }

  set(d: Density) { this.pref.set(d); writePref(KEY, d); }

  toggle() { this.set(this.pref() === 'compact' ? 'comfortable' : 'compact'); }
}
```

`frontend/src/app/app.ts`: add `import { inject } from '@angular/core'`, `ThemeService` and `DensityService`, and add this body to `App`:

```ts
export class App {
  // constructed at start-up so the theme and density apply before the first paint of any page
  constructor() { inject(ThemeService); inject(DensityService); }
}
```

- [ ] **Step 4: Run the tests and make sure they pass.**

  Run `npm test; echo $?`. Expected: all specs pass, exit 0. Also `npm run lint; echo $?` and `npx ng build; echo $?` exit 0.

- [ ] **Step 5: Commit.**

```bash
git add frontend/src/app/core/prefs.ts frontend/src/app/core/theme.service.ts frontend/src/app/core/density.service.ts frontend/src/app/core/theme.service.spec.ts frontend/src/app/core/density.service.spec.ts frontend/src/app/app.ts
git commit -m "[Add] Theme and density settings"
```

---

### Task 3: App shell — sidebar rail, top bar, user menu

**Files:**
- Create: `frontend/src/app/layout/nav.ts`. The `NavItem` / `NavSection` types and the `NAV_SECTIONS` array move here from `shell.ts`. Change the icons: Products `package`; Stock `box` (kept); Ingredients `flask` (kept).
- Modify: `frontend/src/app/layout/shell.ts`, `frontend/src/app/layout/shell.scss`, `frontend/src/app/shared/icon.ts`
- Test: `frontend/src/app/layout/shell.spec.ts` (extend; the existing 3+ tests keep passing unchanged)

**Interfaces:**
- **Consumes:** `ThemeService` and `DensityService` (Task 2), `AuthService`, `OrganizationService`.
- **Produces:**
  - `NAV_SECTIONS: NavSection[]` and `interface NavItem { label: string; icon: string; path: string; perms?: string[] }` from `layout/nav.ts`.
  - On `ShellComponent`:
    - `collapsed: WritableSignal<boolean>` (storage key `erpcafe.sidebar`, values `'expanded' | 'rail'`);
    - `railed: Signal<boolean>`, true when `collapsed()` or the URL starts with `/pos`;
    - `pageTitle: Signal<{ section: string; label: string }>`.
  - Unchanged: `onBranchChange` and `logout`.
- **Icons:** add to `ICONS` in `shared/icon.ts`:

```ts
  package: '<path d="m7.5 4.27 9 5.15"/><path d="M21 8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16Z"/><path d="m3.3 7 8.7 5 8.7-5"/><path d="M12 22V12"/>',
  sun: '<circle cx="12" cy="12" r="4"/><path d="M12 2v2"/><path d="M12 20v2"/><path d="m4.93 4.93 1.41 1.41"/><path d="m17.66 17.66 1.41 1.41"/><path d="M2 12h2"/><path d="M20 12h2"/><path d="m6.34 17.66-1.41 1.41"/><path d="m19.07 4.93-1.41 1.41"/>',
  moon: '<path d="M12 3a6 6 0 0 0 9 9 9 9 0 1 1-9-9Z"/>',
  monitor: '<rect width="20" height="14" x="2" y="3" rx="2"/><line x1="8" x2="16" y1="21" y2="21"/><line x1="12" x2="12" y1="17" y2="21"/>',
  'panel-left': '<rect width="18" height="18" x="3" y="3" rx="2"/><path d="M9 3v18"/>',
  command: '<path d="M15 6v12a3 3 0 1 0 3-3H6a3 3 0 1 0 3 3V6a3 3 0 1 0-3 3h12a3 3 0 1 0-3-3"/>',
  table: '<path d="M12 3v18"/><rect width="18" height="18" x="3" y="3" rx="2"/><path d="M3 9h18"/><path d="M3 15h18"/>',
  cube: '<path d="M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z"/><path d="M3.3 7 12 12l8.7-5"/><path d="M12 22V12"/>',
  'arrow-up': '<path d="m5 12 7-7 7 7"/><path d="M12 19V5"/>',
  'arrow-down': '<path d="M12 5v14"/><path d="m19 12-7 7-7-7"/>',
```

**Template contract** for the new `ShellComponent` template. Keep the skip link, `#main-content`, `aria-current`, the mobile `.scrim`, Escape closing the mobile nav, and the focus-after-navigation logic.

```html
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
      <div class="crumbs"><span class="crumb-section">{{ pageTitle().section }}</span><span class="crumb-sep" aria-hidden="true">/</span><b>{{ pageTitle().label }}</b></div>
      <button type="button" class="palette-trigger" (click)="palette.open()" aria-label="Search or jump to (Control K)">
        <app-icon name="search" [size]="15" /><span class="pt-txt">Search or jump to…</span><kbd>⌘K</kbd>
      </button>
      <div class="spacer"></div>
      <div class="branch-switch"> <!-- the existing select markup, unchanged: aria-label="Active branch" -->
        …existing <select class="select" … (change)="onBranchChange($event)"> with options "{{ b.name }} · {{ b.code }}"…
      </div>
      <button type="button" class="btn btn-ghost btn-icon" (click)="theme.cycle()"
              [attr.aria-label]="'Theme: ' + theme.pref() + '. Change theme'">
        <app-icon [name]="theme.pref() === 'system' ? 'monitor' : theme.pref() === 'dark' ? 'moon' : 'sun'" [size]="17" />
      </button>
      <div class="user-menu">
        <button type="button" class="avatar-btn" (click)="userMenu.set(!userMenu())" aria-haspopup="menu"
                [attr.aria-expanded]="userMenu()" aria-label="Account menu"><span class="avatar">{{ initials() }}</span></button>
        @if (userMenu()) {
          <div class="menu glass" role="menu">
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
  @if (mobileNav()) { <div class="scrim" (click)="mobileNav.set(false)" aria-hidden="true"></div> }
</div>
<app-command-palette />
```

Until Task 4 lands, `palette.open()` and `<app-command-palette />` are not there. In this task, render the trigger button but bind its click to a no-op `openPalette()` method. Task 4 wires it up.

**Shell behaviour:**
- `scrolled = signal(false)`, set by `@HostListener('window:scroll') onScroll() { this.scrolled.set(window.scrollY > 4); }`. The top bar is frosted only once the page has scrolled; at the top it uses `background: var(--bg)`, so add `.topbar { background: var(--bg); }` before the `.glass` rule takes over.
- `userMenu = signal(false)`. Escape closes the user menu, and so does a click outside it: a `document:click` listener that ignores clicks inside `.user-menu`.
- `toggleRail()` flips `collapsed` and calls `writePref('erpcafe.sidebar', collapsed ? 'rail' : 'expanded')`. `collapsed` starts from `readPref('erpcafe.sidebar', ['expanded', 'rail'], 'expanded') === 'rail'`.
- `pageTitle` is computed from a `url` signal, updated on every `NavigationEnd`. It finds the `NavItem` whose path equals the URL path (`/` for Dashboard) and returns `{ section: section.title, label: item.label }`. When nothing matches, it returns `{ section: 'ERP-Cafe', label: '' }`.
- `railed` = `collapsed() || url().startsWith('/pos')`.

**`shell.scss`** (replace the whole file; keep it under 6 kB):

```scss
.shell { display: grid; grid-template-columns: 248px 1fr; min-height: 100vh; min-height: 100dvh; transition: grid-template-columns var(--dur-2) var(--ease-out); }
.shell.railed { grid-template-columns: 64px 1fr; }
.nav { position: sticky; top: 0; height: 100vh; height: 100dvh; display: flex; flex-direction: column;
  background: color-mix(in srgb, var(--surface-1) 88%, transparent); border-right: 1px solid var(--border); z-index: 20; }
.brand { display: flex; align-items: center; gap: .65rem; height: 60px; padding: 0 1.15rem; border-bottom: 1px solid var(--border); }
.logo { width: 24px; height: 24px; flex-shrink: 0; fill: none; stroke: var(--accent); stroke-width: 2; stroke-linejoin: round; filter: drop-shadow(0 0 6px var(--glow)); }
.logo circle { fill: var(--accent); stroke: none; }
.brand-txt { font-family: var(--font-display); font-weight: 700; font-size: 16px; letter-spacing: -.01em; white-space: nowrap; }
.nav-scroll { flex: 1; overflow-y: auto; padding: .75rem .6rem; }
.nav-section + .nav-section { margin-top: .9rem; }
.nav-title { font-size: 10.5px; font-weight: 700; letter-spacing: .08em; text-transform: uppercase; color: var(--text-muted); padding: 0 .65rem .35rem; white-space: nowrap; }
.nav-link { position: relative; display: flex; align-items: center; gap: .7rem; min-height: 38px; padding: 0 .65rem;
  border-radius: var(--radius-sm); color: var(--text-soft); font-weight: 500; font-size: 13.5px; white-space: nowrap; text-decoration: none; }
.nav-link:hover { background: var(--surface-2); color: var(--text); text-decoration: none; }
.nav-link.active { background: var(--accent-soft); color: var(--accent-text); }
.nav-link.active::before { content: ""; position: absolute; left: -.6rem; top: 8px; bottom: 8px; width: 3px; border-radius: 0 3px 3px 0;
  background: var(--accent); box-shadow: 0 0 12px var(--accent); }
.rail-toggle { margin: .5rem .6rem .8rem; align-self: flex-start; }
.railed .brand-txt, .railed .nav-label, .railed .nav-title { display: none; }
.railed .brand { justify-content: center; padding: 0; }
.railed .nav-link { justify-content: center; padding: 0; }
.railed .nav-section + .nav-section { margin-top: .4rem; padding-top: .4rem; border-top: 1px solid var(--border); }
.main { min-width: 0; display: flex; flex-direction: column; }
.topbar { position: sticky; top: 0; z-index: 15; display: flex; align-items: center; gap: .75rem; height: 60px; padding: 0 1.25rem; border-bottom: 1px solid var(--border); }
.hamburger { display: none; }
.crumbs { display: flex; align-items: baseline; gap: .45rem; min-width: 0; font-size: 13.5px; white-space: nowrap; }
.crumb-section { color: var(--text-muted); }
.crumb-sep { color: var(--text-muted); }
.palette-trigger { display: inline-flex; align-items: center; gap: .55rem; min-height: 36px; padding: 0 .55rem 0 .75rem; margin-left: 1rem;
  width: min(360px, 32vw); border: 1px solid var(--border-strong); border-radius: var(--radius-sm); background: var(--surface-1);
  color: var(--text-muted); font: inherit; font-size: 13px; cursor: pointer; }
.palette-trigger:hover { border-color: var(--accent); color: var(--text-soft); }
.pt-txt { flex: 1; text-align: left; }
kbd { font-family: var(--font-mono); font-size: 11px; padding: .1rem .4rem; border-radius: 5px; border: 1px solid var(--border-strong); color: var(--text-soft); background: var(--surface-2); }
.branch-switch .select { min-height: 36px; font-size: 13px; width: auto; max-width: 240px; }
.user-menu { position: relative; }
.avatar-btn { background: none; border: none; padding: 0; cursor: pointer; border-radius: 50%; }
.avatar-btn .avatar { width: 32px; height: 32px; font-size: 12px; }
.menu { position: absolute; right: 0; top: calc(100% + 8px); min-width: 240px; padding: .4rem; border: 1px solid var(--border);
  border-radius: var(--radius); box-shadow: var(--shadow-pop); z-index: 30; }
.menu-who { display: flex; flex-direction: column; padding: .55rem .6rem .65rem; border-bottom: 1px solid var(--border); margin-bottom: .3rem; min-width: 0; }
.menu-who small { color: var(--text-muted); }
.truncate { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 220px; }
.menu-item { display: flex; align-items: center; gap: .55rem; width: 100%; min-height: 36px; padding: 0 .6rem; border: none; background: none;
  color: var(--text); font: inherit; font-size: 13.5px; border-radius: var(--radius-sm); cursor: pointer; text-align: left; }
.menu-item:hover, .menu-item:focus-visible { background: var(--surface-2); }
.menu-item[aria-checked="true"] { color: var(--accent-text); }
.content { flex: 1; min-width: 0; }
.content:focus { outline: none; }
.scrim { position: fixed; inset: 0; background: var(--scrim); z-index: 18; }
@media (max-width: 900px) {
  .shell, .shell.railed { grid-template-columns: 1fr; }
  .nav { position: fixed; left: 0; top: 0; bottom: 0; width: 264px; transform: translateX(-100%); transition: transform var(--dur-2) var(--ease-out); }
  .nav-open .nav { transform: none; }
  .railed .brand-txt, .railed .nav-label, .railed .nav-title { display: revert; }
  .railed .nav-link { justify-content: flex-start; padding: 0 .65rem; }
  .rail-toggle { display: none; }
  .hamburger { display: inline-flex; }
  .crumbs .crumb-section, .crumbs .crumb-sep { display: none; }
  .palette-trigger { width: auto; margin-left: 0; }
  .pt-txt, .palette-trigger kbd { display: none; }
}
```

- [ ] **Step 1: Write the failing tests.** Append to `layout/shell.spec.ts`, in a new `describe` that reuses the existing setup pattern:

```ts
describe('ShellComponent layout', () => {
  let router: Router;
  beforeEach(async () => {
    stubLocalStorage();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([{ path: 'pos', component: BlankComponent }, { path: 'inventory/stock', component: BlankComponent }, { path: '', component: BlankComponent }]),
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    });
    TestBed.inject(AuthService).me.set({
      userId: 'u', email: 'a@b.c', companyId: 'co', branchIds: ['b1'], roles: ['OWNER'],
      permissions: ['inventory:read', 'sales:write'], permissionsByBranch: { b1: ['inventory:read', 'sales:write'] },
    });
    router = TestBed.inject(Router);
  });

  it('remembers a collapsed sidebar', () => {
    const shell = TestBed.createComponent(ShellComponent).componentInstance;
    shell.toggleRail();
    expect(shell.collapsed()).toBe(true);
    expect(localStorage.getItem('erpcafe.sidebar')).toBe('rail');
  });

  it('rails the sidebar on the POS screen without changing the saved choice', async () => {
    const shell = TestBed.createComponent(ShellComponent).componentInstance;
    await router.navigateByUrl('/pos');
    expect(shell.railed()).toBe(true);
    expect(shell.collapsed()).toBe(false);
  });

  it('titles the page from the navigation', async () => {
    const shell = TestBed.createComponent(ShellComponent).componentInstance;
    await router.navigateByUrl('/inventory/stock');
    expect(shell.pageTitle()).toEqual({ section: 'Inventory', label: 'Stock' });
  });
});
```

- [ ] **Step 2: Run the tests and make sure they fail.**

  Run `npm test`. Expected: FAIL, because `toggleRail`, `collapsed`, `railed` and `pageTitle` do not exist.

- [ ] **Step 3: Implement.** Create `layout/nav.ts`, update `shell.ts` per the contract and `shell.scss` as above, and add the icons.

- [ ] **Step 4: Verify.**
  - Run `npm test; echo $?`, `npm run lint; echo $?` and `npx ng build; echo $?`. Expected: all 0, and the old branch-switch tests still pass.
  - Start `npx ng serve` (backend on :8090 per `proxy.conf.json`, if available). Check the rail toggle, the theme cycle, the user menu and phone width (400px).

- [ ] **Step 5: Commit.**

```bash
git add frontend/src/app/layout/nav.ts frontend/src/app/layout/shell.ts frontend/src/app/layout/shell.scss frontend/src/app/layout/shell.spec.ts frontend/src/app/shared/icon.ts
git commit -m "[Change] Command centre shell with sidebar rail and top bar"
```

---

### Task 4: ⌘K command palette

**Files:**
- Create: `frontend/src/app/layout/command-palette.ts`, which holds the `PaletteService` and the `CommandPaletteComponent`.
- Modify:
  - `layout/shell.ts`: inject `PaletteService` as `palette`, import `CommandPaletteComponent`, bind the trigger to `palette.open()`, and remove `openPalette()`.
  - `shared/overlay-stack.ts`: add `get size(): number { return this.open.length; }`.
  - `features/inventory/purchase-orders.ts`: add a `new = input<string>()` route input (query param, through `withComponentInputBinding`). In the constructor, add `effect(() => { if (this.new() === '1' && this.auth.can('purchasing:write')) untracked(() => this.openNew()); })`.
  - `features/inventory/stock.ts`: add an `action = input<string>()` route input. In the constructor, add `effect(() => { if (this.action() === 'adjust' && this.auth.can('inventory:write')) untracked(() => this.openAction('adjust')); })`.
- Test: `frontend/src/app/layout/command-palette.spec.ts`

**Interfaces:**
- **Consumes:** `NAV_SECTIONS` (Task 3), `ThemeService.cycle()` (Task 2), `AuthService.canAny`, `AuthService.setActiveBranch`, `AuthService.logout`, `AuthService.me`, `OverlayStack`.
- **Produces:**
  - `PaletteService`: `isOpen: Signal<boolean>`, `open()`, `close()`, `toggle()`.
  - `CommandPaletteComponent` (selector `app-command-palette`).
  - `interface Command { id: string; group: 'Recent' | 'Go to' | 'Actions'; label: string; icon: string; hint?: string; run(): void }`.
  - Pure `export function filterCommands(all: Command[], query: string): Command[]`: case-insensitive subsequence match on the label. It keeps group order (Recent, Go to, Actions) and the original order within each group.
  - Recents use storage key `erpcafe.recent`: a JSON array of up to 4 paths, written through `writePref`, read with try/catch, and limited to paths still in the permitted nav.

**Behaviour:**
- **Opening and closing:**
  - A global `document:keydown` in `CommandPaletteComponent` opens or closes the palette on `(e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k'`, with `preventDefault()`. This also works inside text fields.
  - It does **not** open when `OverlayStack.size > 0` and the palette itself is not open, because a dialog or drawer is up.
  - While open, the palette registers in `OverlayStack` (push on open, remove on close), so Esc and Tab follow the stack rules.
- **Markup:**
  - A `.palette-backdrop` (fixed, `var(--scrim)`) around a `.palette.glass` dialog: `role="dialog" aria-modal="true" aria-label="Command palette"`, width `min(640px, 92vw)`, positioned 14vh from the top.
  - Inside, an `<input class="palette-input" role="combobox" aria-expanded="true" aria-controls="palette-list" [attr.aria-activedescendant]="activeId()" placeholder="Search or jump to…">`, focused on open.
  - Below it, `<ul id="palette-list" role="listbox">` with group headers (`role="presentation"`), and each row `<li role="option" [id]="'cmd-' + c.id" [attr.aria-selected]="i === active()">` showing an icon, the label and a right-aligned `hint` in `<kbd>`.
- **Keys:** ArrowDown and ArrowUp move `active`, wrapping. Enter runs the active command and closes. Esc closes when this is the top overlay. A click on a row runs it.
- **No results:** `<li class="palette-empty">No matches for "{{ query }}"</li>`. The query clears on close.
- **Commands:**
  - **Go to:** every nav item the user may open (the same permission filter as the shell), with `hint` = its section title. Runs `router.navigateByUrl(path)` and records it in recents.
  - **Recent:** up to 4 recent paths, shown only when the query is empty.
  - **Actions**, each shown only when the user holds the permission:
    - **New order** (`sales:write`) → `/pos`;
    - **New purchase order** (`purchasing:write`) → `/inventory/purchase-orders?new=1`;
    - **Adjust stock** (`inventory:write`) → `/inventory/stock?action=adjust`;
    - **Switch branch: {name}**, one per branch the user holds → `auth.setActiveBranch(id)`;
    - **Change theme** → `theme.cycle()`;
    - **Sign out** → `auth.logout()` then `location.href = '/login'`.
  - The branch names come from a `branches` input that the shell passes: `<app-command-palette [branches]="branches()" />`.
- **Styles:** the component's own `styles`, under 6 kB. Row height 44px; the active row gets `background: var(--accent-soft)` with `color: var(--accent-text)`. Group headers are 11px uppercase in `var(--text-muted)`.

- [ ] **Step 1: Write the failing tests** in `layout/command-palette.spec.ts`:

```ts
import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { provideRouter, Router } from '@angular/router';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { stubLocalStorage } from '../core/test-storage';
import { AuthService } from '../core/auth.service';
import { OverlayStack } from '../shared/overlay-stack';
import { CommandPaletteComponent, PaletteService, filterCommands, Command } from './command-palette';

const cmd = (id: string, group: Command['group'], label: string): Command => ({ id, group, label, icon: 'grid', run: () => undefined });

describe('filterCommands', () => {
  const all = [cmd('a', 'Go to', 'Purchase Orders'), cmd('b', 'Go to', 'Products'), cmd('c', 'Actions', 'New purchase order')];
  it('matches a subsequence case-insensitively and keeps group order', () => {
    expect(filterCommands(all, 'po').map((c) => c.id)).toEqual(['a', 'b', 'c']);
    expect(filterCommands(all, 'prod').map((c) => c.id)).toEqual(['b']);
  });
  it('returns nothing for a query that matches no label', () => {
    expect(filterCommands(all, 'zzz')).toEqual([]);
  });
});

describe('CommandPaletteComponent', () => {
  const key = (k: string, mods: Partial<KeyboardEventInit> = {}) =>
    document.dispatchEvent(new KeyboardEvent('keydown', { key: k, bubbles: true, ...mods }));

  beforeEach(() => {
    stubLocalStorage();
    TestBed.configureTestingModule({
      providers: [provideZonelessChangeDetection(), provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    TestBed.inject(AuthService).me.set({
      userId: 'u', email: 'a@b.c', companyId: 'co', branchIds: ['b1'], roles: [],
      permissions: ['inventory:read'], permissionsByBranch: { b1: ['inventory:read'] },
    });
    TestBed.inject(AuthService).setActiveBranch('b1');
  });

  it('opens on Ctrl+K and lists only screens the user may open', () => {
    const f = TestBed.createComponent(CommandPaletteComponent);
    f.detectChanges();
    key('k', { ctrlKey: true });
    f.detectChanges();
    const labels = Array.from(f.nativeElement.querySelectorAll('[role="option"]')).map((li) => (li as HTMLElement).textContent);
    expect(labels.some((t) => t!.includes('Stock'))).toBe(true);
    expect(labels.some((t) => t!.includes('Orders'))).toBe(false);
  });

  it('does not open over an open dialog', () => {
    const f = TestBed.createComponent(CommandPaletteComponent);
    TestBed.inject(OverlayStack).push({});
    key('k', { metaKey: true });
    expect(TestBed.inject(PaletteService).isOpen()).toBe(false);
  });

  it('runs the active command on Enter and closes', async () => {
    const f = TestBed.createComponent(CommandPaletteComponent);
    const router = TestBed.inject(Router);
    const nav = vi.spyOn(router, 'navigateByUrl').mockResolvedValue(true);
    TestBed.inject(PaletteService).open();
    f.detectChanges();
    const input = f.nativeElement.querySelector('.palette-input') as HTMLInputElement;
    input.value = 'stock';
    input.dispatchEvent(new Event('input'));
    f.detectChanges();
    key('Enter');
    expect(nav).toHaveBeenCalledWith('/inventory/stock');
    expect(TestBed.inject(PaletteService).isOpen()).toBe(false);
  });

  it('shows a no-results row', () => {
    const f = TestBed.createComponent(CommandPaletteComponent);
    TestBed.inject(PaletteService).open();
    f.detectChanges();
    const input = f.nativeElement.querySelector('.palette-input') as HTMLInputElement;
    input.value = 'qqqq';
    input.dispatchEvent(new Event('input'));
    f.detectChanges();
    expect(f.nativeElement.querySelector('.palette-empty')?.textContent).toContain('qqqq');
  });
});
```

- [ ] **Step 2: Run the tests and make sure they fail.**

  Run `npm test`. Expected: FAIL, because `./command-palette` cannot be resolved.

- [ ] **Step 3: Implement** per the behaviour above. Implementation notes:
  - Keyboard handling for Enter and the arrow keys lives on the document `keydown` listener and is guarded by `isOpen()` and `OverlayStack.isTop`, so the test's document-level Enter runs the command.
  - The input handler sets `query` from `(e.target as HTMLInputElement).value` and resets `active` to 0.

- [ ] **Step 4: Verify.** `npm test`, `npm run lint` and `npx ng build` all exit 0.

- [ ] **Step 5: Commit.**

```bash
git add frontend/src/app/layout/command-palette.ts frontend/src/app/layout/command-palette.spec.ts frontend/src/app/layout/shell.ts frontend/src/app/shared/overlay-stack.ts frontend/src/app/features/inventory/purchase-orders.ts frontend/src/app/features/inventory/stock.ts
git commit -m "[Add] Command palette"
```

---

### Task 5: Shared building blocks — drawer, KPI tile, sparkline, avatar, overlays

**Files:**
- Modify: `frontend/src/app/shared/modal.ts`. Add `@Input() variant: 'dialog' | 'drawer' = 'dialog'`.
- Modify: `frontend/src/app/shared/overlays.ts`, restyling the toasts and the confirm dialog to the new tokens: glass, `--shadow-pop`, and soft status tints for the toast icons. Behaviour is unchanged.
- Modify: `frontend/src/app/shared/status-badge.ts`. Keep the API; the styles come from the restyled `.badge` (Task 1).
- Create: `frontend/src/app/shared/kpi-tile.ts`, `frontend/src/app/shared/sparkline.ts`, `frontend/src/app/shared/avatar.ts`
- Test: `frontend/src/app/shared/modal.spec.ts` (new), `frontend/src/app/shared/kpi-tile.spec.ts`, `frontend/src/app/shared/sparkline.spec.ts`, `frontend/src/app/shared/avatar.spec.ts`

**Interfaces:**
- **Produces:**
  - `<app-modal variant="drawer">`, which slides in from the right, full height, `width` = max-width in px (default 520), with the same focus trap, Esc, backdrop close and focus restore.
  - `<app-kpi-tile label="Net sales" [value]="string" [delta]="number | null" [trend]="number[]" icon="banknote" />`. `delta` is a fraction, so `0.12` means +12%. When it is null, no change line is shown.
  - `<app-sparkline [values]="number[]" [width]="96" [height]="28" />`, which renders an `<svg>` holding one `<path class="spark-line">` and one `<path class="spark-fill">`, and nothing when fewer than 2 values are given.
  - `export function sparkPath(values: number[], w: number, h: number): string`, a pure function, exported from `sparkline.ts`.
  - `<app-avatar [name]="string" />` renders `<span class="avatar">` holding up to 2 initials (first letters of the first two words, or the first two characters of a one-word name), uppercase. It is `aria-hidden="true"`.
  - `export function initialsOf(name: string): string`.

**Drawer CSS** (in `modal.ts` styles, alongside the dialog styles, which become the new look too):

```css
.modal-backdrop { position: fixed; inset: 0; z-index: 100; display: flex; align-items: flex-start; justify-content: center;
  padding: 8vh 1rem 2rem; overflow-y: auto; overscroll-behavior: contain; background: var(--scrim); animation: fade var(--dur-2) var(--ease-out); }
.modal { width: 100%; background: var(--surface-1); border: 1px solid var(--border); border-radius: var(--radius-lg);
  box-shadow: var(--shadow-pop), 0 0 60px -20px var(--glow); animation: rise var(--dur-3) var(--ease-out); }
.modal:focus { outline: none; }
.modal-head { display: flex; align-items: center; justify-content: space-between; padding: 1rem 1.2rem 1rem 1.4rem; border-bottom: 1px solid var(--border); }
.modal-head h3 { font-size: 17px; }
.modal-body { padding: 1.3rem 1.4rem; }
.modal-foot { display: flex; justify-content: flex-end; gap: .6rem; padding: 1rem 1.4rem; border-top: 1px solid var(--border);
  background: var(--surface-2); border-radius: 0 0 var(--radius-lg) var(--radius-lg); }
.is-drawer.modal-backdrop { justify-content: flex-end; align-items: stretch; padding: 0; overflow: hidden; }
.is-drawer .modal { height: 100%; border-radius: 0; border-width: 0 0 0 1px; display: flex; flex-direction: column; animation: slide-in var(--dur-3) var(--ease-out); }
.is-drawer .modal-body { flex: 1; overflow-y: auto; }
.is-drawer .modal-foot { border-radius: 0; }
@keyframes fade { from { opacity: 0; } }
@keyframes rise { from { opacity: 0; transform: translateY(10px) scale(.98); } }
@keyframes slide-in { from { transform: translateX(32px); opacity: 0; } }
```

The template adds `[class.is-drawer]="variant === 'drawer'"` on `.modal-backdrop`.

**KPI tile:**
- Template:

  ```html
  <div class="kpi card">
    <div class="kpi-top"><span class="kpi-label">{{ label() }}</span><span class="kpi-ic" aria-hidden="true"><app-icon [name]="icon()" [size]="16" /></span></div>
    <div class="kpi-value">{{ value() }}</div>
    <div class="kpi-foot">
      @if (delta() !== null) {
        <span class="kpi-delta" [class.up]="delta()! >= 0" [class.down]="delta()! < 0">
          <app-icon [name]="delta()! >= 0 ? 'arrow-up' : 'arrow-down'" [size]="12" aria-hidden="true" />{{ deltaText() }}
        </span>
        <span class="sr-only">compared with the previous period</span>
      }
      <app-sparkline [values]="trend()" />
    </div>
  </div>
  ```

- `deltaText` = `(delta >= 0 ? '+' : '−') + Math.abs(delta * 100).toFixed(1) + '%'`.
- `.kpi-value` uses `font-family: var(--font-display)` at 26px, weight 600, with tabular numerals.
- `.up` uses `var(--green)` and `.down` uses `var(--red)`.
- The sparkline stroke is `var(--accent)`; its fill is `var(--accent)` at opacity .12.

- [ ] **Step 1: Write the failing tests.**

`shared/modal.spec.ts`:

```ts
import { Component, provideZonelessChangeDetection, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ModalComponent } from './modal';

@Component({
  imports: [ModalComponent],
  template: `@if (open()) { <app-modal title="Edit" [variant]="variant()" (close)="open.set(false)"><input id="a" /><button id="b">b</button><div footer></div></app-modal> }`,
})
class HostComponent { open = signal(true); variant = signal<'dialog' | 'drawer'>('drawer'); }

describe('ModalComponent drawer', () => {
  beforeEach(() => TestBed.configureTestingModule({ providers: [provideZonelessChangeDetection()] }));

  it('renders as a drawer', () => {
    const f = TestBed.createComponent(HostComponent);
    f.detectChanges();
    expect(f.nativeElement.querySelector('.modal-backdrop').classList).toContain('is-drawer');
  });

  it('closes on Escape', () => {
    const f = TestBed.createComponent(HostComponent);
    f.detectChanges();
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    f.detectChanges();
    expect(f.componentInstance.open()).toBe(false);
  });

  it('defaults to a centred dialog', () => {
    const f = TestBed.createComponent(HostComponent);
    f.componentInstance.variant.set('dialog');
    f.detectChanges();
    expect(f.nativeElement.querySelector('.modal-backdrop').classList).not.toContain('is-drawer');
  });
});
```

`shared/sparkline.spec.ts`:

```ts
import { sparkPath } from './sparkline';

describe('sparkPath', () => {
  it('spans the full width and flips y so bigger is higher', () => {
    expect(sparkPath([0, 10], 100, 20)).toBe('M0.0,19.0 L100.0,1.0');
  });
  it('draws a flat line in the middle when every value is equal', () => {
    expect(sparkPath([5, 5, 5], 100, 20)).toBe('M0.0,10.0 L50.0,10.0 L100.0,10.0');
  });
  it('is empty for fewer than two points', () => {
    expect(sparkPath([3], 100, 20)).toBe('');
  });
});
```

`sparkPath` uses a 1px vertical inset: `y = h - 1 - (v - min) / (max - min) * (h - 2)`, and `y = h / 2` when `max === min`. Coordinates are formatted with `toFixed(1)`, joined as `M x,y L x,y …`.

`shared/avatar.spec.ts`:

```ts
import { initialsOf } from './avatar';

describe('initialsOf', () => {
  it('takes the first letters of the first two words', () => expect(initialsOf('Nguyễn Văn An')).toBe('NV'));
  it('takes two letters of a single word', () => expect(initialsOf('admin@example.com')).toBe('AD'));
  it('is a placeholder for an empty name', () => expect(initialsOf('  ')).toBe('?'));
});
```

`shared/kpi-tile.spec.ts`:

```ts
import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { KpiTileComponent } from './kpi-tile';

describe('KpiTileComponent', () => {
  beforeEach(() => TestBed.configureTestingModule({ providers: [provideZonelessChangeDetection()] }));
  const render = (delta: number | null) => {
    const f = TestBed.createComponent(KpiTileComponent);
    f.componentRef.setInput('label', 'Net sales');
    f.componentRef.setInput('value', '1.200.000 ₫');
    f.componentRef.setInput('delta', delta);
    f.componentRef.setInput('trend', [1, 2, 3]);
    f.detectChanges();
    return f.nativeElement as HTMLElement;
  };
  it('shows a rise in green with a plus sign', () => {
    const el = render(0.125);
    expect(el.querySelector('.kpi-delta.up')?.textContent).toContain('+12.5%');
  });
  it('shows a fall with a minus sign', () => {
    expect(render(-0.04).querySelector('.kpi-delta.down')?.textContent).toContain('−4.0%');
  });
  it('hides the change line when there is no comparison', () => {
    expect(render(null).querySelector('.kpi-delta')).toBeNull();
  });
});
```

- [ ] **Step 2: Run the tests and make sure they fail.** Run `npm test`. Expected: FAIL (missing modules or the `variant` input).

- [ ] **Step 3: Implement.** Components use signal `input()` / `input.required()` except `modal.ts`, which keeps its `@Input`s.

  `overlays.ts` restyle:
  - The toast background becomes `var(--glass)` with `backdrop-filter: saturate(160%) blur(16px)`, the border `var(--border)` and the shadow `var(--shadow-pop)`.
  - The `.ti-*` icon circles use the soft status tints with the status colour as the icon colour: `.ti-success { background: var(--green-soft); color: var(--green); }`, `.ti-error { background: var(--red-soft); color: var(--red); }` and `.ti-info { background: var(--accent-soft); color: var(--accent-text); }`. Each of these pairs passes 4.5:1 in both themes. The confirm dialog uses the same panel styles as `.modal`.
  - Remove every `--azure`, `--material` and `--ease-spring` reference from `overlays.ts`.

- [ ] **Step 4: Verify.** `npm test`, `npm run lint` and `npx ng build` all exit 0, and `shared/overlays.spec.ts` still passes.

- [ ] **Step 5: Commit.**

```bash
git add frontend/src/app/shared/modal.ts frontend/src/app/shared/modal.spec.ts frontend/src/app/shared/kpi-tile.ts frontend/src/app/shared/kpi-tile.spec.ts frontend/src/app/shared/sparkline.ts frontend/src/app/shared/sparkline.spec.ts frontend/src/app/shared/avatar.ts frontend/src/app/shared/avatar.spec.ts frontend/src/app/shared/overlays.ts frontend/src/app/shared/status-badge.ts
git commit -m "[Add] Drawer, KPI tile, sparkline and avatar building blocks"
```

---

## Screen tasks (6–13): shared rules

Every screen task below applies these rules to its files:

1. **Remove café materials** from the file's template and styles:
   - `niche shelf-board contact-shadow paper-card dusty thermal thermal-edge thermal-edge-top t-rule printer-slot counter-lip print-in stamp stamp-in grain wood-counter swing-tag brewing brew-cup cup-steamed leader-row dots mini-coin name-cell obj-btn obj-del reveal-zone script`;
   - `<app-steam>` and the `SteamComponent` import;
   - every `--paper*`, `--wood*`, `--brand*`, `--azure*`, `--gold*`, `--nav-*`, `--material*`, `--violet*`, `--blue*`, `--surface` (old name), `--hairline`, `--ease-spring`, `--radius-xl`, `--shadow-sm/md/lg` and `--font-script` reference.

   **Done check:** `grep -nE "<the class list above as an alternation>|app-steam|--(paper|wood|brand|azure|gold|nav-|material|violet|blue|hairline|ease-spring|radius-xl|font-script)|--surface[^-]|--shadow-(sm|md|lg)" <file>` prints nothing.
2. **Replacements:**

   | Café pattern | Becomes |
   |---|---|
   | Card or shelf grids of objects (jars, crates, menu cards, tags, index cards) | A `.card` holding a `table.data`, with a `<button class="row-open">` in the first cell. Hover and focus actions go in `.row-actions` |
   | `.dusty` inactive look | `<app-status-badge [active]="…" />` plus `class="muted"` on the row |
   | `mini-coin` + `name-cell` | `<span class="name-with-avatar"><app-avatar [name]="…" />…</span>` |
   | `stamp` | `<span class="badge dot …">`, using the same status-to-badge mapping the file already has |
   | `thermal` receipts and ticket panels | A `.card` with `.card-head` and rows; totals right-aligned with `.num` |
   | `brewing` loaders | `<div class="loading-block"><span class="spinner"></span> Loading…</div>`, or 3 `.skeleton` rows in tables |

3. **Forms for create/edit** use `<app-modal variant="drawer" …>`. Confirmations and pickers stay dialogs (no `variant`).
4. **Keep:** every field, `id`/`for`, validation, permission check, service call, signal and method name. Only templates and styles change, plus any imports those need.
5. **Specs:** each spec in the task's folder passes. A spec that queried a removed café class follows the new markup and asserts the same behaviour.
6. **Size:** component styles stay under 6 kB. Shared patterns go to `styles.scss` under a `/* ---- screens: <name> */` header only when two or more screens use them.
7. **Verify** each task with `npm test; echo $?`, `npm run lint; echo $?` and `npx ng build; echo $?` (all 0), plus a manual look in both themes at desktop width and 400px width via `npx ng serve`.

### Task 6: Sign-in pages

**Files:** `features/auth/login.ts`, `login.scss` (if present, otherwise the component styles), `forgot-password.ts`, `reset-password.ts`; specs `login.spec.ts`, `forgot-password.spec.ts`, `reset-password.spec.ts`.

- [ ] **Step 1: Apply the shared rules.**
  - The page background is `var(--bg-glow), var(--bg)` plus a second radial glow at the bottom right (`color-mix(in srgb, var(--accent) 10%, transparent)`).
  - The form sits in a centred `.card.glass`, 400px wide.
  - The logo mark above the title is the same SVG as the shell brand, at 36px.
  - The title is "Sign in to ERP-Cafe" in `--font-display`. Remove the `.script` brand line and the café copy: no coffee words.
  - Keep the password eye toggle, every error message, and the `?reset=done` notice.
- [ ] **Step 2: Verify.** Run the done-check grep on the 3–4 files, then `npm test`, `npm run lint` and `npx ng build`.
- [ ] **Step 3: Commit.** Stage the changed auth files by path, then:

```bash
git commit -m "[Change] Command centre sign-in pages"
```

### Task 7: Dashboard (2D part)

**Files:** `features/dashboard/dashboard.ts`; Test: `features/dashboard/dashboard.spec.ts` (new).

**Interfaces:**
- **Consumes:** `KpiTileComponent` (Task 5), `ReportingService.summary(from?, to?)`, `ReportingService.salesByDay(from, to)`, `reportPeriod(now, count)` from `features/reports/reports.ts`.
- **Produces:** a `<section class="towers-slot">` placeholder after the KPI row, which Task 15 fills. In this task it renders nothing.

**KPI row** (four `<app-kpi-tile>`):

| Tile | Value | Delta | Shown when |
|---|---|---|---|
| Net sales today | `summary.netSales \| money` | `pct(today.netSales, yesterday.netSales)` | `report:read` |
| Orders today | `summary.orderCount` | `pct` of order counts | `report:read` |
| Average order | `netSales / orderCount` (`'—'` when there are no orders) | `pct` of averages | `report:read` |
| Gross margin | `summary.grossMargin \| money` (an amount: net sales − COGS) | `pct` of gross margin amounts | `report:read` |

- **Data:** today = `summary()`; yesterday = `summary(fromYesterday, toYesterday)`, from `reportPeriod(Date.now() - 86_400_000, 1)` with `.from.toISOString()` and `.to.toISOString()`. The `trend` for every tile = the `netSales` values of `salesByDay` for `reportPeriod(Date.now(), 7)`, zero-filled with `zeroFillDays`.
- **`pct(cur, prev)`** = `prev === 0 ? null : (cur - prev) / prev`. Export it from `dashboard.ts`.
- **Without `report:read`:** the KPI row shows the existing non-report tiles (Recent orders count, Active products, Low-stock items) as `app-kpi-tile`s with `delta` null and `trend` `[]`.
- **Keep** the recent orders table, the low-stock list and their loading, error and retry states. Replace the "brewing" loaders with `.loading-block`, remove the script greeting and steam, and keep the `<h1>Dashboard</h1>` and `sub` line.

- [ ] **Step 1: Write the failing test** in `dashboard.spec.ts`:

```ts
import { pct } from './dashboard';

describe('pct', () => {
  it('is the change against the previous value', () => expect(pct(120, 100)).toBeCloseTo(0.2));
  it('is null when there is nothing to compare with', () => expect(pct(50, 0)).toBeNull());
  it('is negative for a fall', () => expect(pct(80, 100)).toBeCloseTo(-0.2));
});
```

- [ ] **Step 2: Run it to make sure it fails.** Run `npm test`. Expected: FAIL, because `pct` is not exported.
- [ ] **Step 3: Implement** the KPI row, the shared rules and `pct`.
- [ ] **Step 4: Verify.** `npm test`, `npm run lint` and `npx ng build` all exit 0, and the done-check grep prints nothing.
- [ ] **Step 5: Commit.**

```bash
git add frontend/src/app/features/dashboard/dashboard.ts frontend/src/app/features/dashboard/dashboard.spec.ts
git commit -m "[Change] Command centre dashboard with KPI tiles"
```

### Task 8: POS and Orders

**Files:** `features/sales/pos.ts`, `features/sales/orders.ts`; specs `pos.spec.ts` (keeps `.grand b` and every assertion).

- [ ] **Step 1: POS.**
  - **Category tabs:** `.pill-row` of `.pill`s.
  - **Product grid:**
    - `grid-template-columns: repeat(auto-fill, minmax(150px, 1fr))`;
    - each product is a `<button class="pos-tile">`: `min-height: 96px`, `background: var(--surface-2)`, border `var(--border)`, radius `var(--radius)`, name in 14px/600, price in `.mono` `var(--accent-text)`;
    - on hover the border is `var(--accent)` with `box-shadow: 0 0 0 1px var(--accent), 0 8px 24px -12px var(--glow)`.
  - **Order panel** on the right: a `.card` with `.card-head` ("Current order") and rows of line, quantity steppers and money. It keeps the `.grand` total element with its `<b>`, plus the Pay button (`.btn-primary.btn-lg.btn-block`).
  - **Remove** the printer slot, thermal paper, counter lip, wood, paper menu cards, steam, `print-in` and the PAID stamp.
  - **After a successful payment:** show a `.paid-check` element, a 56px circle in `var(--green-soft)` with a `check` icon in `var(--green)` and a `0 0 24px var(--green)` glow, scaling from .8 to 1 over `var(--dur-2)`. Next to it, the text "Paid" and the total.
  - **Required-option hints** move from `.thermal .btn-outline.needs-opts` to `.btn-outline.needs-opts` (`border-color: var(--amber); color: var(--amber); background: var(--amber-soft)`). Move this and `.pay-hint` from the lively section into the POS component styles.
  - **Touch targets** are at least 44px.
- [ ] **Step 2: Orders.**
  - The list becomes `table.data`.
  - The order detail opens in `<app-modal variant="drawer" [width]="560">` instead of the thermal slip.
  - Status shows as `.badge.dot` (no stamp).
  - Refund, void and cancel keep their `confirm.ask({ danger: true })`.
- [ ] **Step 3: Verify.** The done-check grep prints nothing for both files; `npm test` (pos.spec unchanged in intent), `npm run lint` and `npx ng build` exit 0.
- [ ] **Step 4: Commit.** Stage the two files, plus any spec touched, by path, then:

```bash
git commit -m "[Change] Command centre POS and orders"
```

### Task 9: Catalog — products, product editor, categories, modifiers

**Files:** `features/catalog/products.ts`, `product-editor.ts`, `categories.ts`, `modifiers.ts`; spec `products.spec.ts`.

- [ ] **Step 1: Products.**
  - The menu-card shelves grouped by category become one `table.data` with these columns: Product (`row-open`), Category, Price (`.num`), Status (`app-status-badge`) and actions. The category grouping stays, as a `.segmented` or `.pill-row` filter above the table; keep the existing filter signal.
  - The product editor opens as `<app-modal variant="drawer" [width]="640">`.
- [ ] **Step 2: Categories.** The kraft swing tags become a `table.data` (Name, Products count if the template shows it, Status, actions), and editing opens in a drawer.
- [ ] **Step 3: Modifiers.**
  - Keep the accordion (`.mod-group`).
  - Replace the ruled paper index cards with `.card` sections and `.leader-row` price rows with a flex row (name, `.spacer`, `.num` price).
  - Editing opens in a drawer.
- [ ] **Step 4: Verify.** The done-check grep prints nothing for all four files; `npm test` (products.spec passes), `npm run lint` and `npx ng build` exit 0.
- [ ] **Step 5: Commit.**

```bash
git commit -m "[Change] Command centre catalog screens"
```

### Task 10: Inventory — ingredients, suppliers, items, purchase orders, stock (2D)

**Files:** `features/inventory/ingredients.ts`, `suppliers.ts`, `items.ts`, `purchase-orders.ts`, `stock.ts`; specs `purchase-orders.spec.ts`, `stock.spec.ts`.

- [ ] **Step 1: Ingredients and suppliers.** The pantry jars and delivery crates become `table.data`:
  - **Ingredients:** Name, Category, Base unit, Status, actions.
  - **Suppliers:** Name, Phone, Email, Status, actions.
  - Editing opens in drawers.
- [ ] **Step 2: Items.** `table.data` (already a table: apply the restyle), with drawer edit.
- [ ] **Step 3: Purchase orders.**
  - The list becomes `table.data`.
  - The detail panel loses the clipboard and pencil note and becomes a `.card` with `.card-head`.
  - "New purchase order" opens a drawer at `[width]="720"`.
  - The status stamps become `.badge.dot`.
  - Keep the `new` input added in Task 4.
- [ ] **Step 4: Stock.**
  - Remove the low-stock crate tint and the coin timeline styling. Movements become a plain list inside a `.card`, with the type as a badge and `.num` quantities.
  - Keep the `action` input added in Task 4.
  - Add a `.view-switch` placeholder `<div class="segmented" role="group" aria-label="Stock view">` containing only a pressed "Table" button for now; Task 16 adds "3D".
  - The adjust, waste and reorder forms open in drawers.
- [ ] **Step 5: Verify.** The done-check grep prints nothing for all five files; `npm test` (`purchase-orders.spec` and `stock.spec` pass), `npm run lint` and `npx ng build` exit 0.
- [ ] **Step 6: Commit.**

```bash
git commit -m "[Change] Command centre inventory screens"
```

### Task 11: Staff — team, shifts

**Files:** `features/staff/team.ts`, `shifts.ts`; spec `shifts.spec.ts`.

- [ ] **Step 1: Team.**
  - Team rows use `name-with-avatar` + `<app-avatar>`.
  - The `.dusty` look becomes a status badge with a `muted` row.
  - Editing opens in a drawer.
  - The pay-rate visibility rule is unchanged.
- [ ] **Step 2: Shifts.**
  - Restyle the timeline bars: `background: var(--accent-soft)`, border-left `3px solid var(--accent)`, and the in-progress shift in `var(--green-soft)` / `var(--green)`.
  - Clock-in and clock-out buttons use `.btn-primary` and `.btn-outline`.
- [ ] **Step 3: Verify** with the grep plus `npm test`, `npm run lint` and `npx ng build`. **Step 4: Commit** `[Change] Command centre staff screens`.

### Task 12: Reports

**Files:** `features/reports/reports.ts`; spec `reports.spec.ts` (it checks the SVG `path`, `line` and `circle` elements, which stay).

- [ ] **Step 1: Apply the shared rules.**
  - Chart series use `var(--chart-1)` and `var(--chart-2)`; grid lines use `var(--border)`; axis labels use `var(--text-muted)`, 11px `--font-mono`.
  - The KPI summary becomes `<app-kpi-tile>`s, with `delta` null and `trend` from the daily series.
  - The outlets table uses `name-with-avatar`.
  - Remove the `grain`, `brewing`, `cup-steamed`, `mini-coin` and `--wood` usages.
- [ ] **Step 2: Verify** with the grep plus `npm test`, `npm run lint` and `npx ng build`. **Step 3: Commit** `[Change] Command centre reports`.

### Task 13: Organization — companies, branches, users, roles

**Files:** `features/organization/companies.ts`, `branches.ts`, `users.ts`, `roles.ts`; spec `branches.spec.ts`.

- [ ] **Step 1: Apply the shared rules.**
  - Use `name-with-avatar` + `<app-avatar>` in the users and branches name cells.
  - Create and edit open in drawers.
  - The role permission matrix keeps its checkboxes, restyled: the header row sticky, and checked cells tinted `var(--accent-soft)`.
  - Remove the `--azure` and `--gold` usages in `roles.ts`.
- [ ] **Step 2: Verify** with the grep plus `npm test` (`branches.spec` passes), `npm run lint` and `npx ng build`. **Step 3: Commit** `[Change] Command centre organization screens`.

---

### Task 14: Three.js foundation — stage helper, WebGL check, scene models

**Files:**
- Modify: `frontend/package.json` and `package-lock.json`. Run `npm install three@$(npm view three version) --save-exact`. If `node_modules/three/package.json` has no `"types"` field, also run `npm install @types/three@$(npm view @types/three version) --save-dev --save-exact`.
- Create:
  - `frontend/src/app/three/webgl.ts`
  - `frontend/src/app/three/three-stage.ts`
  - `frontend/src/app/three/tower-model.ts`
  - `frontend/src/app/three/shelf-model.ts`
- Test:
  - `frontend/src/app/three/webgl.spec.ts`
  - `frontend/src/app/three/three-stage.spec.ts`
  - `frontend/src/app/three/tower-model.spec.ts`
  - `frontend/src/app/three/shelf-model.spec.ts`

**Interfaces (Produces):**

```ts
// webgl.ts
/** True when the browser can create a WebGL context (cached after the first check). */
export function hasWebGL(): boolean;

// tower-model.ts
export interface TowerSpec { branchId: string; code: string; name: string; netSales: number; orderCount: number;
  netMargin: number; height: number; top: boolean; slab: boolean; loss: boolean; x: number; z: number; }
export interface TowerModel { towers: TowerSpec[]; others: { count: number; netSales: number } | null; }
export const TOWER_MAX_HEIGHT = 6;
export const TOWER_MIN_HEIGHT = 0.25;
export const TOWER_LIMIT = 24;
export const TOWER_GAP = 2.2;
/** Branch performance → towers: top 24 by net sales, heights relative to the best, laid out on a centred grid. */
export function towerModel(outlets: BranchPerformance[]): TowerModel;

// shelf-model.ts
export type Level = 'ok' | 'low' | 'empty';
export interface ContainerSpec { ingredientId: string; name: string; unit: string; onHand: number; reorderLevel: number;
  avgUnitCost: string; fill: number; level: Level; noReorderLevel: boolean; shelf: number; slot: number; }
export interface ShelfSpec { category: string; index: number; containers: ContainerSpec[]; }
/** Stock + ingredients → one shelf per category (alphabetical, "Uncategorised" last), containers alphabetical. */
export function shelfModel(stock: StockItemResponse[], ingredients: IngredientResponse[]): ShelfSpec[];

// three-stage.ts
/** Owns one renderer + scene + camera for a host element: resize, render-on-demand, pause off-screen, dispose. */
export class ThreeStage {
  constructor(three: typeof import('three'), host: HTMLElement, opts?: { maxPixelRatio?: number });
  readonly scene: import('three').Scene;
  readonly camera: import('three').PerspectiveCamera;
  /** Ask for one more frame (render-on-demand). */
  invalidate(): void;
  /** Called every frame while it returns true, e.g. an orbit; the stage stops looping when it returns false. */
  setAnimator(fn: ((dtMs: number) => boolean) | null): void;
  destroy(): void;
}
```

**Rules for the models:**
- **`towerModel`:**
  - parses `summary.netSales` and `summary.netMargin` with `Number()`;
  - sorts by netSales descending and keeps `TOWER_LIMIT`; the rest go to `others` (count + summed netSales), and `others` is null when there are none;
  - `height` = `max(TOWER_MIN_HEIGHT, netSales / maxNetSales * TOWER_MAX_HEIGHT)`, and `TOWER_MIN_HEIGHT` when maxNetSales is 0;
  - `top` is true only for the first tower, and only when its netSales > 0;
  - `slab` = `netSales === 0`; `loss` = `netMargin < 0`;
  - grid: `cols = ceil(sqrt(n))`, `x = (col - (cols - 1) / 2) * TOWER_GAP`, `z = (row - (rows - 1) / 2) * TOWER_GAP`.
- **`shelfModel`:**
  - joins stock to ingredients by id and drops stock rows whose ingredient is unknown;
  - `onHand = Number(quantityOnHand)`, `reorderLevel = Number(reorderLevel)`;
  - `fill`:
    - when `reorderLevel > 0`: `clamp(onHand / max(3 * reorderLevel, onHand), 0, 1)` (but `0` when `onHand <= 0`);
    - when `reorderLevel <= 0`: `onHand > 0 ? 1 : 0`, with `noReorderLevel = true`;
  - `level` = `onHand <= 0 ? 'empty' : belowReorderLevel ? 'low' : 'ok'`;
  - `shelf` = the shelf index; `slot` = the index within the shelf.

**`ThreeStage` mechanics:**
- **Setup:**
  - `new three.WebGLRenderer({ antialias: true, alpha: true })`, pixel ratio `min(devicePixelRatio, opts.maxPixelRatio ?? 2)`, canvas appended to `host`;
  - camera `PerspectiveCamera(40, w/h, 0.1, 200)`.
- **Sizing:** a `ResizeObserver` on `host` updates the size and aspect, then calls `invalidate()`.
- **Visibility:** an `IntersectionObserver` sets `visible`, and `document.visibilitychange` sets `hidden`. The loop runs only when `visible && !hidden`.
- **Loop:**
  - `invalidate()` schedules one `requestAnimationFrame` if none is pending.
  - Each frame: if an animator is set and returns true, schedule another frame; then `renderer.render(scene, camera)`.
- **`destroy()`:**
  - disconnects the observers and removes the listener;
  - cancels the pending frame;
  - traverses `scene` and calls `dispose()` on every `geometry` and `material` (arrays included);
  - calls `renderer.dispose()` and removes the canvas.

**Tests:**
- `three-stage.spec.ts` must not need WebGL. Pass a fake `three` module object with `WebGLRenderer` / `Scene` / `PerspectiveCamera` test doubles (`vi.fn` classes) and assert that:
  - `destroy()` calls `dispose` on a geometry and a material added to the scene, calls `renderer.dispose()`, and removes the canvas from the host;
  - `invalidate()` twice before a frame schedules exactly one `requestAnimationFrame` (stub `requestAnimationFrame` with `vi.fn`).

  Stub `ResizeObserver` and `IntersectionObserver` with `vi.stubGlobal`.
- `webgl.spec.ts`: with `HTMLCanvasElement.prototype.getContext` stubbed to return `null`, `hasWebGL()` is `false`. Reset its cache through an exported `resetWebGLCacheForTests()`.
- `tower-model.spec.ts`: assert each rule above with three branches (`[100, 50, 0]` netSales, one with netMargin `-0.1`): heights `6`, `3`, `0.25`; `top` only on the first; `slab` on the third; `loss` on the second; a 2×2 grid's x positions `-1.1, 1.1, -1.1`. Also, 30 branches give 24 towers and `others.count === 6`.
- `shelf-model.spec.ts`: assert:
  - fill `1` at `onHand 30, reorder 10`;
  - fill `0.5` at `onHand 15, reorder 10`;
  - level `low` when `belowReorderLevel`;
  - level `empty` at `onHand 0`;
  - `noReorderLevel` with fill `1` at `reorder 0, onHand 5`;
  - categories sorted, with "Uncategorised" last;
  - unknown ingredient ids dropped.

- [ ] **Step 1:** Install the packages; write all four specs exactly per the bullets, using concrete numbers.
- [ ] **Step 2:** Run `npm test`. Expected: FAIL (modules missing).
- [ ] **Step 3:** Implement the four files. `three-stage.ts` imports only *types* from `three` (`import type`). The module object is passed in, so nothing pulls `three` into the initial bundle.
- [ ] **Step 4:** `npm test`, `npm run lint` and `npx ng build` all exit 0. Confirm the initial bundle total in the build output is unchanged from Task 13, because `three` is not imported yet.
- [ ] **Step 5: Commit.**

```bash
git add frontend/package.json frontend/package-lock.json frontend/src/app/three/
git commit -m "[Add] Three.js stage helper and scene models"
```

### Task 15: Dashboard branch towers

**Files:**
- Create: `frontend/src/app/features/dashboard/branch-towers.ts`
- Modify: `features/dashboard/dashboard.ts`, so the `towers-slot` renders `<app-branch-towers />` when `auth.can('report:read')`.
- Test: `frontend/src/app/features/dashboard/branch-towers.spec.ts`

**Interfaces:**
- **Consumes:** `towerModel`, `TowerSpec`, `TowerModel`, `ThreeStage`, `hasWebGL` (Task 14); `ReportingService.outlets(from, to)`; `reportPeriod`; `MoneyPipe`; `ThemeService.effective` (Task 2).
- **Produces:** `BranchTowersComponent` (selector `app-branch-towers`). For tests it exposes `period: WritableSignal<1 | 7 | 30>`, `model: Signal<TowerModel>`, `mode: Signal<'3d' | 'table' | 'loading' | 'error' | 'empty'>` and `focused: WritableSignal<TowerSpec | null>`.

**Behaviour:**
- **Panel:** a `.card` with `.card-head`: "Branch performance" plus a `.segmented` for Today, 7 days and 30 days.
- **Data:** `outlets(reportPeriod(Date.now(), period).from.toISOString(), …to.toISOString())` on start and on every period change, using `switchMap` so a stale response cannot win.
- **`mode`:**
  - `'loading'` until the first answer;
  - `'error'` on failure, with a Retry button;
  - `'empty'` when there are no towers, with an `.empty` "No branch sales in this period";
  - otherwise `'3d'` when `hasWebGL()`, else `'table'`.
- **Table fallback:** also reachable by a `.btn-ghost.btn-sm` "View as table" toggle. It is a `table.data` with columns Branch / Code / Net sales / Orders / Net margin, plus a note `<p class="muted">3D view isn't available on this device.</p>` when WebGL is missing.
- **3D:**
  - Inside a `<div class="stage" aria-hidden="true">`, 360px tall, call `const THREE = await import('three')`, then create a `ThreeStage`.
  - **Scene:**
    - a `GridHelper(24, 24)` whose colour is read from `getComputedStyle(document.documentElement).getPropertyValue('--border')`;
    - one `BoxGeometry(1, 1, 1)` shared by all towers, each mesh scaled to `(1, height, 1)` and positioned at `y = height / 2`;
    - material `MeshStandardMaterial({ color: accent, emissive: accent, emissiveIntensity: top ? 0.9 : 0.45, roughness: .35, metalness: .2 })`, where `accent` is read from `--accent`; a slab uses `--text-muted` with emissive 0;
    - a loss ring: a `RingGeometry(0.75, 0.9, 32)` rotated flat, in `--red`;
    - lights: an `AmbientLight` (0.6) plus a `DirectionalLight` (1.0);
    - camera at `(10, 9, 12)`, looking at the origin.
  - **Labels:** each tower's branch code is an absolutely positioned `<button class="tower-label">` overlay, placed every frame by projecting the tower's top position (`vector.project(camera)`). Each button is focusable, with `aria-label="{name}: net sales {money}, {orders} orders"`. Focusing or hovering one sets `focused` and shows a `.tower-tip` card (name, net sales, orders, net margin as a money amount, a `routerLink="/reports"` "View report" link).
  - **Orbit:**
    - an animator rotates the tower group by `dt * 0.00012` rad, until the first `pointerdown` on the stage, or for good when `matchMedia('(prefers-reduced-motion: reduce)').matches`;
    - pointer drag rotates the group around Y by `dx * 0.01`, clamped to ±π;
    - no zoom.
- **Theme:** an `effect` on `theme.effective()` re-reads the tokens, updates the material colours, and calls `invalidate()`.
- **Teardown:** `ngOnDestroy` calls `stage.destroy()`.

- [ ] **Step 1: Write failing specs** with `HttpTestingController`:
  - (a) It requests `/reports/outlets` once on start and again when `period.set(7)`.
  - (b) With `hasWebGL` mocked to `false` (`vi.mock('../../three/webgl', () => ({ hasWebGL: () => false }))`), after flushing two outlets the template shows `table.data` with 2 body rows and the "isn't available" note.
  - (c) Flushing `[]` gives `mode() === 'empty'` and the `.empty` text.
  - (d) A 500 gives `mode() === 'error'` and a Retry button that re-requests.
- [ ] **Step 2:** `npm test`. Expected: FAIL.
- [ ] **Step 3:** Implement. Keep the component styles under 6 kB: `.stage`, `.tower-label`, `.tower-tip`.
- [ ] **Step 4:** `npm test`, `npm run lint` and `npx ng build` exit 0.
  - In the build output there must be a lazy chunk containing three (look for a chunk over 100 kB that is not in "Initial chunk files"), and the initial total must stay under 600 kB.
  - Check it in a browser with `ng serve` in both themes: drag, Tab through the labels, and the tip.
- [ ] **Step 5: Commit.**

```bash
git add frontend/src/app/features/dashboard/branch-towers.ts frontend/src/app/features/dashboard/branch-towers.spec.ts frontend/src/app/features/dashboard/dashboard.ts
git commit -m "[Add] 3D branch towers on the dashboard"
```

### Task 16: Stock 3D shelves

**Files:**
- Create: `frontend/src/app/features/inventory/stock-shelves.ts`
- Modify: `features/inventory/stock.ts`. The `.view-switch` gains "3D"; the view is stored under `erpcafe.stockView` with values `'table' | '3d'` (default `'table'`).
- Test: `frontend/src/app/features/inventory/stock-shelves.spec.ts`, and extend `stock.spec.ts`.

**Interfaces:**
- **Consumes:** `shelfModel`, `ShelfSpec`, `ContainerSpec` (Task 14); `ThreeStage`; `hasWebGL`; `ThemeService`.
- **Produces:** `StockShelvesComponent` (selector `app-stock-shelves`):
  - inputs `stock: StockItemResponse[]` (required) and `ingredients: IngredientResponse[]` (required);
  - output `pick: OutputEmitterRef<string>`, which emits the ingredientId;
  - exposes `shelves: Signal<ShelfSpec[]>`, `category: WritableSignal<string | null>` and `search: WritableSignal<string>`.
- **On `StockComponent`:**
  - `stockView: WritableSignal<'table' | '3d'>`;
  - picking a container runs `selectedIngredientId.set(id); openAction('adjust')`, the same path a table row uses.

**Behaviour:**
- **Controls:** above the stage, a `.pill-row` of categories (All plus each shelf category) sets `category`. A search `.input` (`id="shelf-search"` with a `<label for>`) sets `search`.
- **Without WebGL:** the component renders `<p class="muted">3D view isn't available on this device.</p>`, and `StockComponent` switches the view back to Table.
- **3D scene:**
  - Each shelf is a thin `BoxGeometry` plank (`--surface-3` colour) at `y = -shelf * 2.4`, with containers spaced `1.3` apart along x.
  - Containers are drawn with `InstancedMesh`:
    - one for the glass shells: `CylinderGeometry(0.45, 0.45, 1.4, 24)`, with `MeshPhysicalMaterial({ transparent: true, opacity: .18, roughness: .1 })`;
    - one per level for the fills, coloured `--accent`, `--amber` or `--red`: each instance is scaled to `(0.4, max(0.02, fill) * 1.36, 0.4)` and sits on the shelf.
  - Low containers pulse their fill's emissive intensity between .3 and .8 every 1.6 s through the stage animator. There is no pulse under reduced motion.
- **Filtering:** containers outside `category` or not matching `search` (case-insensitive on name) dim to 25% opacity; matching ones get a white emissive bump.
- **Labels and picking:**
  - Focusable overlay buttons (`.shelf-label`), one per container, give the keyboard path and the tooltip (name, `onHand unit`, reorder level, avg cost via `MoneyPipe`, and "No reorder level set" when `noReorderLevel`).
  - A click or Enter on a label, or a raycast click on a mesh, emits `pick`.
- **Camera:** pan with drag on the x axis only, clamped to the shelf extent; the camera starts framing the first shelf.
- **Branch changes:** when `stock` changes (the shell's branch switch reloads it), the instanced meshes rebuild through an `effect`.
- **Teardown:** `ngOnDestroy` calls `stage.destroy()`.

- [ ] **Step 1: Write failing specs.**
  - **`stock-shelves.spec.ts`**, with `hasWebGL` mocked `false`:
    - the unavailable note renders;
    - with `hasWebGL` mocked `true` and `import('three')` never reached (mock the module `'three'` with a minimal object or stub `ThreeStage`), the `shelves()` signal groups the given rows;
    - `category.set('Dairy')` plus `search.set('mil')` leaves the label buttons for non-matching containers with `aria-disabled="true"`;
    - a click on a label emits `pick` with that ingredientId.
  - **`stock.spec.ts`** additions:
    - switching to 3D writes `erpcafe.stockView = '3d'`;
    - a remembered `'3d'` restores the 3D view;
    - a `pick` event opens the adjust drawer with that ingredient selected (`app-modal` present and `action.ingredientId` equal to the id).
- [ ] **Step 2:** `npm test`. Expected: FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** `npm test`, `npm run lint` and `npx ng build` exit 0, with three still only in a lazy chunk. Check it in a browser: category filter, search, Tab through the labels, Enter opens adjust, and a branch switch rebuilds the view.
- [ ] **Step 5: Commit.**

```bash
git add frontend/src/app/features/inventory/stock-shelves.ts frontend/src/app/features/inventory/stock-shelves.spec.ts frontend/src/app/features/inventory/stock.ts frontend/src/app/features/inventory/stock.spec.ts
git commit -m "[Add] 3D stock shelves"
```

### Task 17: Remove the café layer and final checks

**Files:**
- Modify: `frontend/src/styles.scss`. Delete the whole "lively: physical materials" section and everything after it, the legacy alias block from Task 1, and any remaining `.script` rule. Move any still-used rule into the screen that uses it first; grep before deleting.
- Delete: `frontend/src/app/shared/steam.ts` and its imports.
- Modify: `shared/icon.ts`. Remove `coffee`, `cup`, `steam`, `bean` and `leaf` from the registry only if a repo-wide grep under `frontend/src` shows no remaining use; otherwise leave them.

- [ ] **Step 1: Prove nothing still uses the café layer:**

```bash
cd frontend
grep -rnE "niche|shelf-board|contact-shadow|paper-card|dusty|thermal|printer-slot|counter-lip|print-in|stamp|grain|wood-counter|swing-tag|brewing|cup-steamed|leader-row|mini-coin|name-cell|obj-btn|obj-del|reveal-zone|app-steam|Dancing|font-script|class=\"script" src/app src/index.html
grep -rnE "var\(--(paper|wood|brand|azure|gold|nav-|material|violet|blue|hairline|ease-spring|radius-xl|font-script|ring|line|ink)\b|var\(--surface\)|var\(--shadow-(sm|md|lg)\)" src
```

Expected: no matches, except inside the legacy block of `styles.scss` that this task deletes. Fix any other hit in its owning screen before continuing.

- [ ] **Step 2: Delete** the lively section, the legacy aliases and `steam.ts`. Run `npm test`, `npm run lint` and `npx ng build`, all exit 0. Re-run Step 1's greps: no matches anywhere.
- [ ] **Step 3: Contrast check.**
  - Write a throwaway Python or Node script in the scratchpad, **not** in the repo, that parses the two token sets from `styles.scss`. It checks `--text`, `--text-soft`, `--text-muted`, `--accent`, `--accent-text`, `--green`, `--amber` and `--red` against `--bg` and `--surface-1/2/3` (≥ 4.5), white against `--accent-fill` (≥ 4.5), each status colour against its `-soft` tint (≥ 4.5), and `--border-input` and `--accent` against `--surface-1` (≥ 3).
  - Paste its output in the report. Every pair must pass.
- [ ] **Step 4: Bundle check.** From the `npx ng build` output, record the initial total (must be < 600 kB) and confirm that `three` appears only in a lazy chunk.
- [ ] **Step 5: Accessibility pass** with `npx ng serve`:
  - Tab through the shell, the palette, one drawer, the POS, the dashboard towers and the stock shelves in both themes, at 1440px and 400px.
  - Turn on reduced motion (OS setting, or emulation in DevTools) and confirm that nothing orbits or pulses.
  - Record what you checked.
- [ ] **Step 6: Commit.**

```bash
git add frontend/src/styles.scss frontend/src/app/shared/icon.ts
git rm frontend/src/app/shared/steam.ts
git commit -m "[Remove] Café theme layer"
```
