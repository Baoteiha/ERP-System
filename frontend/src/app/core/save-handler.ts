import { WritableSignal } from '@angular/core';

/**
 * The observer every save button wants: clear the busy flag whether the request worked or not,
 * and run `done` only when it did. Errors are already toasted by the HTTP interceptor.
 */
export function afterSave<T>(saving: WritableSignal<boolean>, done: (result: T) => void) {
  return {
    next: (result: T) => { saving.set(false); done(result); },
    error: () => saving.set(false),
  };
}
