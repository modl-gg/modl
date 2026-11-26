import type { PerspectiveCamera } from 'three';
import { type Blocked, type CameraSubject, CameraRig } from './CameraRig.ts';
import type { CameraMode } from './ReplayPlayer.ts';

const MOVE_KEYS: ReadonlySet<string> = new Set(['KeyW', 'KeyA', 'KeyS', 'KeyD', 'Space', 'ShiftLeft', 'ShiftRight']);
const FREE_ENTRY_KEYS: ReadonlySet<string> = new Set(['KeyW', 'KeyA', 'KeyS', 'KeyD']);
const DRAG_SENSITIVITY = 0.3;
const MOUSE_LOOK_SENSITIVITY = 0.15;
const MAX_MOUSE_STEP = 120;

export interface KeyDecision {
  readonly capture: boolean;
  readonly enterFree: boolean;
}

const IGNORE: KeyDecision = { capture: false, enterFree: false };
const MOVE: KeyDecision = { capture: true, enterFree: false };
const ENTER_FREE: KeyDecision = { capture: true, enterFree: true };

export function decideKey(code: string, modified: boolean, mode: CameraMode, locked: boolean): KeyDecision {
  if (modified || !MOVE_KEYS.has(code)) return IGNORE;
  if (locked && mode === 'free') return MOVE;
  if (!FREE_ENTRY_KEYS.has(code)) return IGNORE;
  return mode === 'free' ? MOVE : ENTER_FREE;
}

export function clampMouseStep(value: number): number {
  return Math.max(-MAX_MOUSE_STEP, Math.min(MAX_MOUSE_STEP, value));
}

function hasModifier(event: KeyboardEvent): boolean {
  return event.ctrlKey || event.metaKey || event.altKey;
}

export class CameraController {
  private readonly rig = new CameraRig();
  private readonly keys = new Set<string>();
  private dragging = false;
  private lastX = 0;
  private lastY = 0;
  private settling = false;
  onInput: () => void = () => undefined;
  onEnterFree: () => void = () => undefined;
  private readonly controller = new AbortController();
  private readonly element: HTMLElement;

  constructor(element: HTMLElement, blocked: Blocked) {
    this.element = element;
    this.rig.blocked = blocked;
    const signal = this.controller.signal;
    element.addEventListener('pointerdown', (event) => {
      element.focus();
      if (this.rig.mode === 'free') {
        this.captureMouse();
        return;
      }
      this.dragging = true;
      this.lastX = event.clientX;
      this.lastY = event.clientY;
      element.setPointerCapture(event.pointerId);
    }, { signal });
    element.addEventListener('mousemove', (event) => {
      if (this.rig.mode !== 'free' || !this.mouseCaptured) return;
      if (this.settling) {
        this.settling = false;
        return;
      }
      this.turn(clampMouseStep(event.movementX) * MOUSE_LOOK_SENSITIVITY, clampMouseStep(event.movementY) * MOUSE_LOOK_SENSITIVITY);
    }, { signal });
    element.addEventListener('pointermove', (event) => {
      if (!this.dragging) return;
      const dx = event.clientX - this.lastX;
      const dy = event.clientY - this.lastY;
      this.lastX = event.clientX;
      this.lastY = event.clientY;
      this.turn(dx * DRAG_SENSITIVITY, dy * DRAG_SENSITIVITY);
    }, { signal });
    const release = (event: PointerEvent): void => {
      this.dragging = false;
      if (element.hasPointerCapture(event.pointerId)) element.releasePointerCapture(event.pointerId);
    };
    element.addEventListener('pointerup', release, { signal });
    element.addEventListener('pointercancel', release, { signal });
    element.addEventListener('wheel', (event) => {
      event.preventDefault();
      this.rig.zoom(event.deltaY <= 0);
      this.onInput();
    }, { signal, passive: false });
    element.addEventListener('keydown', (event) => {
      const decision = decideKey(event.code, hasModifier(event), this.rig.mode, this.mouseCaptured);
      if (!decision.capture) return;
      event.preventDefault();
      event.stopPropagation();
      if (decision.enterFree) this.onEnterFree();
      if (!event.repeat) this.captureMouse();
      this.keys.add(event.code);
      this.onInput();
    }, { signal });
    element.addEventListener('keyup', (event) => {
      this.keys.delete(event.code);
    }, { signal });
    element.addEventListener('blur', () => {
      this.keys.clear();
      this.dragging = false;
    }, { signal });
    document.addEventListener('pointerlockchange', () => {
      this.settling = this.mouseCaptured;
      if (!this.mouseCaptured) this.keys.clear();
    }, { signal });
  }

  get mode(): CameraMode {
    return this.rig.mode;
  }

  private get mouseCaptured(): boolean {
    return document.pointerLockElement === this.element;
  }

  get moving(): boolean {
    return this.rig.mode === 'free' && this.keys.size > 0;
  }

  captureMouse(): void {
    if (this.rig.mode !== 'free' || this.mouseCaptured) return;
    Promise.resolve(this.element.requestPointerLock()).catch(() => undefined);
  }

  private turn(dx: number, dy: number): void {
    this.rig.rotate(dx, dy);
    this.onInput();
  }

  setFree(x: number, y: number, z: number, yaw: number, pitch: number): void {
    this.rig.setFree(x, y, z, yaw, pitch);
  }

  setMode(mode: CameraMode, camera: PerspectiveCamera): void {
    this.rig.setMode(mode, camera);
    if (mode === 'free') return;
    this.keys.clear();
    if (this.mouseCaptured) document.exitPointerLock();
  }

  update(camera: PerspectiveCamera, subject: CameraSubject | null, deltaSeconds: number): void {
    this.rig.update(camera, subject, deltaSeconds, this.keys);
  }

  dispose(): void {
    if (this.mouseCaptured) document.exitPointerLock();
    this.controller.abort();
    this.keys.clear();
  }
}
