import { signal } from '@angular/core';
import { Subject } from 'rxjs';
import { afterSave } from './save-handler';

describe('afterSave', () => {
  it('clears the busy flag, then runs the follow-up with the result', () => {
    const saving = signal(true);
    const seen: unknown[] = [];
    const request = new Subject<string>();
    request.subscribe(afterSave(saving, (r) => seen.push([saving(), r])));

    request.next('saved');

    expect(seen).toEqual([[false, 'saved']]);
  });

  it('clears the busy flag on an error and skips the follow-up', () => {
    const saving = signal(true);
    let ran = false;
    const request = new Subject<string>();
    request.subscribe(afterSave(saving, () => { ran = true; }));

    request.error(new Error('boom'));

    expect(saving()).toBe(false);
    expect(ran).toBe(false);
  });
});
