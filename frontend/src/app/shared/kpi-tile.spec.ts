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
  it('rounds before choosing the sign', () => {
    const el = render(-0.0004);
    expect(el.querySelector('.kpi-delta.up')?.textContent).toContain('+0.0%');
    expect(el.querySelector('.kpi-delta.down')).toBeNull();
  });
  it('hides the change line for a non-finite delta', () => {
    expect(render(NaN).querySelector('.kpi-delta')).toBeNull();
  });
  it('announces the comparison and shows the arrow only with a delta', () => {
    const el = render(0.1);
    expect(el.querySelector('.sr-only')).not.toBeNull();
    expect(el.querySelector('.kpi-delta app-icon')).not.toBeNull();
    const none = render(null);
    expect(none.querySelector('.sr-only')).toBeNull();
  });
  it('tints the value red only for the negative tone', () => {
    const f = TestBed.createComponent(KpiTileComponent);
    f.componentRef.setInput('label', 'Net margin');
    f.componentRef.setInput('value', '-5 ₫');
    f.detectChanges();
    const el = f.nativeElement as HTMLElement;
    expect(el.querySelector('.kpi-value.negative')).toBeNull();
    f.componentRef.setInput('tone', 'negative');
    f.detectChanges();
    expect(el.querySelector('.kpi-value.negative')).not.toBeNull();
  });
  it('shows a hint line only when one is given', () => {
    const f = TestBed.createComponent(KpiTileComponent);
    f.componentRef.setInput('label', 'Labor');
    f.componentRef.setInput('value', '0 ₫');
    f.detectChanges();
    const el = f.nativeElement as HTMLElement;
    expect(el.querySelector('.kpi-hint')).toBeNull();
    f.componentRef.setInput('hint', 'completed shifts');
    f.detectChanges();
    expect(el.querySelector('.kpi-hint')?.textContent).toContain('completed shifts');
  });
});
