import { Injectable, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { AuthService } from '../core/auth.service';
import { BranchResponse } from '../core/models';
import { NAV_SECTIONS } from './nav';

/** Switches the active branch and leaves a page the new branch's permissions close. */
@Injectable({ providedIn: 'root' })
export class BranchSwitcher {
  private auth = inject(AuthService);
  private router = inject(Router);

  /** The company's branches as the shell last loaded them, so a screen can name the branch it was switched to. */
  readonly known = signal<BranchResponse[]>([]);

  /** The branch's name, or null while it is not in the loaded list. */
  nameOf(id: string): string | null {
    return this.known().find((b) => b.id === id)?.name ?? null;
  }

  switchTo(id: string): void {
    this.auth.setActiveBranch(id);
    // permissions follow the branch, but route guards run only on navigation
    const path = this.router.url.split(/[?#]/)[0];
    const page = NAV_SECTIONS.flatMap((s) => s.items).find((i) => i.path === path);
    if (page?.perms && !this.auth.canAny(...page.perms)) void this.router.navigateByUrl('/');
  }
}
