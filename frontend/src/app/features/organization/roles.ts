import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { IdentityService } from '../../core/api.services';
import { AuthService } from '../../core/auth.service';
import { ToastService } from '../../core/toast.service';
import { ModalComponent } from '../../shared/modal';
import { PERMISSION_GROUPS } from '../../core/permissions';
import { RoleResponse } from '../../core/models';

@Component({
  selector: 'app-roles',
  standalone: true,
  imports: [FormsModule, ModalComponent],
  template: `
    <div class="page">
      <div class="page-head">
        <div><h1>Roles</h1><div class="sub">Permission bundles granted to users per branch.</div></div>
        @if (auth.can('role:write')) { <button class="btn btn-primary" (click)="openNew()">＋ New role</button> }
      </div>

      @if (loading()) { <div class="card"><div class="loading-block"><span class="spinner"></span> Loading…</div></div> }
      @else {
        <div class="roles-grid">
          @for (r of items(); track r.id) {
            <div class="card card-pad role-card">
              <div class="flex" style="justify-content:space-between">
                <div><h3>{{ r.name }}</h3><div class="soft" style="font-size:12.5px">{{ r.description || '—' }}</div></div>
                @if (auth.can('role:write')) { <button class="btn btn-sm btn-outline" (click)="openEdit(r)">Edit perms</button> }
              </div>
              <div class="perm-chips">
                @for (p of r.permissions; track p) { <span class="badge badge-blue">{{ p }}</span> }
                @if (!r.permissions.length) { <span class="muted">No permissions</span> }
              </div>
            </div>
          } @empty { <div class="card"><div class="empty"><div class="big">🔑</div>No roles yet</div></div> }
        </div>
      }
    </div>

    @if (editing()) {
      <app-modal [title]="form.id ? 'Edit role permissions' : 'New role'" [width]="620" (close)="editing.set(null)">
        @if (!form.id) {
          <div class="two-col">
            <div class="field"><label>Name</label><input class="input" [(ngModel)]="form.name" placeholder="Barista" /></div>
            <div class="field"><label>Description</label><input class="input" [(ngModel)]="form.description" /></div>
          </div>
        } @else {
          <div class="soft" style="margin-bottom:1rem">Editing <b>{{ form.name }}</b> — toggles replace the full permission set.</div>
        }
        <div class="perm-groups">
          @for (g of groups; track g.label) {
            <div class="perm-group">
              <div class="pg-title">{{ g.label }}</div>
              @for (p of g.perms; track p.key) {
                <label class="checkbox perm-item">
                  <input type="checkbox" [checked]="selected().has(p.key)" (change)="toggle(p.key)" />
                  <span><b>{{ p.key }}</b><small>{{ p.desc }}</small></span>
                </label>
              }
            </div>
          }
        </div>
        <div footer>
          <button class="btn btn-outline" (click)="editing.set(null)">Cancel</button>
          <button class="btn btn-primary" (click)="save()" [disabled]="saving()">
            @if (saving()) { <span class="spinner"></span> } Save
          </button>
        </div>
      </app-modal>
    }
  `,
  styles: [`
    .roles-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(320px, 1fr)); gap: 1.1rem; }
    .role-card h3 { font-size: 15px; }
    .perm-chips { display: flex; flex-wrap: wrap; gap: .35rem; margin-top: 1rem; }
    .two-col { display: grid; grid-template-columns: 1fr 1fr; gap: 0 1rem; }
    .perm-groups { display: grid; grid-template-columns: 1fr 1fr; gap: 1rem 1.5rem; }
    .pg-title { font-size: 11.5px; text-transform: uppercase; letter-spacing: .04em; color: var(--text-muted); font-weight: 700; margin-bottom: .5rem; }
    .perm-item { align-items: flex-start; padding: .35rem 0; }
    .perm-item span { display: flex; flex-direction: column; line-height: 1.3; }
    .perm-item small { color: var(--text-muted); font-size: 11px; }
    @media (max-width: 620px) { .perm-groups { grid-template-columns: 1fr; } }
  `],
})
export class RolesComponent {
  auth = inject(AuthService);
  private api = inject(IdentityService);
  private toast = inject(ToastService);
  groups = PERMISSION_GROUPS;

  items = signal<RoleResponse[]>([]);
  loading = signal(true);
  editing = signal<RoleResponse | null>(null);
  saving = signal(false);
  selected = signal<Set<string>>(new Set());
  form: { id?: string; name: string; description?: string } = { name: '' };

  constructor() { this.load(); }

  load() {
    this.loading.set(true);
    this.api.listRoles().subscribe({
      next: (r) => { this.items.set(r); this.loading.set(false); },
      error: () => this.loading.set(false),
    });
  }

  openNew() { this.form = { name: '' }; this.selected.set(new Set()); this.editing.set({} as RoleResponse); }
  openEdit(r: RoleResponse) {
    this.form = { id: r.id, name: r.name, description: r.description };
    this.selected.set(new Set(r.permissions));
    this.editing.set(r);
  }

  toggle(key: string) {
    const s = new Set(this.selected());
    s.has(key) ? s.delete(key) : s.add(key);
    this.selected.set(s);
  }

  save() {
    const perms = [...this.selected()];
    if (!this.form.id && !this.form.name) { this.toast.error('Name is required'); return; }
    if (!perms.length) { this.toast.error('Select at least one permission'); return; }
    this.saving.set(true);
    const req = this.form.id
      ? this.api.replacePermissions(this.form.id, { permissions: perms })
      : this.api.createRole({ name: this.form.name, description: this.form.description, permissions: perms });
    req.subscribe({
      next: () => { this.saving.set(false); this.editing.set(null); this.toast.success('Role saved'); this.load(); },
      error: () => this.saving.set(false),
    });
  }
}
