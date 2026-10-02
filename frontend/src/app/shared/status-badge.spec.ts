import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { StatusBadgeComponent } from './status-badge';

describe('StatusBadgeComponent', () => {
  function render(active: boolean) {
    TestBed.configureTestingModule({ providers: [provideZonelessChangeDetection()] });
    const fixture = TestBed.createComponent(StatusBadgeComponent);
    fixture.componentRef.setInput('active', active);
    fixture.detectChanges();
    return fixture.nativeElement.querySelector('.badge') as HTMLElement;
  }

  it('shows an active record with the green badge', () => {
    const badge = render(true);
    expect(badge.textContent?.trim()).toBe('Active');
    expect(badge.classList).toContain('badge-green');
    expect(badge.classList).not.toContain('badge-gray');
  });

  it('shows an inactive record with the grey badge', () => {
    const badge = render(false);
    expect(badge.textContent?.trim()).toBe('Inactive');
    expect(badge.classList).toContain('badge-gray');
    expect(badge.classList).not.toContain('badge-green');
  });
});
