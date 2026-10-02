/** Per-browser settings in localStorage; a blocked or throwing storage falls back to the default. */
export function readPref<T extends string>(key: string, allowed: readonly T[], fallback: T): T {
  try {
    const value = localStorage.getItem(key);
    return value !== null && (allowed as readonly string[]).includes(value) ? (value as T) : fallback;
  } catch {
    return fallback;
  }
}

/** Remembers a setting; when storage is blocked the choice simply lasts for this visit. */
export function writePref(key: string, value: string): void {
  try { localStorage.setItem(key, value); } catch { /* storage unavailable */ }
}
