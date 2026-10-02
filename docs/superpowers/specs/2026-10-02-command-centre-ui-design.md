# Command Centre UI — Design

**Date:** 2026-10-02 · **Branch:** `feat/command-centre-ui` (from `fix/code-review`)
**Scope:** the Angular frontend in `frontend/` only. No backend change.

## 1. Intent

ERP-Cafe is becoming a general multi-tenant business ERP; a café is one kind of business it serves.
The current frontend theme ("Warm Café Ops": espresso and cream, paper, wood, steam) presents it as
a café tool. This rebuild gives every screen one futuristic "command centre" look that suits any
business, and adds two Three.js views where 3D shows something a table cannot.

Decided with the owner:

| Question | Decision |
|---|---|
| What the look is for | General business ERP: no café styling anywhere, POS included |
| Overall style | Command centre: dark by default, light mode too, ⌘K palette |
| Accent | Electric indigo |
| Where 3D goes | Dashboard branch towers; Stock 3D shelf view |
| Approach | New theme + shell, every screen reworked onto shared building blocks; services, routes and logic kept |

Success means: every screen uses the new system in both themes, no café styling is left, every
existing behaviour and test still holds, both themes meet WCAG 2.2 AA, and the two 3D views load
only where they are used.

## 2. Visual system

All tokens live in `frontend/src/styles.scss` on `:root`. The light set applies under
`[data-theme="light"]`, and under `prefers-color-scheme: light` when the theme setting is System.

### Colour

| Token | Dark (default) | Light | Use |
|---|---|---|---|
| `--bg` | `#0B0D14` | `#F6F7FB` | page |
| `--surface-1` | `#12151F` | `#FFFFFF` | panels |
| `--surface-2` | `#181C29` | `#F0F2F8` | raised / hover |
| `--surface-3` | `#20253A` | `#E7EAF3` | selected / wells |
| `--border` | `#262B3D` | `#E3E6EF` | dividers |
| `--border-input` | `#636B8E` | `#848BA4` | input outlines (≥3:1) |
| `--text` | `#E6E8F2` | `#11131C` | body |
| `--text-soft` | `#A3A9C2` | `#4A5068` | secondary |
| `--text-muted` | `#8A91AE` | `#5F6680` | hints, captions |
| `--accent` | `#7C83FF` | `#4F56E8` | links, active nav, focus ring, 3D glow |
| `--accent-fill` | `#5B62F0` | `#4F56E8` | primary button fill (white text) |
| `--green` | `#34D399` | `#047857` | success status |
| `--amber` | `#FBBF24` | `#9A4706` | warning status |
| `--red` | `#F87171` | `#B91C1C` | error / danger status |

Every text token above passes 4.5:1 on every surface of its theme. White on `--accent-fill` passes
in both themes, and the input borders and focus ring pass 3:1. Each status colour has a `-soft`
tint for badge backgrounds, and its text colour keeps 4.5:1 on that tint.

Red, amber and green mean status only, and always come with a text label.

### Depth, glass, motion

- **Background:** a faint indigo radial glow in one corner of the page background.
- **Panels:** a 1px top highlight on each panel; elevation comes from surface steps, not heavy
  shadows.
- **Glass:** frosted glass (`backdrop-filter`) only on things that float: the top bar once
  scrolled, dialogs, drawers, the ⌘K palette and toasts. It falls back to solid under
  `prefers-reduced-transparency`.
- **Radii:** 12px panels, 8px controls, 16px dialogs.
- **Motion:** 150ms ease-out micro-feedback on saves and toggles. Nothing animates under
  `prefers-reduced-motion`.

### Type

The fonts come from Google Fonts via `<link>` in `index.html`. All three include the Vietnamese
subset.

- **Inter:** UI text.
- **Space Grotesk:** headings and dashboard key numbers.
- **JetBrains Mono:** money and quantities, with `tabular-nums`.

### Settings remembered per browser

- **Theme:** System / Dark / Light.
- **Density:** Comfortable / Compact, applied to tables and lists.
- **Sidebar:** expanded or rail.
- **Stock view:** Table / 3D.

All four are stored in `localStorage` behind try/catch, with the defaults applying when storage is
unavailable.

### Removed

- The espresso, cream, azure and gold tokens.
- `--paper*` and `--wood-*`.
- Dancing Script.
- Every material class: `.niche .shelf-board .paper-card .thermal* .stamp* .grain .wood-counter
  .swing-tag .cup-steamed .brewing .dusty .mini-coin` and the rest of the "lively" section.
- `<app-steam>`.
- The café icons in navigation; Products uses a box.

## 3. App shell

### Sidebar

- 248px wide, collapsing to a 64px icon rail that shows tooltips on hover and focus.
- It keeps today's seven permission-filtered sections.
- The active item gets an indigo highlight and a glowing left bar.
- It has a neutral abstract logo mark; the product name stays "ERP-Cafe".
- On `/pos` it collapses to the rail automatically.

### Top bar

- Sticky, and frosted once the page scrolls.
- **Left:** page title and breadcrumb.
- **Centre:** the "Search or jump to… ⌘K" trigger.
- **Right:** the branch switcher (name and code), the theme toggle, and the user menu (email,
  roles, the density switch, Sign out).

### ⌘K command palette (Ctrl+K off macOS)

- **Contents:** jump to any screen the user may open, with recents first. Actions: New order, New
  purchase order, Adjust stock, Switch branch, Toggle theme, Sign out.
- **Keyboard:** arrows move, Enter runs, Esc closes. Focus stays in the input through
  `aria-activedescendant`.
- **Layout:** groups never reorder between keystrokes, and there is a no-results state.
- **Not in scope:** record search.

### Narrow screens (under 900px)

The sidebar becomes a slide-out drawer, and the palette trigger becomes an icon.

### Accessibility kept

- The skip link and `<main id="main-content">`.
- `aria-current` on nav.
- A visible focus ring everywhere.
- Menus and the palette fully keyboard- and screen-reader-operable.

## 4. Building blocks and screens

### Shared building blocks (`frontend/src/app/shared/` plus global classes)

- **Panel:** a titled panel with an actions slot.
- **Key-number tile:** label, value in Space Grotesk, change against the previous period, and a
  7-day sparkline (SVG).
- **Data table:**
  - a sticky header and hairline rows with an accent-tinted hover;
  - `.num` cells right-aligned in mono;
  - built-in loading (skeleton), empty and error states;
  - density-aware.
- **Buttons:** primary, secondary, ghost, danger and icon.
- **Inputs, selects and textareas:** accent focus glow.
- **Status badge:** dot plus label, the existing `StatusBadgeComponent` restyled.
- **Side drawer:** `app-modal` gains a `variant="drawer"` input (right side, full height). Create and
  edit forms use it. Confirmations stay centred dialogs. The focus trap, Esc and focus restore stay
  as they are.
- **Toasts, tabs and segmented controls, skeletons, empty states.**
- **Charts:** they stay hand-drawn SVG, recoloured. No chart library is added.

### Screens

- **Dashboard:** key-number tiles (net sales, orders, average order, gross margin), the branch towers
  (§5), low stock, recent orders, and sales by day.
- **POS:**
  - category tabs, large touch product tiles, and a clean order panel;
  - the payment dialog; a successful payment shows a glowing check.
  - Touch targets are at least 44px.
  - The counter, paper cards, receipt cart, steam and stamp are removed.
- **Orders, purchase orders, products, categories, ingredients, suppliers, items, team, branches,
  users, roles:**
  - each is a table, with details and edits in the drawer;
  - initial avatars replace the identity coins;
  - the modifiers accordion stays, restyled.
- **Stock:** the table plus a Table / 3D switch (§5).
- **Shifts and reports:** the same content, restyled.
- **Login, forgot password and reset password:** a glass card on the indigo-glow background. They
  have no 3D.

### Kept unchanged

- Every form field, validation, permission check, route and API call.
- The accessibility conventions:
  - an `id` and `<label for>` on every control;
  - table captions and `scope`;
  - keyboard-openable rows;
  - `confirm.ask({danger:true})` before destructive actions;
  - `aria-live` toasts.

## 5. Three.js views

### Dashboard: branch towers

- **Data:** `GET /reports/outlets` for Today, 7 days or 30 days. The panel is hidden without
  `report:read`.
- **Layout:** one tower per branch on a faint grid floor; the branch code floats above each tower.
- **Height:** `netSales / max(netSales)`, with a minimum height.
- **Colour:**
  - indigo emissive by default;
  - the top branch gets a brighter cap;
  - zero sales gives a dim flat slab;
  - a net margin below 0 gives a red base ring.
- **Interaction:**
  - slow auto-orbit that stops on interaction;
  - bounded drag-to-rotate;
  - hover or focus shows a tooltip with name, net sales (VND), orders, margin and a "View report"
    link.
- **Over 24 branches:** the top 24 plus an "others" total.

### Stock: 3D shelves

- **Data:** `GET /stock` for the active branch, plus the ingredients (name, category).
- **Layout:** one shelf per category, and one glass container per ingredient.
- **Fill:** `onHand / max(3 × reorderLevel, onHand)`. A zero reorder level shows as full, with a
  note.
- **Colour:** indigo when fine; amber when `belowReorderLevel`, with a gentle pulse; red when on
  hand ≤ 0.
- **Interaction:**
  - hover or focus shows a tooltip with name, on hand with unit, reorder level and average cost;
  - click opens the same adjust drawer the table row opens;
  - category chips filter the shelves, and search highlights matches.
- **Rendering:** containers are drawn with instanced meshes.

### Shared mechanics

- **Package and loading:**
  - `three` is added as a pinned dependency;
  - it is imported only through dynamic `import()` inside the two views, so it lands in a lazy
    chunk and never in the initial bundle.
- **`ThreeStage` helper:**
  - sizes the canvas with a ResizeObserver and caps the pixel ratio at 2;
  - renders on demand (only while animating or after a change);
  - pauses when off-screen (IntersectionObserver) or when the tab is hidden;
  - disposes geometries, materials and the renderer on destroy.
- **Theme:** scene colours read from the CSS tokens and update when the theme changes.
- **Fallbacks:**
  - without WebGL, the table shows with a short note;
  - under `prefers-reduced-motion`, there is no orbit and no pulse.
- **Accessibility:**
  - the canvas is `aria-hidden`, and the same numbers are always one click away as a real table;
  - towers and containers can be reached with Tab through an overlay of focusable labels.
- **Testing:** the data-to-scene mapping (heights, fill levels, colours, the top-24 cut) is pure
  functions with Vitest tests, so it needs no WebGL.

## 6. Delivery

Each step leaves the app building and every test passing:

1. **Foundations:** tokens, fonts, the theme and density services, and removal of the café styles.
2. **Shell:** sidebar and rail, top bar, user menu, branch switcher and ⌘K palette.
3. **Building blocks:** panels, tiles, tables, controls, badges, drawer mode, toasts, skeletons and
   empty states.
4. **Screens, in groups:** auth → dashboard → POS and orders → catalog → inventory → staff →
   reports → organization.
5. **3D:** `ThreeStage` and the branch towers, then the stock shelves.
6. **Final pass:**
   - contrast check of both themes;
   - keyboard and screen-reader pass;
   - check at 400px width;
   - bundle check: the initial bundle stays under the existing 600 kB warning budget, with `three`
     in its own chunk.

### Testing

- **Existing tests:** all existing frontend specs keep passing. A selector may follow new markup,
  but no assertion is weakened.
- **New specs:**
  - the theme and density services;
  - the palette (keyboard, permission filtering, no results);
  - drawer mode (focus trap, Esc);
  - the 3D mapping functions;
  - the no-WebGL fallback and the Table / 3D switch.
- **Every commit:** `npm test`, `npm run lint` (0 errors) and `ng build` all pass.
- **Browser check:** both themes are checked in a browser via `ng serve`.

### Done when

- A search of `frontend/src` finds none of the removed tokens, classes, `app-steam` or Dancing
  Script.
- Every screen uses the building blocks.
- Both themes pass the contrast check.
- `three` loads only on the dashboard and stock screens.

## 7. Not in scope

- Record search in ⌘K.
- Renaming the product.
- Any backend change.
- A 3D login backdrop.
- An ambient 3D background on every screen.
- New features or changes to business behaviour.
