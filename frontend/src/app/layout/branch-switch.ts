import { Injectable, inject } from '@angular/core';
import { Router } from '@angular/router';
import { AuthService } from '../core/auth.service';
import { NAV_SECTIONS } from './nav';

/** Switches the active branch and leaves a page the new branch's permissions close. */
@Injectable({ providedIn: 'root' })
export class BranchSwitcher {
  private auth = inject(AuthService);
  private router = inject(Router);

  switchTo(id: string): void {
    this.auth.setActiveBranch(id);
    // permissions follow the branch, but route guards run only on navigation
    const path = this.router.url.split(/[?#]/)[0];
    const page = NAV_SECTIONS.flatMap((s) => s.items).find((i) => i.path === path);
    if (page?.perms && !this.auth.canAny(...page.perms)) void this.router.navigateByUrl('/');
  }
}
