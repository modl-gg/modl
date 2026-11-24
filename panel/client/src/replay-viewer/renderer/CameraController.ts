import * as THREE from 'three';

export type CameraMode = 'free' | 'follow';

/**
 * Camera controller supporting free-fly (WASD + mouse) and follow-player modes.
 */
export class CameraController {
  private readonly camera: THREE.PerspectiveCamera;
  private readonly domElement: HTMLElement;
  private mode: CameraMode = 'free';

  // Free-fly state
  private readonly moveSpeed = 20;
  private readonly lookSpeed = 0.002;
  private readonly euler: THREE.Euler = new THREE.Euler(0, 0, 0, 'YXZ');
  private readonly keys = new Set<string>();
  private isPointerLocked = false;

  // Follow state
  private readonly followOffset: THREE.Vector3 = new THREE.Vector3(0, 5, -6);
  private followTarget: { x: number; y: number; z: number; yaw: number } | null = null;

  // Smooth follow
  private readonly smoothPosition: THREE.Vector3 = new THREE.Vector3();
  private readonly smoothLookAt: THREE.Vector3 = new THREE.Vector3();
  private readonly moveDirection = new THREE.Vector3();
  private readonly targetCameraPosition = new THREE.Vector3();
  private readonly targetLookAt = new THREE.Vector3();

  // Bound event handlers for cleanup
  private readonly boundOnKeyDown: (e: KeyboardEvent) => void;
  private readonly boundOnKeyUp: (e: KeyboardEvent) => void;
  private readonly boundOnMouseMove: (e: MouseEvent) => void;
  private readonly boundOnClick: (e: MouseEvent) => void;
  private readonly boundOnPointerLockChange: () => void;

  constructor(camera: THREE.PerspectiveCamera, domElement: HTMLElement) {
    this.camera = camera;
    this.domElement = domElement;
    this.smoothPosition.copy(camera.position);

    this.boundOnKeyDown = this.onKeyDown.bind(this);
    this.boundOnKeyUp = this.onKeyUp.bind(this);
    this.boundOnMouseMove = this.onMouseMove.bind(this);
    this.boundOnClick = this.onClick.bind(this);
    this.boundOnPointerLockChange = this.onPointerLockChange.bind(this);

    this.attach();
  }

  private attach(): void {
    document.addEventListener('keydown', this.boundOnKeyDown);
    document.addEventListener('keyup', this.boundOnKeyUp);
    document.addEventListener('mousemove', this.boundOnMouseMove);
    this.domElement.addEventListener('click', this.boundOnClick);
    document.addEventListener('pointerlockchange', this.boundOnPointerLockChange);
  }

  setMode(mode: CameraMode): void {
    this.mode = mode;
    if (mode === 'follow') {
      // Exit pointer lock when switching to follow mode
      if (document.pointerLockElement) {
        document.exitPointerLock();
      }
    }
  }

  getMode(): CameraMode {
    return this.mode;
  }

  /**
   * Set the follow target (player position + yaw).
   * Called each frame when following a player.
   */
  setFollowTarget(target: { x: number; y: number; z: number; yaw: number }): void {
    this.followTarget = target;
  }

  /**
   * Update camera position. Call once per frame.
   * @param deltaTime Time since last frame in seconds
   */
  update(deltaTime: number): void {
    switch (this.mode) {
      case 'free':
        this.updateFreeCamera(deltaTime);
        break;
      case 'follow':
        this.updateFollowCamera(deltaTime);
        break;
    }
  }

  dispose(): void {
    document.removeEventListener('keydown', this.boundOnKeyDown);
    document.removeEventListener('keyup', this.boundOnKeyUp);
    document.removeEventListener('mousemove', this.boundOnMouseMove);
    this.domElement.removeEventListener('click', this.boundOnClick);
    document.removeEventListener('pointerlockchange', this.boundOnPointerLockChange);

    if (document.pointerLockElement === this.domElement) {
      document.exitPointerLock();
    }
  }

  // --- Private ---

  private updateFreeCamera(deltaTime: number): void {
    // Compute movement direction from keys
    const direction = this.moveDirection.set(0, 0, 0);

    if (this.keys.has('w')) direction.z -= 1;
    if (this.keys.has('s')) direction.z += 1;
    if (this.keys.has('a')) direction.x -= 1;
    if (this.keys.has('d')) direction.x += 1;
    if (this.isPointerLocked && this.keys.has('shift')) direction.y -= 1;

    direction.normalize();

    // Apply camera rotation to movement direction
    direction.applyQuaternion(this.camera.quaternion);

    this.camera.position.addScaledVector(direction, this.moveSpeed * deltaTime);
  }

  private updateFollowCamera(deltaTime: number): void {
    if (!this.followTarget) return;

    const { x, y, z, yaw } = this.followTarget;

    // Compute camera position: behind and above the player
    const yawRad = (yaw * Math.PI) / 180;
    const targetCamPos = this.targetCameraPosition.set(
      x - Math.sin(yawRad) * this.followOffset.z,
      y + this.followOffset.y,
      z - Math.cos(yawRad) * this.followOffset.z,
    );

    const targetLookAt = this.targetLookAt.set(x, y + 1.6, z);

    // Smooth follow with lerp
    const smoothFactor = 1 - Math.exp(-5 * deltaTime);
    this.smoothPosition.lerp(targetCamPos, smoothFactor);
    this.smoothLookAt.lerp(targetLookAt, smoothFactor);

    this.camera.position.copy(this.smoothPosition);
    this.camera.lookAt(this.smoothLookAt);
  }

  private onKeyDown(e: KeyboardEvent): void {
    this.keys.add(e.key.toLowerCase());
  }

  private onKeyUp(e: KeyboardEvent): void {
    this.keys.delete(e.key.toLowerCase());
  }

  private onMouseMove(e: MouseEvent): void {
    if (this.mode !== 'free' || !this.isPointerLocked) return;

    this.euler.setFromQuaternion(this.camera.quaternion);
    this.euler.y -= e.movementX * this.lookSpeed;
    this.euler.x -= e.movementY * this.lookSpeed;

    // Clamp pitch to prevent flipping
    this.euler.x = Math.max(-Math.PI / 2, Math.min(Math.PI / 2, this.euler.x));

    this.camera.quaternion.setFromEuler(this.euler);
  }

  private onClick(): void {
    if (this.mode === 'free' && !this.isPointerLocked) {
      Promise.resolve(this.domElement.requestPointerLock()).catch(() => {
        this.isPointerLocked = false;
      });
    }
  }

  private onPointerLockChange(): void {
    this.isPointerLocked = document.pointerLockElement === this.domElement;
  }
}
