import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { InventoryService } from '../../core/api.services';
import { AuthService } from '../../core/auth.service';
import { ConfirmService } from '../../core/confirm.service';
import { SupplierRequest, SupplierResponse } from '../../core/models';
import { ToastService } from '../../core/toast.service';
import { ModalComponent } from '../../shared/modal';
import { IconComponent } from '../../shared/icon';

@Component({
  selector: 'app-suppliers',
  standalone: true,
  imports: [FormsModule, ModalComponent, IconComponent],
  template: `
    <div class="page">
      <div class="page-head">
        <div><h1>Suppliers</h1><div class="sub">Vendor contacts for branch purchasing.</div></div>
        @if (auth.can('inventory:write')) { <button class="btn btn-primary" (click)="openNew()"><app-icon name="plus" [size]="16" /> New supplier</button> }
      </div>

      @if (loading()) { <div class="card"><div class="loading-block"><span class="spinner"></span> Loading…</div></div> }
      @else if (!items().length) {
        <div class="card"><div class="empty"><div class="big"><app-icon name="boxes" [size]="30" /></div>No suppliers yet — schedule your first delivery.</div></div>
      }
      @else {
        <div class="crate-yard niche">
          <div class="crate-grid" role="list">
            @for (s of items(); track s.id) {
              <div class="crate-slot" role="listitem">
                <div class="crate reveal-zone">
                  <button type="button" class="crate-face obj-btn" [class.static]="!canWrite"
                          (click)="canWrite && openEdit(s)" [tabindex]="canWrite ? null : -1"
                          [attr.aria-label]="canWrite ? 'Edit ' + s.name : null" [class.dusty]="!s.active">
                    <span class="ship-label paper-card">
                      <b class="sup-name">{{ s.name }}</b>
                      @if (s.contactPhone) { <span class="sup-line">{{ s.contactPhone }}</span> }
                      @if (s.contactEmail) { <span class="sup-line">{{ s.contactEmail }}</span> }
                      @if (s.address) {
                        <span class="sup-addr"><app-icon name="pin" [size]="12" /><span class="addr-text">{{ s.address }}</span></span>
                      }
                      <span class="badge dot sup-status" [class]="s.active ? 'badge-green' : 'badge-gray'">{{ s.active ? 'Active' : 'Inactive' }}</span>
                    </span>
                  </button>
                  @if (canWrite) {
                    <button type="button" class="btn btn-sm btn-icon obj-del crate-del" (click)="remove(s)"
                            [attr.aria-label]="'Delete ' + s.name"><app-icon name="trash" [size]="15" /></button>
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
      <app-modal [title]="form.id ? 'Edit supplier' : 'New supplier'" (close)="editing.set(null)">
        <div class="field"><label for="sup-name">Name <span class="req" aria-hidden="true">*</span></label><input id="sup-name" class="input" [(ngModel)]="form.name" placeholder="Acme Foods" required /></div>
        <div class="two-col">
          <div class="field"><label for="sup-phone">Phone</label><input id="sup-phone" class="input" [(ngModel)]="form.contactPhone" /></div>
          <div class="field"><label for="sup-email">Email</label><input id="sup-email" class="input" [(ngModel)]="form.contactEmail" type="email" /></div>
        </div>
        <div class="field"><label for="sup-address">Address</label><textarea id="sup-address" class="input" [(ngModel)]="form.address" rows="3"></textarea></div>
        <label class="checkbox"><input type="checkbox" [(ngModel)]="form.active" /> Active</label>
        <div footer>
          <button class="btn btn-outline" (click)="editing.set(null)">Cancel</button>
          <button class="btn btn-primary" (click)="save()" [disabled]="saving()">@if (saving()) { <span class="spinner"></span> } Save</button>
        </div>
      </app-modal>
    }
  `,
  styles: [`
    .crate-yard { padding: 2.4rem 1.8rem .2rem; }
    .crate-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(250px, 1fr)); column-gap: 0; row-gap: 2.4rem; }
    .crate-slot { display: flex; flex-direction: column; align-items: center; min-width: 0; }

    /* slatted crate front (wood tokens keep it absolute in both schemes) */
    .crate { position: relative; z-index: 1; }
    .crate-face {
      display: flex; flex-direction: column; align-items: center;
      width: 210px; padding-bottom: 14px;
      background: repeating-linear-gradient(180deg, transparent 0 22px, rgba(0,0,0,.16) 22px 24px),
        linear-gradient(180deg, var(--wood-hi), var(--wood-mid) 60%, var(--wood-lo));
      border: 1px solid color-mix(in srgb, var(--wood-lo) 80%, black);
      border-radius: 8px;
      box-shadow: inset 0 2px 0 rgba(255,235,205,.35), inset 0 -3px 4px rgba(40,22,8,.4);
    }

    /* stapled shipping label (surface from global .paper-card) */
    .ship-label {
      position: relative; display: flex; flex-direction: column; align-items: flex-start; gap: .35rem;
      width: 88%; margin-top: 26px; padding: .7rem .75rem .75rem; text-align: left; user-select: text;
    }
    .ship-label::before, .ship-label::after {
      content: ""; position: absolute; top: -2px; width: 10px; height: 3px;
      border-radius: 1.5px; background: rgba(90,60,30,.45);
    }
    .ship-label::before { left: 10px; }
    .ship-label::after { right: 10px; }

    .sup-name {
      font-size: 13px; font-weight: 700; text-transform: uppercase; letter-spacing: .06em;
      color: var(--paper-ink); overflow-wrap: anywhere;
    }
    .sup-line { font-size: 12px; color: var(--paper-ink-soft); overflow-wrap: anywhere; }
    .sup-addr { display: flex; align-items: flex-start; gap: .3rem; font-size: 11.5px; color: var(--paper-ink-soft); }
    .sup-addr app-icon { flex-shrink: 0; margin-top: 1px; }
    .sup-addr .addr-text { display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
    .sup-status { rotate: -3deg; align-self: flex-end; height: 20px; font-size: 10.5px; padding: 0 .5rem; }

    .crate-del { top: 8px; right: 8px; }

    .cast { width: 165px; margin: -4px 0 -5px; }
    .board { width: 100%; }
    .crate-slot:first-child .board { border-top-left-radius: 6px; border-bottom-left-radius: 8px; }
    .crate-slot:last-child .board { border-top-right-radius: 6px; border-bottom-right-radius: 8px; }
  `],
})
export class SuppliersComponent {
  auth = inject(AuthService);
  private api = inject(InventoryService);
  private toast = inject(ToastService);
  private confirm = inject(ConfirmService);

  items = signal<SupplierResponse[]>([]);
  loading = signal(true);
  editing = signal<SupplierResponse | null>(null);
  saving = signal(false);
  form: { id?: string } & SupplierRequest = { name: '', contactPhone: '', contactEmail: '', address: '', active: true };

  constructor() { this.load(); }

  get canWrite() { return this.auth.can('inventory:write'); }

  load() {
    this.loading.set(true);
    this.api.listSuppliers().subscribe({
      next: (items) => { this.items.set(items); this.loading.set(false); },
      error: () => this.loading.set(false),
    });
  }

  openNew() { this.form = { name: '', contactPhone: '', contactEmail: '', address: '', active: true }; this.editing.set({} as SupplierResponse); }
  openEdit(s: SupplierResponse) { this.form = { id: s.id, name: s.name, contactPhone: s.contactPhone, contactEmail: s.contactEmail, address: s.address, active: s.active }; this.editing.set(s); }

  save() {
    if (!this.form.name) { this.toast.error('Supplier name is required'); return; }
    this.saving.set(true);
    const { id, ...body } = this.form;
    const req = id ? this.api.updateSupplier(id, body) : this.api.createSupplier(body);
    req.subscribe({
      next: () => { this.saving.set(false); this.editing.set(null); this.toast.success('Supplier saved'); this.load(); },
      error: () => this.saving.set(false),
    });
  }

  async remove(s: SupplierResponse) {
    const ok = await this.confirm.ask({ title: `Delete ${s.name}?`, danger: true, confirmText: 'Delete' });
    if (!ok) return;
    this.api.deleteSupplier(s.id).subscribe({ next: () => { this.toast.success('Deleted'); this.load(); } });
  }
}
