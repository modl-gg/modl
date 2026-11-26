import type { CameraMode, ReplayPlayerInfo } from '../../engine/ReplayPlayer';

export function playerHudSubject(cameraMode: CameraMode, viewed: ReplayPlayerInfo | null): ReplayPlayerInfo | null {
  return cameraMode === 'free' ? null : viewed;
}
