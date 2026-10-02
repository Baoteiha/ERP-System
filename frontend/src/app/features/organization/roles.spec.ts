import { stubLocalStorage } from '../../core/test-storage';
import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { RoleResponse } from '../../core/models';
import { RolesComponent } from './roles';

describe('RolesComponent permSummary', () => {
  it('folds staff:clock into the Staff chip after view and manage', () => {
    stubLocalStorage();
    TestBed.configureTestingModule({
      providers: [provideZonelessChangeDetection(), provideHttpClient(), provideHttpClientTesting()],
    });
    const ctrl = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(RolesComponent);
    ctrl.match(() => true).forEach((r) => r.flush([]));
    const role = { id: 'r', name: 'Crew', permissions: ['staff:clock', 'staff:write', 'staff:read'] } as RoleResponse;

    const summary = fixture.componentInstance.permSummary(role);

    expect(summary.extras).toEqual([]);
    expect(summary.chips).toEqual([{ domain: 'Staff', actions: ['read', 'write', 'clock'] }]);
  });
});
