const MS_PER_TICK = 50;

function formatSeconds(totalSeconds: number): string {
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return `${minutes}:${seconds.toString().padStart(2, '0')}`;
}

export function formatTime(ms: number): string {
  return formatSeconds(Math.floor(ms / 1000));
}

export function formatTicks(ticks: number): string {
  return formatSeconds(Math.max(0, Math.floor(ticks / 20)));
}

export function elapsedTicks(sinceMs: number, nowMs: number): number {
  return Math.floor((nowMs - sinceMs) / MS_PER_TICK);
}
