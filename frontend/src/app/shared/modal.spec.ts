import { Component, provideZonelessChangeDetection, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ModalComponent } from './modal';

@Component({
  imports: [ModalComponent],
  template: `@if (open()) { <app-modal title="Edit" [variant]="variant()" [closeOnBackdrop]="closeOnBackdrop()" (close)="open.set(false)"><input id="a" /><button id="b">b</button><div footer></div></app-modal> }`,
})
class HostComponent { open = signal(true); closeOnBackdrop = signal(true); variant = signal<'dialog' | 'drawer'>('drawer'); }

describe('ModalComponent drawer', () => {
  beforeEach(() => TestBed.configureTestingModule({ providers: [provideZonelessChangeDetection()] }));

  it('renders as a drawer', () => {
    const f = TestBed.createComponent(HostComponent);
    f.detectChanges();
    expect(f.nativeElement.querySelector('.modal-backdrop').classList).toContain('is-drawer');
  });

  it('closes on Escape', () => {
    const f = TestBed.createComponent(HostComponent);
    f.detectChanges();
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    f.detectChanges();
    expect(f.componentInstance.open()).toBe(false);
  });

  it('defaults to a centred dialog', () => {
    const f = TestBed.createComponent(HostComponent);
    f.componentInstance.variant.set('dialog');
    f.detectChanges();
    expect(f.nativeElement.querySelector('.modal-backdrop').classList).not.toContain('is-drawer');
  });
  it('closes on a backdrop click', () => {
    const f = TestBed.createComponent(HostComponent);
    f.detectChanges();
    f.nativeElement.querySelector('.modal-backdrop').click();
    f.detectChanges();
    expect(f.componentInstance.open()).toBe(false);
  });
  it('stays open on a backdrop click when closeOnBackdrop is false', () => {
    const f = TestBed.createComponent(HostComponent);
    f.componentInstance.closeOnBackdrop.set(false);
    f.detectChanges();
    f.nativeElement.querySelector('.modal-backdrop').click();
    f.detectChanges();
    expect(f.componentInstance.open()).toBe(true);
  });
});
