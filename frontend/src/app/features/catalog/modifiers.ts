import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { CatalogService, InventoryService } from '../../core/api.services';
import { AuthService } from '../../core/auth.service';
import { ToastService } from '../../core/toast.service';
import { ConfirmService } from '../../core/confirm.service';
import { ModalComponent } from '../../shared/modal';
import { MoneyPipe } from '../../core/util';
import { ConsumptionLine, IngredientResponse, ModifierGroupResponse } from '../../core/models';

@Component({
  selector: 'app-modifiers',
  standalone: true,
  imports: [FormsModule, ModalComponent, MoneyPipe],
  template: `
    <div class="page">
      <div class="page-head">
        <div><h1>Modifiers</h1><div class="sub">Option groups (size, milk, extras) attachable to products.</div></div>
        @if (auth.can('catalog:write')) { <button class="btn btn-primary" (click)="openNewGroup()">＋ New group</button> }
      </div>

      @if (loading()) { <div class="card"><div class="loading-block"><span class="spinner"></span> Loading…</div></div> }
      @else {
        <div class="mod-grid">
          @for (g of groups(); track g.id) {
            <div class="card">
              <div class="card-head">
                <div>
                  <h3>{{ g.name }}</h3>
                  <div class="soft" style="font-size:12px">
                    select {{ g.minSelect }}–{{ g.maxSelect }}
                    @if (g.required) { <span class="badge badge-amber" style="margin-left:.3rem">required</span> }
                  </div>
                </div>
                @if (auth.can('catalog:write')) {
                  <div class="row-actions">
                    <button class="btn btn-sm btn-outline" (click)="openAddModifier(g)">＋ Option</button>
                    <button class="btn btn-sm btn-ghost" (click)="removeGroup(g)">🗑</button>
                  </div>
                }
              </div>
              <div class="card-pad">
                @for (m of g.modifiers; track m.id) {
                  <div class="mod-row">
                    <span>{{ m.name }}</span>
                    <span class="spacer"></span>
                    <span class="soft mono">{{ m.priceDelta.amount === '0' || m.priceDelta.amount === '0.0000' ? 'free' : ('+' + (m.priceDelta | money)) }}</span>
                  </div>
                } @empty { <div class="muted" style="font-size:12.5px">No options yet</div> }
              </div>
            </div>
          } @empty { <div class="card"><div class="empty"><div class="big">⚙</div>No modifier groups yet</div></div> }
        </div>
      }
    </div>

    @if (newGroup()) {
      <app-modal title="New modifier group" (close)="newGroup.set(false)">
        <div class="field"><label>Name</label><input class="input" [(ngModel)]="ng.name" placeholder="Size" /></div>
        <div class="two-col">
          <div class="field"><label>Min select</label><input class="input" type="number" min="0" [(ngModel)]="ng.minSelect" /></div>
          <div class="field"><label>Max select</label><input class="input" type="number" min="0" [(ngModel)]="ng.maxSelect" /></div>
        </div>
        <div class="hint">Required is derived on the server when min select ≥ 1.</div>
        <div footer>
          <button class="btn btn-outline" (click)="newGroup.set(false)">Cancel</button>
          <button class="btn btn-primary" (click)="createGroup()" [disabled]="saving()">@if (saving()) { <span class="spinner"></span> } Create</button>
        </div>
      </app-modal>
    }

    @if (addTo(); as g) {
      <app-modal [title]="'Add option to ' + g.name" [width]="600" (close)="addTo.set(null)">
        <div class="two-col">
          <div class="field"><label>Name</label><input class="input" [(ngModel)]="nm.name" placeholder="Large" /></div>
          <div class="field"><label>Price delta</label><input class="input" type="number" min="0" [(ngModel)]="nm.priceDelta" placeholder="0" /></div>
        </div>
        <div class="field"><label>Display order</label><input class="input" type="number" [(ngModel)]="nm.displayOrder" /></div>

        <div class="pg-title">Recipe deltas <span class="muted" style="font-weight:400;text-transform:none">— extra ingredients this option consumes (optional)</span></div>
        @for (d of deltas(); track $index) {
          <div class="delta-row">
            <select class="select" [(ngModel)]="d.ingredientId">
              <option value="" disabled>Ingredient…</option>
              @for (ing of ingredients(); track ing.id) { <option [value]="ing.id">{{ ing.name }}</option> }
            </select>
            <input class="input" type="number" [(ngModel)]="d.quantity" placeholder="Qty" style="max-width:100px" />
            <input class="input" [(ngModel)]="d.unit" placeholder="unit" style="max-width:90px" />
            <button class="btn btn-icon btn-ghost" (click)="removeDelta($index)">✕</button>
          </div>
        }
        @if (ingredients().length) {
          <button class="btn btn-sm btn-outline" (click)="addDelta()">＋ Add ingredient</button>
        } @else { <div class="muted" style="font-size:12px">Ingredient list unavailable (needs inventory:read).</div> }

        <div footer>
          <button class="btn btn-outline" (click)="addTo.set(null)">Cancel</button>
          <button class="btn btn-primary" (click)="addModifier()" [disabled]="saving()">@if (saving()) { <span class="spinner"></span> } Add option</button>
        </div>
      </app-modal>
    }
  `,
  styles: [`
    .mod-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(300px, 1fr)); gap: 1.1rem; align-items: start; }
    .mod-row { display: flex; align-items: center; padding: .45rem 0; border-bottom: 1px solid var(--border); font-size: 13.5px; }
    .mod-row:last-child { border-bottom: none; }
    .two-col { display: grid; grid-template-columns: 1fr 1fr; gap: 0 1rem; }
    .pg-title { font-size: 11.5px; text-transform: uppercase; letter-spacing: .04em; color: var(--text-muted); font-weight: 700; margin: 1rem 0 .7rem; }
    .delta-row { display: flex; gap: .5rem; margin-bottom: .5rem; align-items: center; }
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

  ng = { name: '', minSelect: 0, maxSelect: 1 };
  nm = { name: '', priceDelta: '0', displayOrder: 0 };

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
    this.api.createModifierGroup(this.ng).subscribe({
      next: () => { this.saving.set(false); this.newGroup.set(false); this.toast.success('Group created'); this.load(); },
      error: () => this.saving.set(false),
    });
  }

  openAddModifier(g: ModifierGroupResponse) {
    this.nm = { name: '', priceDelta: '0', displayOrder: g.modifiers.length };
    this.deltas.set([]);
    this.addTo.set(g);
  }
  addDelta() { this.deltas.update((d) => [...d, { ingredientId: '', quantity: '', unit: '' }]); }
  removeDelta(i: number) { this.deltas.update((d) => d.filter((_, idx) => idx !== i)); }

  addModifier() {
    const g = this.addTo();
    if (!g || !this.nm.name) { this.toast.error('Name is required'); return; }
    const recipeDeltas = this.deltas().filter((d) => d.ingredientId && d.quantity && d.unit);
    this.saving.set(true);
    this.api.addModifier(g.id, {
      name: this.nm.name,
      priceDelta: String(this.nm.priceDelta || '0'),
      displayOrder: this.nm.displayOrder,
      recipeDeltas: recipeDeltas.length ? recipeDeltas : undefined,
    }).subscribe({
      next: () => { this.saving.set(false); this.addTo.set(null); this.toast.success('Option added'); this.load(); },
      error: () => this.saving.set(false),
    });
  }

  async removeGroup(g: ModifierGroupResponse) {
    const ok = await this.confirm.ask({ title: `Delete ${g.name}?`, message: 'Removes the group and its options.', danger: true, confirmText: 'Delete' });
    if (!ok) return;
    this.api.deleteModifierGroup(g.id).subscribe({ next: () => { this.toast.success('Deleted'); this.load(); } });
  }
}
