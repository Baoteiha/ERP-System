import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { InventoryService } from '../../core/api.services';
import { AuthService } from '../../core/auth.service';
import { ToastService } from '../../core/toast.service';
import { ConfirmService } from '../../core/confirm.service';
import { ModalComponent } from '../../shared/modal';
import { IconComponent } from '../../shared/icon';
import { IngredientRequest, IngredientResponse } from '../../core/models';

@Component({
  selector: 'app-ingredients',
  standalone: true,
  imports: [FormsModule, ModalComponent, IconComponent],
  template: `
    <div class="page">
      <div class="page-head">
        <div><h1>Ingredients</h1><div class="sub">Raw materials consumed by recipes. Company-wide master data.</div></div>
        @if (auth.can('inventory:write')) { <button class="btn btn-primary" (click)="openNew()"><app-icon name="plus" [size]="16" /> New ingredient</button> }
      </div>

      @if (loading()) { <div class="card"><div class="loading-block"><span class="spinner"></span> Loading…</div></div> }
      @else if (!items().length) {
        <div class="card"><div class="empty"><div class="big"><app-icon name="flask" [size]="30" /></div>No ingredients yet — stock your first shelf.</div></div>
      }
      @else {
        <div class="pantry niche">
          <div class="shelf-grid" role="list">
            @for (i of items(); track i.id) {
              <div class="slot" role="listitem">
                <div class="jar reveal-zone" [class.inactive]="!i.active">
                  <button type="button" class="jar-body obj-btn" [class.static]="!canWrite"
                          (click)="canWrite && openEdit(i)" [tabindex]="canWrite ? null : -1"
                          [attr.aria-label]="canWrite ? 'Edit ' + i.name : null">
                    <span class="lid" aria-hidden="true"></span>
                    <span class="glass" aria-hidden="true">
                      <span class="shine"></span>
                      <app-icon [name]="jarIcon(i.category)" [size]="30" [stroke]="1.6" />
                    </span>
                    <span class="tag-label">
                      <b class="jar-name">{{ i.name }}</b>
                      <span class="jar-meta">
                        <span class="jar-cat">{{ i.category || 'Uncategorized' }}</span>
                        <span class="jar-unit">{{ i.baseUnit }}</span>
                      </span>
                      <span class="badge dot jar-status" [class]="i.active ? 'badge-green' : 'badge-gray'">
                        {{ i.active ? 'Active' : 'Inactive' }}
                      </span>
                    </span>
                  </button>
                  @if (canWrite) {
                    <button type="button" class="btn btn-sm btn-icon obj-del jar-del" (click)="remove(i)"
                            [attr.aria-label]="'Delete ' + i.name"><app-icon name="trash" [size]="15" /></button>
                  }
                </div>
                <div class="cast contact-shadow" aria-hidden="true"></div>
                <div class="board shelf-board" aria-hidden="true"></div>
              </div>
            }
          </div>
        </div>
      }
    </div>

    @if (editing()) {
      <app-modal [title]="form.id ? 'Edit ingredient' : 'New ingredient'" (close)="editing.set(null)">
        <div class="field"><label for="ing-name">Name <span class="req" aria-hidden="true">*</span></label><input id="ing-name" class="input" [(ngModel)]="form.name" placeholder="Whole milk" required /></div>
        <div class="two-col">
          <div class="field"><label for="ing-base-unit">Base unit <span class="req" aria-hidden="true">*</span></label><input id="ing-base-unit" class="input" [(ngModel)]="form.baseUnit" placeholder="ml / g / pc" maxlength="16" required /></div>
          <div class="field"><label for="ing-category">Category</label><input id="ing-category" class="input" [(ngModel)]="form.category" placeholder="Dairy" /></div>
        </div>
        <label class="checkbox"><input type="checkbox" [(ngModel)]="form.active" /> Active</label>
        <div footer>
          <button class="btn btn-outline" (click)="editing.set(null)">Cancel</button>
          <button class="btn btn-primary" (click)="save()" [disabled]="saving()">@if (saving()) { <span class="spinner"></span> } Save</button>
        </div>
      </app-modal>
    }
  `,
  styles: [`
    /* pantry niche: visual comes from global .niche; only spacing is local */
    .pantry { padding: 2.4rem 1.8rem .2rem; }
    .shelf-grid {
      display: grid; grid-template-columns: repeat(auto-fill, minmax(176px, 1fr));
      column-gap: 0; row-gap: 2.6rem;
    }
    .slot { display: flex; flex-direction: column; align-items: center; min-width: 0; }

    /* ---- the jar ---- */
    .jar { position: relative; z-index: 1; }
    .jar-body { display: flex; flex-direction: column; align-items: center; width: 148px; border-radius: 14px; }
    .jar-body:not(.static):hover { transform: translateY(-4px); }

    .lid {
      width: 68%; height: 14px; border-radius: 7px 7px 2px 2px;
      background: linear-gradient(180deg, #e2cdab, #c2a075 50%, #a8845a);
      box-shadow: inset 0 1.5px 0 rgba(255,255,255,.7), inset 0 -2px 2px rgba(90,60,30,.35),
                  0 1px 2px rgba(90,60,30,.35);
    }
    .glass {
      position: relative; width: 86%; height: 88px; margin-top: -2px; overflow: hidden;
      border-radius: 5px 5px 15px 15px;
      display: flex; align-items: center; justify-content: center;
      color: color-mix(in srgb, var(--brand) 72%, #3a2413);
      background: linear-gradient(180deg, rgba(255,255,255,.94), rgba(247,240,229,.8));
      border: 1.5px solid rgba(120, 85, 45, .30);
      box-shadow: inset 0 2px 1px rgba(255,255,255,.9), inset 0 -3px 6px rgba(120,80,40,.12);
    }
    /* contents visible through the glass */
    .glass::after {
      content: ""; position: absolute; left: 0; right: 0; bottom: 0; height: 42%;
      background: linear-gradient(180deg,
        color-mix(in srgb, var(--brand) 22%, transparent),
        color-mix(in srgb, var(--brand) 38%, transparent));
      border-top: 1px solid color-mix(in srgb, var(--brand) 30%, transparent);
    }
    .glass app-icon { position: relative; z-index: 1; margin-top: -8px; }
    .shine {
      position: absolute; top: 6px; bottom: 8px; left: 10%; width: 6px; z-index: 2;
      border-radius: 999px; background: rgba(255,255,255,.9); filter: blur(1.5px);
    }

    /* ---- paper label (global .paper-card recipe, local layout) ---- */
    .tag-label {
      display: flex; flex-direction: column; align-items: center; gap: .3rem;
      width: 92%; margin-top: -26px; z-index: 2; padding: .55rem .6rem .6rem;
      background: linear-gradient(180deg, var(--paper), var(--paper-2));
      border: 1px solid var(--paper-line); border-radius: 9px;
      box-shadow: 0 1px 2px rgba(90,60,30,.18), inset 0 1px 0 rgba(255,255,255,.55);
    }
    .jar-name {
      font-size: 13.5px; font-weight: 650; color: var(--paper-ink); letter-spacing: -.01em;
      max-width: 100%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
    }
    .jar-meta { display: flex; align-items: baseline; gap: .45rem; max-width: 100%; }
    .jar-cat { font-size: 11.5px; color: var(--paper-ink-soft); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .jar-unit { font-family: var(--font-mono); font-size: 11px; color: var(--text-muted);
      background: var(--surface-2); border: 1px solid var(--hairline); border-radius: 5px; padding: 0 .35rem; }
    .jar-status { height: 20px; font-size: 10.5px; padding: 0 .5rem; }

    .jar-del { top: 10px; right: 2px; }

    /* ---- inactive = dusty jar ---- */
    .jar.inactive .glass, .jar.inactive .lid { filter: saturate(.35) opacity(.75); }
    .jar.inactive .jar-name { color: var(--text-soft); }

    /* ---- cast shadow + shelf board: visuals from global .contact-shadow /
       .shelf-board (token-driven, dark handled there); only geometry here ---- */
    .cast { margin: -4px 0 -5px; }
    .board { width: 100%; }
    .slot:first-child .board { border-top-left-radius: 6px; border-bottom-left-radius: 8px; }
    .slot:last-child .board { border-top-right-radius: 6px; border-bottom-right-radius: 8px; }

    /* ---- dark mode: dim glass (paper/wood handled by global tokens) ---- */
    @media (prefers-color-scheme: dark) {
      .pantry { box-shadow: inset 0 2px 8px rgba(0,0,0,.4); }
      .lid { background: linear-gradient(180deg, #6d5638, #4e3b22 55%, #3c2c17);
        box-shadow: inset 0 1px 0 rgba(255,255,255,.14), inset 0 -2px 3px rgba(0,0,0,.5); }
      .glass {
        background: linear-gradient(180deg, rgba(255,255,255,.14), rgba(255,255,255,.06));
        border-color: rgba(255,255,255,.2);
        box-shadow: inset 0 2px 1px rgba(255,255,255,.14), inset 0 -3px 6px rgba(0,0,0,.35);
        color: color-mix(in srgb, var(--brand) 80%, #fff);
      }
      .shine { background: rgba(255,255,255,.22); }
      .cast { background: radial-gradient(closest-side, rgba(0,0,0,.55), transparent 78%); }
    }
  `],
})
export class IngredientsComponent {
  auth = inject(AuthService);
  private api = inject(InventoryService);
  private toast = inject(ToastService);
  private confirm = inject(ConfirmService);

  items = signal<IngredientResponse[]>([]);
  loading = signal(true);
  editing = signal<IngredientResponse | null>(null);
  saving = signal(false);
  form: { id?: string } & IngredientRequest = { name: '', baseUnit: '', category: '', active: true };

  constructor() { this.load(); }

  get canWrite() { return this.auth.can('inventory:write'); }

  /** Pick an embossed jar glyph from the free-text category. */
  jarIcon(category?: string): string {
    const c = (category || '').toLowerCase();
    if (/dairy|milk|cream/.test(c)) return 'cup';
    if (/coffee|bean|espresso/.test(c)) return 'coffee';
    if (/pack|paper|lid|box|suppl/.test(c)) return 'box';
    if (/produce|fruit|veg|fresh/.test(c)) return 'basket';
    return 'flask';
  }

  load() {
    this.loading.set(true);
    this.api.listIngredients().subscribe({
      next: (i) => { this.items.set(i); this.loading.set(false); },
      error: () => this.loading.set(false),
    });
  }

  openNew() { this.form = { name: '', baseUnit: '', category: '', active: true }; this.editing.set({} as IngredientResponse); }
  openEdit(i: IngredientResponse) { this.form = { id: i.id, name: i.name, baseUnit: i.baseUnit, category: i.category, active: i.active }; this.editing.set(i); }

  save() {
    if (!this.form.name || !this.form.baseUnit) { this.toast.error('Name and base unit are required'); return; }
    this.saving.set(true);
    const { id, ...body } = this.form;
    const req = id ? this.api.updateIngredient(id, body) : this.api.createIngredient(body);
    req.subscribe({
      next: () => { this.saving.set(false); this.editing.set(null); this.toast.success('Ingredient saved'); this.load(); },
      error: () => this.saving.set(false),
    });
  }

  async remove(i: IngredientResponse) {
    const ok = await this.confirm.ask({ title: `Delete ${i.name}?`, danger: true, confirmText: 'Delete' });
    if (!ok) return;
    this.api.deleteIngredient(i.id).subscribe({ next: () => { this.toast.success('Deleted'); this.load(); } });
  }
}
