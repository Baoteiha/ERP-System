import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { IconComponent } from './icon';

describe('IconComponent', () => {
  function render(name: string) {
    TestBed.configureTestingModule({ providers: [provideZonelessChangeDetection()] });
    const fixture = TestBed.createComponent(IconComponent);
    fixture.componentRef.setInput('name', name);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it('draws every shape of the icon as SVG elements', () => {
    const el = render('trash');

    expect(el.querySelectorAll('path').length).toBe(3);
    expect(el.querySelectorAll('line').length).toBe(2);
    expect(el.querySelector('path')?.namespaceURI).toBe('http://www.w3.org/2000/svg');
    expect(el.querySelector('path')?.getAttribute('d')).toContain('M3 6h18');
  });

  it('keeps per-shape attributes such as a filled dot', () => {
    expect(render('tag').querySelector('circle')?.getAttribute('fill')).toBe('currentColor');
  });

  it('renders an empty svg for an unknown name', () => {
    expect(render('nope').querySelectorAll('path').length).toBe(0);
  });
});
