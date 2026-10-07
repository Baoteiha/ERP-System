import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { IdentityService } from '../../core/api.services';
import { AuthService } from '../../core/auth.service';
import { ToastService } from '../../core/toast.service';
import { afterSave } from '../../core/save-handler';
import { ModalComponent } from '../../shared/modal';
import { IconComponent } from '../../shared/icon';
import { AvatarComponent } from '../../shared/avatar';
import { ACTION_LABELS, ALL_PERMISSIONS, DOMAIN_LABELS, PERMISSION_GROUPS } from '../../core/permissions';
import { RoleResponse } from '../../core/models';

@Component({
  selector: 'app-roles',
  standalone: true,
  imports: [FormsModule, ModalComponent, IconComponent, AvatarComponent],
  template: `
    <div class="page">
      <div class="page-head">
        <div><h1>Roles</h1><div class="sub">Permission bundles granted to users per branch.</div></div>
        @if (auth.can('role:write')) { <button class="btn btn-primary" (click)="openNew()"><app-icon name="plus" [size]="16" /> New role</button> }
      </div>

      <div class="card">
        @if (loading()) { <div class="loading-block"><span class="spinner"></span> Loading…</div> }
        @else {
          <div class="table-wrap">
            <table class="data">
              <caption class="sr-only">Roles with description and the permissions each grants</caption>
              <thead><tr><th scope="col">Role</th><th scope="col">Permissions</th><th scope="col"><span class="sr-only">Actions</span></th></tr></thead>
              <tbody>
                @for (r of items(); track r.id) {
                  <tr>
                    <td>
                      <span class="name-with-avatar"><app-avatar [name]="r.name" />
                        <span class="role-name">
                          @if (auth.can('role:write')) { <button class="row-open" (click)="openEdit(r)">{{ r.name }}</button> }
                          @else { <b>{{ r.name }}</b> }
                          <span class="soft role-desc">{{ r.description || '—' }}</span>
                        </span>
                      </span>
                    </td>
                    <td>
                      @if (permSummary(r); as ps) {
                        <div class="perm-chips">
                          @for (c of ps.chips; track c.domain) {
                            <span class="perm-chip">
                              <span class="pc-domain">{{ c.domain }}</span>
                              @for (a of c.actions; track a) { <span class="pc-act" [class]="'pc-' + a">{{ actionLabel(a) }}</span> }
                            </span>
                          }
                          @for (p of ps.extras; track p) { <span class="badge">{{ p }}</span> }
                          @if (!r.permissions.length) { <span class="muted">No permissions</span> }
                        </div>
                      }
                    </td>
                    <td>
                      <div class="row-actions">
                        @if (auth.can('role:write')) {
                          <button class="btn btn-sm btn-outline" (click)="openEdit(r)" [attr.aria-label]="'Edit permissions for ' + r.name">Edit permissions</button>
                        }
                      </div>
                    </td>
                  </tr>
                } @empty { <tr><td colspan="3"><div class="empty"><div class="big"><app-icon name="key" [size]="30" /></div>No roles yet</div></td></tr> }
              </tbody>
            </table>
          </div>
        }
      </div>
    </div>

    @if (editing()) {
      <app-modal variant="drawer" [title]="form.id ? 'Edit role permissions' : 'New role'" [width]="640" (close)="editing.set(null)">
        @if (!form.id) {
          <div class="two-col">
            <div class="field"><label for="role-name">Name <span class="req" aria-hidden="true">*</span></label><input id="role-name" class="input" [(ngModel)]="form.name" placeholder="e.g. Shift lead" required /></div>
            <div class="field"><label for="role-desc">Description</label><input id="role-desc" class="input" [(ngModel)]="form.description" /></div>
          </div>
        } @else {
          <div class="soft edit-note">Editing <b>{{ form.name }}</b> — toggles replace the full permission set.</div>
        }
        <p class="perm-note">"Manage" access automatically includes "view" for the same area.</p>
        <div class="perm-groups">
          @for (g of groups; track g.label) {
            <div class="perm-group">
              <h3 class="pg-title">{{ g.label }}</h3>
              @for (p of g.perms; track p.key) {
                <label class="checkbox perm-item" [class.on]="selected().has(p.key)">
                  <input type="checkbox" [checked]="selected().has(p.key)" (change)="toggle(p.key)" />
                  <span><b>{{ p.desc }}</b><small class="perm-key">{{ p.key }}</small></span>
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
    .role-name { display: flex; flex-direction: column; line-height: 1.3; }
    .role-desc { font-size: 12.5px; }
    .edit-note { margin-bottom: 1rem; }
    .perm-chips { display: flex; flex-wrap: wrap; gap: .45rem; min-width: 16rem; }
    .perm-chip { display: inline-flex; align-items: center; gap: .45rem; padding: .32rem .7rem;
      border-radius: 999px; white-space: nowrap; max-width: 100%; background: var(--surface-2); border: 1px solid var(--border); font-size: 12px; }
    .pc-domain { font-weight: 650; color: var(--text-soft); }
    .pc-act { font-weight: 600; }
    .pc-act + .pc-act::before { content: "·"; margin-right: .45rem; color: var(--border-strong); font-weight: 400; }
    .pc-read { color: var(--text-muted); }
    .pc-write { color: var(--accent-text); }
    .pc-refund { color: var(--red); }
    .perm-note { font-size: 12px; color: var(--text-muted); margin: 0 0 .9rem; }
    .perm-groups { display: grid; grid-template-columns: 1fr 1fr; gap: 1rem 1.5rem; }
    .pg-title { margin: 0 0 .35rem; position: sticky; top: 0; z-index: 1; background: var(--surface-1); padding: .4rem 0; border-bottom: 1px solid var(--border);
      font-size: 11.5px; text-transform: uppercase; letter-spacing: .04em; color: var(--text-muted); font-weight: 700; }
    .perm-item input { margin-top: 2px; }
    .perm-item { display: flex; align-items: flex-start; padding: .35rem .5rem; border-radius: var(--radius); }
    .perm-item.on { background: var(--accent-soft); }
    .perm-item > span { display: flex; flex-direction: column; line-height: 1.3; }
    .perm-item small { color: var(--text-muted); font-size: 11px; }
    .perm-key { font-family: var(--font-mono); letter-spacing: 0; }
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

  /**
   * Plain-language summary for a role card: one chip per area, actions in
   * view → manage → refund order, areas in catalog order. Unknown keys fall
   * through to `extras` and render verbatim.
   */
  permSummary(r: RoleResponse): { chips: { domain: string; actions: string[] }[]; extras: string[] } {
    const domains = [...new Set(ALL_PERMISSIONS.map((k) => k.split(':')[0]))];
    const rank: Record<string, number> = { read: 0, write: 1, refund: 2, clock: 3, retail: 4 };
    const byDomain = new Map<string, string[]>();
    const extras: string[] = [];
    for (const p of r.permissions ?? []) {
      const [d, a] = p.split(':');
      if (domains.includes(d) && a in rank) {
        byDomain.set(d, [...(byDomain.get(d) ?? []), a]);
      } else {
        extras.push(p);
      }
    }
    const chips = domains
      .filter((d) => byDomain.has(d))
      .map((d) => ({
        domain: DOMAIN_LABELS[d] ?? d,
        actions: byDomain.get(d)!.sort((x, y) => rank[x] - rank[y]),
      }));
    return { chips, extras };
  }

  actionLabel(a: string) { return ACTION_LABELS[a] ?? a; }

  /** Keep the set consistent: manage/refund implies view (retail purchases do not); dropping view drops the rest. */
  toggle(key: string) {
    const s = new Set(this.selected());
    const [domain, action] = key.split(':');
    if (s.has(key)) {
      s.delete(key);
      if (action === 'read') {
        s.delete(`${domain}:write`);
        s.delete(`${domain}:refund`);
      }
    } else {
      s.add(key);
      if (action !== 'read' && action !== 'retail') s.add(`${domain}:read`);
    }
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
    req.subscribe(afterSave(this.saving, () => { this.editing.set(null); this.toast.success('Role saved'); this.load(); }));
  }
}
