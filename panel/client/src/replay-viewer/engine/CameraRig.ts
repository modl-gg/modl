import type { PerspectiveCamera } from 'three';
import type { CameraMode } from './ReplayPlayer.ts';

export interface CameraSubject {
  readonly x: number;
  readonly y: number;
  readonly z: number;
  readonly yaw: number;
  readonly pitch: number;
  readonly eyeHeight: number;
}

export type Direction = [number, number, number];

export type Blocked = (x: number, y: number, z: number) => boolean;

const DEG = Math.PI / 180;
const COLLISION_STEP = 0.1;
const COLLISION_MARGIN = 0.3;
const MAX_PITCH = 89.9;
const MAX_STEP_SECONDS = 0.1;

export function lookDirection(yaw: number, pitch: number, out: Direction = [0, 0, 0]): Direction {
  const cosPitch = Math.cos(pitch * DEG);
  out[0] = -Math.sin(yaw * DEG) * cosPitch;
  out[1] = -Math.sin(pitch * DEG);
  out[2] = Math.cos(yaw * DEG) * cosPitch;
  return out;
}

export function flightStep(keys: ReadonlySet<string>, yaw: number, distance: number, out: Direction = [0, 0, 0]): Direction {
  const fx = -Math.sin(yaw * DEG);
  const fz = Math.cos(yaw * DEG);
  let mx = 0;
  let my = 0;
  let mz = 0;
  if (keys.has('KeyW')) { mx += fx; mz += fz; }
  if (keys.has('KeyS')) { mx -= fx; mz -= fz; }
  if (keys.has('KeyA')) { mx += fz; mz -= fx; }
  if (keys.has('KeyD')) { mx -= fz; mz += fx; }
  if (keys.has('Space')) my += 1;
  if (keys.has('ShiftLeft') || keys.has('ShiftRight')) my -= 1;
  const length = Math.hypot(mx, my, mz);
  const scale = length === 0 ? 0 : distance / length;
  out[0] = mx * scale;
  out[1] = my * scale;
  out[2] = mz * scale;
  return out;
}

export class CameraRig {
  private currentMode: CameraMode = 'follow';
  private orbitYaw = 0;
  private orbitPitch = 20;
  private distance = 4;
  private freeYaw = 0;
  private freePitch = 30;
  private freeX = 0;
  private freeY = 0;
  private freeZ = 0;
  private flySpeed = 10;
  private placed = false;
  private readonly look: Direction = [0, 0, 0];
  private readonly step: Direction = [0, 0, 0];
  blocked: Blocked = () => false;

  get mode(): CameraMode {
    return this.currentMode;
  }

  rotate(dx: number, dy: number): void {
    if (this.currentMode === 'free') {
      this.freeYaw += dx;
      this.freePitch = Math.max(-MAX_PITCH, Math.min(MAX_PITCH, this.freePitch + dy));
    } else if (this.currentMode === 'follow') {
      this.orbitYaw += dx;
      this.orbitPitch = Math.max(-80, Math.min(85, this.orbitPitch + dy));
    }
  }

  zoom(closer: boolean): void {
    if (this.currentMode === 'free') this.flySpeed = Math.min(60, Math.max(2, this.flySpeed * (closer ? 1.15 : 0.85)));
    else this.distance = Math.min(24, Math.max(1.5, this.distance * (closer ? 0.9 : 1.1)));
  }

  setFree(x: number, y: number, z: number, yaw: number, pitch: number): void {
    this.freeX = x;
    this.freeY = y;
    this.freeZ = z;
    this.freeYaw = yaw;
    this.freePitch = Math.max(-MAX_PITCH, Math.min(MAX_PITCH, pitch));
  }

  setMode(mode: CameraMode, camera: PerspectiveCamera): void {
    if (mode === 'free' && this.currentMode !== 'free' && this.placed) {
      const direction = camera.getWorldDirection(camera.position.clone());
      this.setFree(camera.position.x, camera.position.y, camera.position.z, Math.atan2(-direction.x, direction.z) / DEG, -Math.asin(Math.max(-1, Math.min(1, direction.y))) / DEG);
    }
    this.currentMode = mode;
  }

  update(camera: PerspectiveCamera, subject: CameraSubject | null, deltaSeconds: number, keys: ReadonlySet<string>): void {
    if (this.currentMode === 'free') {
      this.fly(keys, deltaSeconds);
      this.place(camera, this.freeX, this.freeY, this.freeZ, this.freeYaw, this.freePitch);
      return;
    }
    if (subject === null) {
      if (!this.placed) this.place(camera, this.freeX, this.freeY, this.freeZ, this.freeYaw, this.freePitch);
      return;
    }
    const eyeX = subject.x;
    const eyeY = subject.y + subject.eyeHeight;
    const eyeZ = subject.z;
    if (this.currentMode === 'first-person') {
      this.place(camera, eyeX, eyeY, eyeZ, subject.yaw, subject.pitch);
      return;
    }
    const yaw = subject.yaw + this.orbitYaw;
    const pitch = this.orbitPitch;
    const [dx, dy, dz] = lookDirection(yaw, pitch, this.look);
    const distance = this.clearDistance(eyeX, eyeY, eyeZ, -dx, -dy, -dz);
    this.place(camera, eyeX - dx * distance, eyeY - dy * distance, eyeZ - dz * distance, yaw, pitch);
  }

  private clearDistance(x: number, y: number, z: number, dx: number, dy: number, dz: number): number {
    for (let travelled = COLLISION_STEP; travelled <= this.distance; travelled += COLLISION_STEP) {
      if (this.blocked(Math.floor(x + dx * travelled), Math.floor(y + dy * travelled), Math.floor(z + dz * travelled))) {
        return Math.max(0.3, travelled - COLLISION_MARGIN);
      }
    }
    return this.distance;
  }

  private fly(keys: ReadonlySet<string>, deltaSeconds: number): void {
    if (keys.size === 0) return;
    const [mx, my, mz] = flightStep(keys, this.freeYaw, this.flySpeed * Math.min(MAX_STEP_SECONDS, deltaSeconds), this.step);
    this.freeX += mx;
    this.freeY += my;
    this.freeZ += mz;
  }

  private place(camera: PerspectiveCamera, x: number, y: number, z: number, yaw: number, pitch: number): void {
    camera.position.set(x, y, z);
    const [dx, dy, dz] = lookDirection(yaw, pitch, this.look);
    camera.up.set(0, 1, 0);
    camera.lookAt(x + dx, y + dy, z + dz);
    this.placed = true;
  }
}
