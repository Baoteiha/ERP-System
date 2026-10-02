import { stubLocalStorage } from '../../core/test-storage';
import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { AuthService } from '../../core/auth.service';
import { EmployeeResponse } from '../../core/models';
import { TeamComponent } from './team';

const an = {
  id: 'e1', fullName: 'An Nguyen', position: 'Barista', active: true, phone: '0901', email: 'an@x.vn',
  hourlyRate: { amount: '30000', currency: 'VND' },
} as EmployeeResponse;

describe('TeamComponent', () => {
  it('opens the edit drawer for the employee whose Edit button is pressed', () => {
    stubLocalStorage();
    TestBed.configureTestingModule({
      providers: [provideZonelessChangeDetection(), provideHttpClient(), provideHttpClientTesting()],
    });
    TestBed.inject(AuthService).me.set({
      userId: 'u', email: 'a@b.c', companyId: 'co', branchIds: ['b1'], roles: [],
      permissions: ['staff:write'], permissionsByBranch: { b1: ['staff:write'] },
    });
    const ctrl = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(TeamComponent);
    ctrl.expectOne((r) => r.url.startsWith('/api/v1/staff')).flush([an]);
    fixture.detectChanges();

    const btn = fixture.nativeElement.querySelector('button[aria-label="Edit An Nguyen"]') as HTMLButtonElement;
    btn.click();
    fixture.detectChanges();

    expect(fixture.componentInstance.editing()?.id).toBe('e1');
    expect(fixture.componentInstance.modalOpen()).toBe(true);
    expect(fixture.componentInstance.form.fullName).toBe('An Nguyen');
    expect(fixture.nativeElement.querySelector('#emp-fullname')).not.toBeNull();
  });
});
