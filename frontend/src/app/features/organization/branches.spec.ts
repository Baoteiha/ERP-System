import { stubLocalStorage } from '../../core/test-storage';
import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { AuthService } from '../../core/auth.service';
import { BranchResponse } from '../../core/models';
import { BranchesComponent } from './branches';

const branch = (id: string, name: string, active = true) =>
  ({ id, companyId: 'co', name, code: id.toUpperCase(), active, taxRate: '0.0800' }) as BranchResponse;
const page = <T>(content: T[]) => ({ content, page: 0, size: 100, totalElements: content.length, totalPages: 1 });

describe('BranchesComponent row actions', () => {
  let ctrl: HttpTestingController;

  function render(roles: string[], permissionsByBranch: Record<string, string[]>) {
    stubLocalStorage();
    TestBed.configureTestingModule({
      providers: [provideZonelessChangeDetection(), provideHttpClient(), provideHttpClientTesting()],
    });
    TestBed.inject(AuthService).me.set({
      userId: 'u', email: 'a@b.c', companyId: 'co', branchIds: ['b1', 'b2'], roles,
      permissions: ['branch:read', 'branch:write'], permissionsByBranch,
    });
    ctrl = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(BranchesComponent);
    ctrl.expectOne((r) => r.url === '/api/v1/branches').flush(page([branch('b1', 'Main'), branch('b2', 'Riverside', false)]));
    fixture.detectChanges();
    return fixture;
  }

  const rowActions = (fixture: ReturnType<typeof render>) =>
    [...fixture.nativeElement.querySelectorAll('table.data tbody tr')].map(
      (r: HTMLTableRowElement) => [...r.querySelectorAll('.row-actions button')].map((b) => b.textContent?.trim() || b.getAttribute('aria-label')));

  afterEach(() => ctrl.verify());

  it('offers Edit and Delete only on the branches where the role carries branch:write', () => {
    const fixture = render(['MANAGER'], { b1: ['branch:read', 'branch:write'], b2: ['branch:read'] });

    expect(rowActions(fixture)).toEqual([['Edit Main', 'Delete Main'], []]);
  });

  it('opens the editor from the name only on the branches where Edit is offered', () => {
    const fixture = render(['MANAGER'], { b1: ['branch:read', 'branch:write'], b2: ['branch:read'] });
    const rows = [...fixture.nativeElement.querySelectorAll('table.data tbody tr')] as HTMLElement[];

    expect(rows.map((r) => !!r.querySelector('button.row-open'))).toEqual([true, false]);
  });

  it('offers them on every branch to an owner', () => {
    const fixture = render(['OWNER'], { b1: ['branch:read'], b2: ['branch:read'] });

    expect(rowActions(fixture)).toEqual([['Edit Main', 'Delete Main'], ['Edit Riverside', 'Delete Riverside']]);
  });

  it('shows the status through the shared badge', () => {
    const fixture = render(['OWNER'], {});
    const badges = [...fixture.nativeElement.querySelectorAll('app-status-badge .badge')] as HTMLElement[];

    expect(badges.map((b) => b.textContent?.trim())).toEqual(['Active', 'Inactive']);
  });
});
