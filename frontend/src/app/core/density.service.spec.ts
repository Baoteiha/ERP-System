import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { stubLocalStorage } from './test-storage';
import { DensityService } from './density.service';

describe('DensityService', () => {
  beforeEach(() => {
    stubLocalStorage();
    document.documentElement.removeAttribute('data-density');
    TestBed.configureTestingModule({ providers: [provideZonelessChangeDetection()] });
  });
  afterEach(() => {
    document.documentElement.removeAttribute('data-density');
    vi.unstubAllGlobals();
  });

  it('defaults to comfortable', () => {
    const d = TestBed.inject(DensityService);
    TestBed.tick();
    expect(d.pref()).toBe('comfortable');
    expect(document.documentElement.getAttribute('data-density')).toBe('comfortable');
  });

  it('toggle switches to compact and remembers it', () => {
    const d = TestBed.inject(DensityService);
    d.toggle();
    TestBed.tick();
    expect(document.documentElement.getAttribute('data-density')).toBe('compact');
    expect(localStorage.getItem('erpcafe.density')).toBe('compact');
  });

  it('reads a remembered choice', () => {
    localStorage.setItem('erpcafe.density', 'compact');
    expect(TestBed.inject(DensityService).pref()).toBe('compact');
  });
});
