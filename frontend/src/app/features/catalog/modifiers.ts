import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { CatalogService, InventoryService } from '../../core/api.services';
import { AuthService } from '../../core/auth.service';
import { ToastService } from '../../core/toast.service';
import { afterSave } from '../../core/save-handler';
import { ConfirmService } from '../../core/confirm.service';
import { ModalComponent } from '../../shared/modal';
import { IconComponent } from '../../shared/icon';
import { unitsFor } from '../../core/units';
import { MoneyPipe } from '../../core/util';
import { ConsumptionLine, IngredientResponse, ModifierGroupResponse, ModifierResponse } from '../../core/models';

@Component({
  selector: 'app-modifiers',
  standalone: true,
  imports: [FormsModule, ModalComponent, MoneyPipe, IconComponent],
  template: `
    <div class="page">
      <div class="page-head">
        <div><h1>Modifiers</h1><div class="sub">Option groups (size, milk, extras) attachable to products.</div></div>
        @if (auth.can('catalog:write')) { <button class="btn btn-primary" (click)="openNewGroup()"><app-icon name="plus" [size]="16" /> New group</button> }
      </div>

      @if (loading()) { <div class="card"><div class="loading-block"><span class="spinner"></span> Loading…</div></div> }
      @else {
        <div class="mod-list">
          @for (g of groups(); track g.id) {
            <section class="mod-group card">
              <h2 class="mg-head">
                <button class="mg-toggle" (click)="toggle(g.id)"
                        [attr.aria-expanded]="isOpen(g.id)" [attr.aria-controls]="'grp-' + g.id">
                  <span class="mg-chev" [class.open]="isOpen(g.id)" aria-hidden="true"><app-icon name="chevron" [size]="18" /></span>
                  <span class="mg-name">{{ g.name }}</span>
                  <span class="badge badge-gray">{{ selectionLabel(g) }}</span>
                  @if (g.required) { <span class="badge badge-amber">required at POS</span> }
                  <span class="mg-count">{{ g.modifiers.length }} {{ g.modifiers.length === 1 ? 'option' : 'options' }}</span>
                </button>
                @if (auth.can('catalog:write')) {
                  <span class="mg-actions">
                    <button class="btn btn-sm btn-tinted" (click)="openAddModifier(g)"><app-icon name="plus" [size]="15" /> Option</button>
                    <button class="btn btn-sm btn-ghost btn-icon" (click)="openEditGroup(g)" [attr.aria-label]="'Edit group ' + g.name"><app-icon name="edit" [size]="16" /></button>
                    <button class="btn btn-sm btn-ghost btn-icon" (click)="removeGroup(g)" [attr.aria-label]="'Delete group ' + g.name"><app-icon name="trash" [size]="16" /></button>
                  </span>
                }
              </h2>
              @if (isOpen(g.id)) {
                <ul class="opt-list" [id]="'grp-' + g.id">
                  @for (m of g.modifiers; track m.id) {
                    <li class="opt-row">
                      <span class="opt-name">{{ m.name }}</span>
                      <span class="spacer"></span>
                      <span class="opt-price num" [class.free]="isFree(m.priceDelta.amount)">
                        {{ isFree(m.priceDelta.amount) ? 'Free' : ('+' + (m.priceDelta | money)) }}
                      </span>
                      @if (!m.active) { <span class="badge badge-gray">inactive</span> }
                      @if (auth.can('catalog:write')) {
                        <span class="opt-actions">
                          <button class="btn btn-sm btn-ghost btn-icon" (click)="openEditModifier(g, m)" [attr.aria-label]="'Edit option ' + m.name"><app-icon name="edit" [size]="15" /></button>
                          <button class="btn btn-sm btn-ghost btn-icon" (click)="removeModifier(g, m)" [attr.aria-label]="'Remove option ' + m.name"><app-icon name="trash" [size]="15" /></button>
                        </span>
                      }
                    </li>
                  } @empty {
                    <li class="opt-empty">
                      <span class="muted">No options yet</span>
                      @if (auth.can('catalog:write')) { <button class="btn btn-sm btn-outline" (click)="openAddModifier(g)"><app-icon name="plus" [size]="15" /> Add the first option</button> }
                    </li>
                  }
                </ul>
              }
            </section>
          } @empty { <div class="card"><div class="empty"><div class="big"><app-icon name="sliders" [size]="30" /></div>No modifier groups yet</div></div> }
        </div>
      }
    </div>

    @if (newGroup()) {
      <app-modal variant="drawer" title="New modifier group" (close)="newGroup.set(false)">
        <div class="field"><label for="mod-group-name">Name <span class="req" aria-hidden="true">*</span></label><input id="mod-group-name" class="input" [(ngModel)]="ng.name" placeholder="Size" required /></div>
        <div class="two-col">
          <div class="field">
            <label for="mod-min">Min select</label>
            <input id="mod-min" class="input" type="number" min="0" [(ngModel)]="ng.minSelect" />
            <div class="hint">0 = optional. 1 or more makes this group <b>required</b> — the POS won't accept an order until the cashier picks.</div>
          </div>
          <div class="field">
            <label for="mod-max">Max select</label>
            <input id="mod-max" class="input" type="number" min="0" [(ngModel)]="ng.maxSelect" />
            <div class="hint">Most options one line may have. Min 1 + max 1 = "choose exactly one" (e.g. Size).</div>
          </div>
        </div>
        <div footer>
          <button class="btn btn-outline" (click)="newGroup.set(false)">Cancel</button>
          <button class="btn btn-primary" (click)="createGroup()" [disabled]="saving()">@if (saving()) { <span class="spinner"></span> } Create</button>
        </div>
      </app-modal>
    }

    @if (editGroup(); as g) {
      <app-modal variant="drawer" [title]="'Edit ' + g.name" (close)="editGroup.set(null)">
        <div class="field"><label for="mod-edit-group-name">Name <span class="req" aria-hidden="true">*</span></label><input id="mod-edit-group-name" class="input" [(ngModel)]="eg.name" required /></div>
        <div class="two-col">
          <div class="field"><label for="mod-edit-min">Min select</label><input id="mod-edit-min" class="input" type="number" min="0" [(ngModel)]="eg.minSelect" /></div>
          <div class="field"><label for="mod-edit-max">Max select</label><input id="mod-edit-max" class="input" type="number" min="0" [(ngModel)]="eg.maxSelect" /></div>
        </div>
        <div footer>
          <button class="btn btn-outline" (click)="editGroup.set(null)">Cancel</button>
          <button class="btn btn-primary" (click)="saveGroup()" [disabled]="saving()">@if (saving()) { <span class="spinner"></span> } Save</button>
        </div>
      </app-modal>
    }

    @if (editMod(); as e) {
      <app-modal variant="drawer" [title]="'Edit option ' + e.modifier.name" (close)="editMod.set(null)">
        <div class="two-col">
          <div class="field"><label for="mod-edit-name">Name <span class="req" aria-hidden="true">*</span></label><input id="mod-edit-name" class="input" [(ngModel)]="em.name" required /></div>
          <div class="field"><label for="mod-edit-delta">Price delta</label><input id="mod-edit-delta" class="input" type="number" min="0" [(ngModel)]="em.priceDelta" /></div>
        </div>
        <div class="two-col">
          <div class="field"><label for="mod-edit-order">Display order</label><input id="mod-edit-order" class="input" type="number" [(ngModel)]="em.displayOrder" /></div>
          <div class="field"><label for="mod-edit-active">Active</label><input id="mod-edit-active" type="checkbox" [(ngModel)]="em.active" /></div>
        </div>
        <div footer>
          <button class="btn btn-outline" (click)="editMod.set(null)">Cancel</button>
          <button class="btn btn-primary" (click)="saveModifier()" [disabled]="saving()">@if (saving()) { <span class="spinner"></span> } Save</button>
        </div>
      </app-modal>
    }

    @if (addTo(); as g) {
      <app-modal variant="drawer" [title]="'Add option to ' + g.name" [width]="600" (close)="addTo.set(null)">
        <div class="two-col">
          <div class="field"><label for="mod-opt-name">Name <span class="req" aria-hidden="true">*</span></label><input id="mod-opt-name" class="input" [(ngModel)]="nm.name" placeholder="Large" required /></div>
          <div class="field"><label for="mod-opt-delta">Price delta</label><input id="mod-opt-delta" class="input" type="number" min="0" [(ngModel)]="nm.priceDelta" placeholder="0" /></div>
        </div>
        <div class="field"><label for="mod-opt-order">Display order</label><input id="mod-opt-order" class="input" type="number" [(ngModel)]="nm.displayOrder" /></div>

        <div class="pg-title">Recipe deltas <span class="muted pg-note">— extra ingredients this option consumes (optional)</span></div>
        @for (d of deltas(); track $index) {
          <div class="delta-row">
            <select class="select" [(ngModel)]="d.ingredientId" (ngModelChange)="onIngredientPicked(d)" aria-label="Ingredient">
              <option value="" disabled>Ingredient…</option>
              @for (ing of ingredients(); track ing.id) { <option [value]="ing.id">{{ ing.name }}</option> }
            </select>
            <input class="input w-qty" type="number" [(ngModel)]="d.quantity" placeholder="Qty" aria-label="Quantity" />
            <select class="select w-unit" [(ngModel)]="d.unit" aria-label="Unit">
              <option value="" disabled>Unit…</option>
              @for (u of unitOptions(d); track u.value) { <option [value]="u.value">{{ u.value }}</option> }
            </select>
            <button class="btn btn-icon btn-ghost" (click)="removeDelta($index)" aria-label="Remove ingredient"><app-icon name="close" [size]="16" /></button>
          </div>
        }
        @if (ingredients().length) {
          <button class="btn btn-sm btn-outline" (click)="addDelta()"><app-icon name="plus" [size]="15" /> Add ingredient</button>
        } @else { <div class="muted note-sm">Ingredient list unavailable (needs inventory:read).</div> }
        @if (deltaProblem(); as problem) { <div class="note-sm field-error" role="alert">{{ problem }}</div> }

        <div footer>
          <button class="btn btn-outline" (click)="addTo.set(null)">Cancel</button>
          <button class="btn btn-primary" (click)="addModifier()" [disabled]="saving() || !!deltaProblem()">@if (saving()) { <span class="spinner"></span> } Add option</button>
        </div>
      </app-modal>
    }
  `,
  styles: [`
    .mod-list { display: flex; flex-direction: column; gap: .9rem; max-width: 780px; }
    .mod-group { overflow: hidden; }
    .mg-head { display: flex; align-items: center; gap: .5rem; padding: .4rem .6rem .4rem .3rem; margin: 0;
      font-weight: inherit; border-bottom: 1px solid var(--border); }
    .mg-toggle { flex: 1; min-width: 0; display: flex; align-items: center; gap: .6rem; background: none; border: none;
      padding: .5rem .4rem; font: inherit; cursor: pointer; color: var(--text); text-align: left; border-radius: var(--radius-sm); min-height: 44px; }
    .mg-toggle:hover { background: var(--surface-2); }
    .mg-chev { display: inline-flex; color: var(--text-muted); transition: transform var(--dur-2) var(--ease-out); }
    .mg-chev.open { transform: rotate(90deg); }
    .mg-name { font-family: var(--font-display); font-size: 15px; font-weight: 600; }
    .mg-count { color: var(--text-muted); font-size: 13px; margin-left: auto; padding-left: .5rem; }
    .mg-actions { display: flex; align-items: center; gap: .35rem; flex-shrink: 0; padding-right: .3rem; }
    .opt-list { list-style: none; margin: 0; padding: 0 .5rem .4rem; }
    .opt-row { display: flex; align-items: center; gap: .6rem; padding: .7rem .8rem; border-bottom: 1px solid var(--border); font-size: 14px; }
    .opt-row:last-child { border-bottom: none; }
    .opt-name { font-weight: 500; }
    .opt-price { color: var(--accent-text); font-weight: 600; }
    .opt-price.free { color: var(--text-muted); font-weight: 500; }
    .opt-actions { display: inline-flex; gap: .15rem; margin-left: .5rem; }
    .opt-empty { display: flex; align-items: center; justify-content: space-between; gap: 1rem; flex-wrap: wrap; padding: 1rem .8rem 1.1rem; }
    .pg-title { font-size: 11.5px; text-transform: uppercase; letter-spacing: .04em; color: var(--text-muted); font-weight: 700; margin: 1.2rem 0 .7rem; }
    .pg-note { font-weight: 400; text-transform: none; }
    .delta-row { display: flex; gap: .5rem; margin-bottom: .5rem; align-items: center; }
    .w-qty { max-width: 100px; }
    .w-unit { max-width: 90px; }
    .note-sm { font-size: 12px; }
    .field-error { margin-top: .5rem; color: var(--red); }
    @media (max-width: 620px) { .mg-count { display: none; } }
  `],
})
export class ModifiersComponent {
  auth = inject(AuthService);
  private api = inject(CatalogService);
  private inv = inject(InventoryService);
  private toast = inject(ToastService);
  private confirm = inject(ConfirmService);

  groups = signal<ModifierGroupResponse[]>([]);
  ingredients = signal<IngredientResponse[]>([]);
  loading = signal(true);
  saving = signal(false);
  newGroup = signal(false);
  addTo = signal<ModifierGroupResponse | null>(null);
  deltas = signal<ConsumptionLine[]>([]);
  editGroup = signal<ModifierGroupResponse | null>(null);
  editMod = signal<{ group: ModifierGroupResponse; modifier: ModifierResponse } | null>(null);
  private collapsed = signal<Set<string>>(new Set());

  ng = { name: '', minSelect: 0, maxSelect: 1 };
  eg = { name: '', minSelect: 0, maxSelect: 1 };
  em = { name: '', priceDelta: '0', displayOrder: 0, active: true };
  nm = { name: '', priceDelta: '0', displayOrder: 0 };

  isOpen = (id: string) => !this.collapsed().has(id);
  toggle(id: string) {
    const s = new Set(this.collapsed());
    s.has(id) ? s.delete(id) : s.add(id);
    this.collapsed.set(s);
  }
  isFree = (amount: string) => amount === '0' || amount === '0.0000' || Number(amount) === 0;

  /** Plain-language selection rule shown on the group header. */
  selectionLabel(g: { minSelect: number; maxSelect: number }): string {
    if (g.minSelect <= 0) return g.maxSelect > 0 ? `optional · up to ${g.maxSelect}` : 'optional';
    if (g.minSelect === g.maxSelect) return `pick exactly ${g.minSelect}`;
    return `pick ${g.minSelect}–${g.maxSelect}`;
  }

  constructor() {
    this.load();
    if (this.auth.can('inventory:read')) {
      this.inv.listIngredients().subscribe({ next: (i) => this.ingredients.set(i), error: () => {} });
    }
  }

  load() {
    this.loading.set(true);
    this.api.listModifierGroups().subscribe({
      next: (g) => { this.groups.set(g); this.loading.set(false); },
      error: () => this.loading.set(false),
    });
  }

  openNewGroup() { this.ng = { name: '', minSelect: 0, maxSelect: 1 }; this.newGroup.set(true); }
  createGroup() {
    if (!this.ng.name) { this.toast.error('Name is required'); return; }
    this.saving.set(true);
    this.api.createModifierGroup(this.ng).subscribe(afterSave(this.saving, () => { this.newGroup.set(false); this.toast.success('Group created'); this.load(); }));
  }

  openAddModifier(g: ModifierGroupResponse) {
    this.nm = { name: '', priceDelta: '0', displayOrder: g.modifiers.length };
    this.deltas.set([]);
    this.addTo.set(g);
  }
  unitOptions(d: ConsumptionLine) { return unitsFor(this.ingredients().find((i) => i.id === d.ingredientId)?.baseUnit); }
  onIngredientPicked(d: ConsumptionLine) {
    const base = this.ingredients().find((i) => i.id === d.ingredientId)?.baseUnit;
    if (base) d.unit = base;
  }
  deltaProblem(): string | null {
    return this.deltas().some((d) => !d.ingredientId || !d.quantity || !d.unit)
      ? 'Every ingredient needs a quantity and a unit — fill in or remove the incomplete lines.'
      : null;
  }
  addDelta() { this.deltas.update((d) => [...d, { ingredientId: '', quantity: '', unit: '' }]); }
  removeDelta(i: number) { this.deltas.update((d) => d.filter((_, idx) => idx !== i)); }

  addModifier() {
    const g = this.addTo();
    if (!g || !this.nm.name) { this.toast.error('Name is required'); return; }
    if (this.deltaProblem()) return;
    const recipeDeltas = this.deltas().map((d) => ({ ...d, quantity: String(d.quantity) }));
    this.saving.set(true);
    this.api.addModifier(g.id, {
      name: this.nm.name,
      priceDelta: String(this.nm.priceDelta || '0'),
      displayOrder: this.nm.displayOrder,
      recipeDeltas: recipeDeltas.length ? recipeDeltas : undefined,
    }).subscribe(afterSave(this.saving, () => { this.addTo.set(null); this.toast.success('Option added'); this.load(); }));
  }

  openEditGroup(g: ModifierGroupResponse) {
    this.eg = { name: g.name, minSelect: g.minSelect, maxSelect: g.maxSelect };
    this.editGroup.set(g);
  }
  saveGroup() {
    const g = this.editGroup();
    if (!g || !this.eg.name) { this.toast.error('Name is required'); return; }
    this.saving.set(true);
    this.api.updateModifierGroup(g.id, this.eg).subscribe(afterSave(this.saving, () => { this.editGroup.set(null); this.toast.success('Group updated'); this.load(); }));
  }

  openEditModifier(group: ModifierGroupResponse, modifier: ModifierResponse) {
    this.em = { name: modifier.name, priceDelta: modifier.priceDelta.amount, displayOrder: modifier.displayOrder, active: modifier.active };
    this.editMod.set({ group, modifier });
  }
  saveModifier() {
    const e = this.editMod();
    if (!e || !this.em.name) { this.toast.error('Name is required'); return; }
    this.saving.set(true);
    // recipeDeltas omitted: the server keeps the option's existing ingredient deltas.
    this.api.updateModifier(e.group.id, e.modifier.id, { ...this.em, priceDelta: String(this.em.priceDelta || '0') }).subscribe(afterSave(this.saving, () => { this.editMod.set(null); this.toast.success('Option updated'); this.load(); }));
  }
  async removeModifier(g: ModifierGroupResponse, m: ModifierResponse) {
    const ok = await this.confirm.ask({ title: `Remove ${m.name}?`, message: 'The option can no longer be picked at the till.', danger: true, confirmText: 'Remove' });
    if (!ok) return;
    this.api.removeModifier(g.id, m.id).subscribe({ next: () => { this.toast.success('Option removed'); this.load(); } });
  }

  async removeGroup(g: ModifierGroupResponse) {
    const ok = await this.confirm.ask({ title: `Delete ${g.name}?`, message: 'Removes the group and its options.', danger: true, confirmText: 'Delete' });
    if (!ok) return;
    this.api.deleteModifierGroup(g.id).subscribe({ next: () => { this.toast.success('Deleted'); this.load(); } });
  }
}
