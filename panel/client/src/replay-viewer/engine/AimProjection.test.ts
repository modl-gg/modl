import test from 'node:test';
import assert from 'node:assert/strict';
import { Matrix4, PerspectiveCamera } from 'three';
import { aimPoint, blockReach, castAim, projectToScreen } from './AimProjection.ts';

const WIDTH = 800;
const HEIGHT = 600;

function viewProjectionLookingNorth(): readonly number[] {
  const camera = new PerspectiveCamera(70, WIDTH / HEIGHT, 0.05, 512);
  camera.position.set(0.5, 65.62, 4.5);
  camera.lookAt(0.5, 65.62, 0);
  camera.updateMatrixWorld();
  return new Matrix4().multiplyMatrices(camera.projectionMatrix, camera.matrixWorldInverse).elements;
}

await test('reach follows the game mode', () => {
  assert.equal(blockReach('survival'), 4.5);
  assert.equal(blockReach('adventure'), 4.5);
  assert.equal(blockReach(null), 4.5);
  assert.equal(blockReach('creative'), 5);
});

await test('the aim ray stops at the first solid block face', () => {
  const wallAtZMinus3 = (_x: number, _y: number, z: number) => z === -3;
  const hit = castAim([0.5, 65.62, 0.5], [0, 0, -1], 4.5, wallAtZMinus3);
  assert.ok(Math.abs(hit[2] - -2) < 1e-9);
});

await test('without a block in reach the aim point is at full reach', () => {
  const end = castAim([0.5, 65.62, 0.5], [0, 0, -1], 4.5, () => false);
  assert.ok(Math.abs(end[2] - -4) < 1e-9);
});

await test('points behind the camera or off screen are hidden', () => {
  const viewProjection = viewProjectionLookingNorth();
  const ahead = projectToScreen([0.5, 65.62, -4], viewProjection, WIDTH, HEIGHT);
  assert.ok(ahead !== null && Math.abs(ahead.x - WIDTH / 2) < 1e-6 && Math.abs(ahead.y - HEIGHT / 2) < 1e-6);
  assert.equal(projectToScreen([0.5, 65.62, 10], viewProjection, WIDTH, HEIGHT), null);
  assert.equal(projectToScreen([200, 65.62, 0], viewProjection, WIDTH, HEIGHT), null);
});

await test('the crosshair shows only while the camera is on a player', () => {
  const base = { reach: 4.5, fullCubeAt: () => false, viewProjection: viewProjectionLookingNorth(), width: WIDTH, height: HEIGHT };
  const subject = { eye: [0.5, 65.62, 0.5] as const, direction: [0, 0, -1] as const };
  assert.deepEqual(aimPoint({ ...base, cameraMode: 'first-person', subject }), { x: WIDTH / 2, y: HEIGHT / 2 });
  assert.equal(aimPoint({ ...base, cameraMode: 'free', subject }), null);
  assert.equal(aimPoint({ ...base, cameraMode: 'follow', subject: null }), null);
  const followed = aimPoint({ ...base, cameraMode: 'follow', subject });
  assert.ok(followed !== null && Math.abs(followed.x - WIDTH / 2) < 1e-6);
});
