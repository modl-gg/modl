import type { ScreenPoint } from '../../engine/ReplayPlayer';

export const CROSSHAIR_SIZE = 15;

export function crosshairOrigin(point: ScreenPoint, cssScale: number): ScreenPoint {
  const half = CROSSHAIR_SIZE / 2;
  return {
    x: Math.floor(point.x / cssScale - half) * cssScale,
    y: Math.floor(point.y / cssScale - half) * cssScale,
  };
}
