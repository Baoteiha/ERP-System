import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { SparklineComponent, sparkPath } from './sparkline';

describe('sparkPath', () => {
  it('spans the full width and flips y so bigger is higher', () => {
    expect(sparkPath([0, 10], 100, 20)).toBe('M0.0,19.0 L100.0,1.0');
  });
  it('draws a flat line in the middle when every value is equal', () => {
    expect(sparkPath([5, 5, 5], 100, 20)).toBe('M0.0,10.0 L50.0,10.0 L100.0,10.0');
  });
  it('is empty for fewer than two points', () => {
    expect(sparkPath([3], 100, 20)).toBe('');
  });
});

describe('sparkPath with bad values', () => {
  it('drops non-finite values', () => {
    expect(sparkPath([1, NaN, 3], 100, 20)).not.toContain('NaN');
  });
});

describe('SparklineComponent', () => {
  beforeEach(() => TestBed.configureTestingModule({ providers: [provideZonelessChangeDetection()] }));
  const render = (values: number[]) => {
    const f = TestBed.createComponent(SparklineComponent);
    f.componentRef.setInput('values', values);
    f.detectChanges();
    return f.nativeElement as HTMLElement;
  };
  it('draws the line but no fill when every value is equal', () => {
    const el = render([0, 0, 0]);
    expect(el.querySelector('.spark-line')?.getAttribute('d')).toBeTruthy();
    expect(el.querySelector('.spark-fill')?.getAttribute('d') ?? '').toBe('');
  });
  it('renders no svg for a single value', () => {
    expect(render([1]).querySelector('svg')).toBeNull();
  });
  it('renders a hidden svg with line and fill for two values', () => {
    const el = render([1, 2]);
    const svg = el.querySelector('svg[aria-hidden="true"]');
    expect(svg).not.toBeNull();
    expect(svg?.querySelector('.spark-line')).not.toBeNull();
    expect(svg?.querySelector('.spark-fill')).not.toBeNull();
  });
});
