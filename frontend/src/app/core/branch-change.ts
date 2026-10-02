import { effect, inject, untracked } from '@angular/core';
import { AuthService } from './auth.service';

/**
 * Calls `reload` with the new branch id each time the active branch changes (the top-bar switcher, the palette's
 * "Switch branch", another tab). The screen does its first load itself, so the branch it opened with is skipped,
 * and so is a cleared branch (signing out leaves the page). Call it from a constructor; `reload` runs untracked.
 */
export function onBranchChange(reload: (branchId: string) => void): void {
  const auth = inject(AuthService);
  let current = auth.activeBranchId();
  effect(() => {
    const id = auth.activeBranchId();
    if (id === current) return;
    current = id;
    if (id) untracked(() => reload(id));
  });
}
