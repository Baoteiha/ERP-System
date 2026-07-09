import { Component, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { IdentityService, OrganizationService } from '../../core/api.services';
import { AuthService } from '../../core/auth.service';
import { ToastService } from '../../core/toast.service';
import { ModalComponent } from '../../shared/modal';
import { BranchAccessResponse, BranchResponse, RoleResponse, UserResponse } from '../../core/models';

@Component({
  selector: 'app-users',
  standalone: true,
  imports: [FormsModule, ModalComponent, DatePipe],
  template: `
    <div class="page">
      <div class="page-head">
        <div><h1>Users</h1><div class="sub">Accounts and their per-branch role assignments.</div></div>
        @if (auth.can('user:write')) { <button class="btn btn-primary" (click)="openNew()">＋ New user</button> }
      </div>

      <div class="card">
        @if (loading()) { <div class="loading-block"><span class="spinner"></span> Loading…</div> }
        @else {
          <div class="table-wrap">
            <table class="data">
              <thead><tr><th>Email</th><th>Full name</th><th>Status</th><th>Created</th><th></th></tr></thead>
              <tbody>
                @for (u of items(); track u.id) {
                  <tr>
                    <td><b>{{ u.email }}</b></td>
                    <td class="soft">{{ u.fullName || '—' }}</td>
                    <td><span class="badge dot" [class]="u.status === 'ACTIVE' ? 'badge-green' : 'badge-gray'">{{ u.status }}</span></td>
                    <td class="soft">{{ u.createdAt | date:'mediumDate' }}</td>
                    <td class="row-actions">
                      @if (auth.can('user:write')) { <button class="btn btn-sm btn-ghost" (click)="openAccess(u)">Branch access</button> }
                    </td>
                  </tr>
                } @empty { <tr><td colspan="5"><div class="empty"><div class="big">👤</div>No users yet</div></td></tr> }
              </tbody>
            </table>
          </div>
        }
      </div>
    </div>

    @if (creating()) {
      <app-modal title="New user" (close)="creating.set(false)">
        <div class="field"><label>Email</label><input class="input" type="email" [(ngModel)]="nu.email" placeholder="barista@esscafe.vn" /></div>
        <div class="field"><label>Password</label><input class="input" type="password" [(ngModel)]="nu.password" placeholder="min 8 chars" /></div>
        <div class="field"><label>Full name</label><input class="input" [(ngModel)]="nu.fullName" /></div>
        <div footer>
          <button class="btn btn-outline" (click)="creating.set(false)">Cancel</button>
          <button class="btn btn-primary" (click)="createUser()" [disabled]="saving()">
            @if (saving()) { <span class="spinner"></span> } Create
          </button>
        </div>
      </app-modal>
    }

    @if (accessUser(); as u) {
      <app-modal [title]="'Branch access — ' + u.email" (close)="accessUser.set(null)">
        <div class="access-list">
          @for (a of access(); track a.branchId + a.roleId) {
            <div class="access-row">
              <span>📍 {{ branchName(a.branchId) }}</span>
              <span class="spacer"></span>
              <span class="badge badge-blue">{{ roleName(a.roleId) }}</span>
            </div>
          } @empty { <div class="muted" style="padding:.5rem 0">No branch access granted yet.</div> }
        </div>

        <div class="grant-box">
          <div class="pg-title">Grant access</div>
          <div class="two-col">
            <div class="field"><label>Branch</label>
              <select class="select" [(ngModel)]="grant.branchId">
                <option value="" disabled>Select…</option>
                @for (b of branches(); track b.id) { <option [value]="b.id">{{ b.name }}</option> }
              </select>
            </div>
            <div class="field"><label>Role</label>
              <select class="select" [(ngModel)]="grant.roleId">
                <option value="" disabled>Select…</option>
                @for (r of roles(); track r.id) { <option [value]="r.id">{{ r.name }}</option> }
              </select>
            </div>
          </div>
          <button class="btn btn-primary btn-block" (click)="doGrant()" [disabled]="saving()">Grant access</button>
        </div>
        <div footer><button class="btn btn-outline" (click)="accessUser.set(null)">Done</button></div>
      </app-modal>
    }
  `,
  styles: [`
    .two-col { display: grid; grid-template-columns: 1fr 1fr; gap: 0 1rem; }
    .access-row { display: flex; align-items: center; gap: .5rem; padding: .55rem 0; border-bottom: 1px solid var(--border); font-size: 13.5px; }
    .grant-box { margin-top: 1.2rem; padding-top: 1.2rem; border-top: 1px dashed var(--border-strong); }
    .pg-title { font-size: 11.5px; text-transform: uppercase; letter-spacing: .04em; color: var(--text-muted); font-weight: 700; margin-bottom: .7rem; }
  `],
})
export class UsersComponent {
  auth = inject(AuthService);
  private api = inject(IdentityService);
  private org = inject(OrganizationService);
  private toast = inject(ToastService);

  items = signal<UserResponse[]>([]);
  branches = signal<BranchResponse[]>([]);
  roles = signal<RoleResponse[]>([]);
  access = signal<BranchAccessResponse[]>([]);
  loading = signal(true);
  saving = signal(false);
  creating = signal(false);
  accessUser = signal<UserResponse | null>(null);

  nu = { email: '', password: '', fullName: '' };
  grant = { branchId: '', roleId: '' };

  constructor() {
    this.load();
    this.org.listBranches().subscribe({ next: (p) => this.branches.set(p.content) });
    this.api.listRoles().subscribe({ next: (r) => this.roles.set(r) });
  }

  load() {
    this.loading.set(true);
    this.api.listUsers().subscribe({
      next: (p) => { this.items.set(p.content); this.loading.set(false); },
      error: () => this.loading.set(false),
    });
  }

  branchName(id: string) { return this.branches().find((b) => b.id === id)?.name ?? id.slice(0, 6); }
  roleName(id: string) { return this.roles().find((r) => r.id === id)?.name ?? id.slice(0, 6); }

  openNew() { this.nu = { email: '', password: '', fullName: '' }; this.creating.set(true); }
  createUser() {
    if (!this.nu.email || this.nu.password.length < 8) { this.toast.error('Email and 8+ char password required'); return; }
    this.saving.set(true);
    this.api.createUser(this.nu).subscribe({
      next: () => { this.saving.set(false); this.creating.set(false); this.toast.success('User created'); this.load(); },
      error: () => this.saving.set(false),
    });
  }

  openAccess(u: UserResponse) {
    this.accessUser.set(u);
    this.grant = { branchId: '', roleId: '' };
    this.api.userAccess(u.id).subscribe({ next: (a) => this.access.set(a) });
  }
  doGrant() {
    const u = this.accessUser();
    if (!u || !this.grant.branchId || !this.grant.roleId) { this.toast.error('Pick a branch and role'); return; }
    this.saving.set(true);
    this.api.grantAccess(u.id, this.grant).subscribe({
      next: () => {
        this.saving.set(false);
        this.toast.success('Access granted');
        this.api.userAccess(u.id).subscribe({ next: (a) => this.access.set(a) });
      },
      error: () => this.saving.set(false),
    });
  }
}
