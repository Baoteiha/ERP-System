import { Component, provideZonelessChangeDetection, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ConfirmService } from '../core/confirm.service';
import { ModalComponent } from './modal';
import { ConfirmHostComponent } from './overlays';

@Component({
  standalone: true,
  imports: [ModalComponent, ConfirmHostComponent],
  template: `
    @if (open()) { <app-modal title="Edit" (close)="closed = closed + 1; open.set(false)"><button>inside</button></app-modal> }
    <app-confirm-host />
  `,
})
class HostComponent {
  open = signal(true);
  closed = 0;
}

describe('overlay stack', () => {
  let fixture: ReturnType<typeof TestBed.createComponent<HostComponent>>;
  let confirm: ConfirmService;

  const escape = () => document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideZonelessChangeDetection()] });
    fixture = TestBed.createComponent(HostComponent);
    confirm = TestBed.inject(ConfirmService);
    fixture.detectChanges();
  });

  afterEach(() => fixture.destroy());

  it('closes only the confirm when Escape is pressed with a confirm open over a modal', async () => {
    const answer = confirm.ask({ title: 'Delete?' });
    fixture.detectChanges();

    escape();
    fixture.detectChanges();

    expect(await answer).toBe(false);
    expect(fixture.componentInstance.closed).toBe(0);
    expect(fixture.nativeElement.querySelector('app-modal')).not.toBeNull();
  });

  it('closes the modal on the next Escape once the confirm is gone', () => {
    void confirm.ask({ title: 'Delete?' });
    fixture.detectChanges();
    escape();
    fixture.detectChanges();

    escape();
    fixture.detectChanges();

    expect(fixture.componentInstance.closed).toBe(1);
  });
});
