import type { MarkerKind, ReplayMarker } from '../../engine/ReplayPlayer';
import { formatClock } from '../hud/game-text';

export const HANDLE_WIDTH = 8;
const MIN_TIME_LABEL_WIDTH = 72;
const TIME_LABEL_PADDING = 6;
export const SNAP_DISTANCE = 2;

export function timeAtX(x: number, width: number, durationMs: number): number {
  const track = Math.max(1, width - HANDLE_WIDTH);
  const ratio = Math.min(1, Math.max(0, (x - HANDLE_WIDTH / 2) / track));
  return Math.round(ratio * durationMs);
}

export function handleX(timeMs: number, width: number, durationMs: number): number {
  const ratio = durationMs <= 0 ? 0 : Math.min(1, Math.max(0, timeMs / durationMs));
  return Math.round(ratio * (width - HANDLE_WIDTH));
}

export function markerX(timeMs: number, width: number, durationMs: number): number {
  return handleX(timeMs, width, durationMs) + HANDLE_WIDTH / 2;
}

export function nearestMarker(markers: readonly ReplayMarker[], x: number, width: number, durationMs: number): ReplayMarker | null {
  let best: ReplayMarker | null = null;
  let bestDistance = SNAP_DISTANCE + 1;
  for (const marker of markers) {
    const distance = Math.abs(markerX(marker.timeMs, width, durationMs) - x);
    if (distance < bestDistance) {
      best = marker;
      bestDistance = distance;
    }
  }
  return best;
}

export const MARKER_COLORS: Readonly<Record<MarkerKind, number>> = {
  chat: 0x55ffff,
  damage: 0xff5555,
  death: 0xaa0000,
  block_break: 0xffaa00,
  block_place: 0x55ff55,
  capture: 0xffff55,
};

export const MARKER_LABELS: Readonly<Record<MarkerKind, string>> = {
  chat: 'replayViewer.marker.chat',
  damage: 'replayViewer.marker.damage',
  death: 'replayViewer.marker.death',
  block_break: 'replayViewer.marker.blockBreak',
  block_place: 'replayViewer.marker.blockPlace',
  capture: 'replayViewer.marker.capture',
};

export function clockText(timeMs: number, durationMs: number): string {
  return `${formatClock(timeMs)} / ${formatClock(durationMs)}`;
}

export function timeLabelWidth(measure: (text: string) => number, durationMs: number): number {
  return Math.max(MIN_TIME_LABEL_WIDTH, measure(clockText(durationMs, durationMs)) + TIME_LABEL_PADDING);
}
