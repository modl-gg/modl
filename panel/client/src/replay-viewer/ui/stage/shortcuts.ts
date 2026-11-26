export const PLAYBACK_SPEEDS = [0.25, 0.5, 1, 2, 4, 8] as const;
export const TICK_MS = 50;
export const SHORT_SEEK_MS = 5_000;
export const LONG_SEEK_MS = 10_000;

export type ShortcutAction =
  | { readonly kind: 'toggle-play' }
  | { readonly kind: 'seek-by'; readonly deltaMs: number }
  | { readonly kind: 'seek-to'; readonly edge: 'start' | 'end' }
  | { readonly kind: 'speed'; readonly speed: number }
  | { readonly kind: 'fullscreen' }
  | { readonly kind: 'inventory' }
  | { readonly kind: 'camera' };

export interface ShortcutKey {
  readonly key: string;
  readonly shiftKey: boolean;
  readonly ctrlKey: boolean;
  readonly metaKey: boolean;
  readonly altKey: boolean;
}

export function seekStepFor(key: string, shift: boolean): number | null {
  if (key === 'ArrowLeft') return shift ? -TICK_MS : -SHORT_SEEK_MS;
  if (key === 'ArrowRight') return shift ? TICK_MS : SHORT_SEEK_MS;
  if (key === 'PageDown') return -LONG_SEEK_MS;
  if (key === 'PageUp') return LONG_SEEK_MS;
  return null;
}

const LETTER_ACTIONS: ReadonlyMap<string, ShortcutAction> = new Map<string, ShortcutAction>([
  ['k', { kind: 'toggle-play' }],
  ['j', { kind: 'seek-by', deltaMs: -LONG_SEEK_MS }],
  ['l', { kind: 'seek-by', deltaMs: LONG_SEEK_MS }],
  [',', { kind: 'seek-by', deltaMs: -TICK_MS }],
  ['.', { kind: 'seek-by', deltaMs: TICK_MS }],
  ['f', { kind: 'fullscreen' }],
  ['e', { kind: 'inventory' }],
  ['c', { kind: 'camera' }],
]);

export function shortcutFor(event: ShortcutKey): ShortcutAction | null {
  if (event.ctrlKey || event.metaKey || event.altKey) return null;
  if (event.key === ' ') return { kind: 'toggle-play' };
  if (event.key === 'Home') return { kind: 'seek-to', edge: 'start' };
  if (event.key === 'End') return { kind: 'seek-to', edge: 'end' };
  const step = seekStepFor(event.key, event.shiftKey);
  if (step !== null) return { kind: 'seek-by', deltaMs: step };
  const digit = Number.parseInt(event.key, 10);
  const speed = Number.isInteger(digit) ? PLAYBACK_SPEEDS[digit - 1] : undefined;
  if (speed !== undefined) return { kind: 'speed', speed };
  return LETTER_ACTIONS.get(event.key.toLowerCase()) ?? null;
}

const TEXT_ENTRY = 'input, textarea, select, [contenteditable=""], [contenteditable="true"]';
const ACTIVATABLE = 'button, a[href], [role="tab"], [role="menuitem"]';
const ACTIVATION_KEYS: ReadonlySet<string> = new Set([' ', 'Enter']);

export const SHORTCUT_EXEMPT_ATTRIBUTE = 'data-replay-no-shortcuts';
export const SHORTCUT_EXEMPT_MARKER = { [SHORTCUT_EXEMPT_ATTRIBUTE]: '' };

export function ignoresShortcut(target: Element, key: string): boolean {
  if (target.closest(`${TEXT_ENTRY}, [${SHORTCUT_EXEMPT_ATTRIBUTE}]`) !== null) return true;
  if (target.closest('[role="slider"]') !== null && (seekStepFor(key, false) !== null || key === 'Home' || key === 'End')) return true;
  return ACTIVATION_KEYS.has(key) && target.closest(ACTIVATABLE) !== null;
}

export function nextSpeed(current: number, direction: 1 | -1): number {
  const index = PLAYBACK_SPEEDS.findIndex((speed) => speed === current);
  const start = index < 0 ? PLAYBACK_SPEEDS.indexOf(1) : index;
  const next = (start + direction + PLAYBACK_SPEEDS.length) % PLAYBACK_SPEEDS.length;
  return PLAYBACK_SPEEDS[next] ?? 1;
}

export function formatSpeed(speed: number): string {
  return `${String(speed)}x`;
}
