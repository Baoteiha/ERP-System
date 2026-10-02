import { Component, computed, input } from '@angular/core';

/** Up to two uppercase initials: first letters of the first two words, or the first two characters of one word. */
export function initialsOf(name: string): string {
  const words = name.trim().split(/\s+/).filter(Boolean);
  if (!words.length) return '?';
  const chars = words.length === 1 ? Array.from(words[0]).slice(0, 2) : [Array.from(words[0])[0], Array.from(words[1])[0]];
  return chars.join('').toUpperCase();
}

/** A round initials badge that stands in for a person or place; decorative, so hidden from assistive tech. */
@Component({
  selector: 'app-avatar',
  standalone: true,
  template: `<span class="avatar" aria-hidden="true">{{ initials() }}</span>`,
  styles: [`:host { display: inline-flex; }`],
})
export class AvatarComponent {
  name = input.required<string>();
  initials = computed(() => initialsOf(this.name()));
}
