import type { GameModeName } from '../format/ReplayModel.ts';
import type { CameraMode, ScreenPoint } from './ReplayPlayer.ts';

export type Vec3 = readonly [number, number, number];

export type FullCubeAt = (x: number, y: number, z: number) => boolean;

const SURVIVAL_REACH = 4.5;
const CREATIVE_REACH = 5;

export function blockReach(gameMode: GameModeName | null): number {
  return gameMode === 'creative' ? CREATIVE_REACH : SURVIVAL_REACH;
}

interface AxisWalk {
  cell: number;
  next: number;
  readonly step: number;
  readonly delta: number;
}

function axisWalk(origin: number, direction: number): AxisWalk {
  const cell = Math.floor(origin);
  if (direction > 0) return { cell, step: 1, next: (cell + 1 - origin) / direction, delta: 1 / direction };
  if (direction < 0) return { cell, step: -1, next: (cell - origin) / direction, delta: -1 / direction };
  return { cell, step: 0, next: Number.POSITIVE_INFINITY, delta: Number.POSITIVE_INFINITY };
}

function along(origin: Vec3, direction: Vec3, distance: number): Vec3 {
  return [origin[0] + direction[0] * distance, origin[1] + direction[1] * distance, origin[2] + direction[2] * distance];
}

export function castAim(origin: Vec3, direction: Vec3, reach: number, fullCubeAt: FullCubeAt): Vec3 {
  const x = axisWalk(origin[0], direction[0]);
  const y = axisWalk(origin[1], direction[1]);
  const z = axisWalk(origin[2], direction[2]);
  let travelled = 0;
  while (travelled <= reach) {
    if (fullCubeAt(x.cell, y.cell, z.cell)) return along(origin, direction, travelled);
    const axis = x.next <= y.next && x.next <= z.next ? x : y.next <= z.next ? y : z;
    travelled = axis.next;
    axis.cell += axis.step;
    axis.next += axis.delta;
  }
  return along(origin, direction, reach);
}

export function projectToScreen(point: Vec3, viewProjection: readonly number[], width: number, height: number): ScreenPoint | null {
  const [x, y, z] = point;
  const m = (index: number) => viewProjection[index] ?? 0;
  const clipX = m(0) * x + m(4) * y + m(8) * z + m(12);
  const clipY = m(1) * x + m(5) * y + m(9) * z + m(13);
  const clipW = m(3) * x + m(7) * y + m(11) * z + m(15);
  if (clipW <= 0) return null;
  const ndcX = clipX / clipW;
  const ndcY = clipY / clipW;
  if (ndcX < -1 || ndcX > 1 || ndcY < -1 || ndcY > 1) return null;
  return { x: ((ndcX + 1) / 2) * width, y: ((1 - ndcY) / 2) * height };
}

export interface AimSubject {
  readonly eye: Vec3;
  readonly direction: Vec3;
}

export interface AimInput {
  readonly cameraMode: CameraMode;
  readonly subject: AimSubject | null;
  readonly reach: number;
  readonly fullCubeAt: FullCubeAt;
  readonly viewProjection: readonly number[];
  readonly width: number;
  readonly height: number;
}

export function aimPoint(input: AimInput): ScreenPoint | null {
  if (input.cameraMode === 'free' || input.subject === null) return null;
  if (input.cameraMode === 'first-person') return { x: input.width / 2, y: input.height / 2 };
  const target = castAim(input.subject.eye, input.subject.direction, input.reach, input.fullCubeAt);
  return projectToScreen(target, input.viewProjection, input.width, input.height);
}
