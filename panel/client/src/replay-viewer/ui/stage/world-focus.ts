export const REPLAY_WORLD_ATTRIBUTE = 'data-replay-world';
export const REPLAY_WORLD_MARKER = { [REPLAY_WORLD_ATTRIBUTE]: '' };

export function focusReplayWorld(scope: HTMLElement | null): void {
  const world = scope?.querySelector(`[${REPLAY_WORLD_ATTRIBUTE}]`);
  const target = world instanceof HTMLElement ? world : scope;
  target?.focus({ preventScroll: true });
}
