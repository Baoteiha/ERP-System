import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { KpiTileComponent } from './kpi-tile';

describe('KpiTileComponent', () => {
  beforeEach(() => TestBed.configureTestingModule({ providers: [provideZonelessChangeDetection()] }));
  const render = (delta: number | null) => {
    const f = TestBed.createComponent(KpiTileComponent);
    f.componentRef.setInput('label', 'Net sales');
    f.componentRef.setInput('value', '1.200.000 ₫');
    f.componentRef.setInput('delta', delta);
    f.componentRef.setInput('trend', [1, 2, 3]);
    f.detectChanges();
    return f.nativeElement as HTMLElement;
  };
  it('shows a rise in green with a plus sign', () => {
    const el = render(0.125);
    expect(el.querySelector('.kpi-delta.up')?.textContent).toContain('+12.5%');
  });
  it('shows a fall with a minus sign', () => {
    expect(render(-0.04).querySelector('.kpi-delta.down')?.textContent).toContain('−4.0%');
  });
  it('hides the change line when there is no comparison', () => {
    expect(render(null).querySelector('.kpi-delta')).toBeNull();
  });
});
