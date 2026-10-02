import { Component, provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { AuthService } from './auth.service';
import { onBranchChange } from './branch-change';
import { stubLocalStorage } from './test-storage';

const calls: string[] = [];

@Component({ selector: 'app-probe', template: '' })
class ProbeComponent {
  constructor() { onBranchChange((id) => calls.push(id)); }
}

describe('onBranchChange', () => {
  let auth: AuthService;

  beforeEach(() => {
    stubLocalStorage();
    calls.length = 0;
    TestBed.configureTestingModule({ providers: [provideZonelessChangeDetection()] });
    auth = TestBed.inject(AuthService);
    auth.activeBranchId.set('b1');
    TestBed.createComponent(ProbeComponent);
    TestBed.tick();
  });

  it('skips the branch the screen opened with', () => expect(calls).toEqual([]));

  it('calls back once per switch, with the new branch', () => {
    auth.setActiveBranch('b2');
    TestBed.tick();
    TestBed.tick();
    auth.setActiveBranch('b1');
    TestBed.tick();
    expect(calls).toEqual(['b2', 'b1']);
  });

  it('ignores a cleared branch, then follows the next one', () => {
    auth.activeBranchId.set(null);
    TestBed.tick();
    auth.setActiveBranch('b1');
    TestBed.tick();
    expect(calls).toEqual(['b1']);
  });
});
