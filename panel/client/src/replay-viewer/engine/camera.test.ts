import test from 'node:test';
import assert from 'node:assert/strict';
import { PerspectiveCamera } from 'three';
import { clampMouseStep, decideKey } from './CameraController.ts';
import { CameraRig, flightStep, lookDirection } from './CameraRig.ts';

const SUBJECT = { x: 10, y: 64, z: -5, yaw: 0, pitch: 0, eyeHeight: 1.62 };

function assertAt(camera: PerspectiveCamera, x: number, y: number, z: number): void {
  assert.ok(camera.position.distanceTo(camera.position.clone().set(x, y, z)) < 1e-9, camera.position.toArray().join(','));
}

void test('a missing subject holds the last camera placement instead of jumping to the origin', () => {
  const rig = new CameraRig();
  const camera = new PerspectiveCamera();
  rig.setMode('first-person', camera);
  rig.update(camera, SUBJECT, 0.016, new Set());
  assertAt(camera, 10, 64 + 1.62, -5);
  rig.update(camera, null, 0.016, new Set());
  assertAt(camera, 10, 64 + 1.62, -5);
  rig.setMode('follow', camera);
  rig.update(camera, null, 0.016, new Set());
  assertAt(camera, 10, 64 + 1.62, -5);
});

void test('before any subject is seen the camera starts at the free seed', () => {
  const rig = new CameraRig();
  const camera = new PerspectiveCamera();
  rig.setFree(3, 70, 9, 0, 30);
  rig.update(camera, null, 0.016, new Set());
  assertAt(camera, 3, 70, 9);
  rig.setMode('free', camera);
  rig.update(camera, null, 0.016, new Set());
  assertAt(camera, 3, 70, 9);
});

void test('switching to free keeps the current view', () => {
  const rig = new CameraRig();
  const camera = new PerspectiveCamera();
  rig.setMode('first-person', camera);
  rig.update(camera, SUBJECT, 0.016, new Set());
  rig.setMode('free', camera);
  rig.update(camera, null, 0.016, new Set(['KeyW']));
  assertAt(camera, 10, 64 + 1.62, -5 + 10 * 0.016);
});

void test('diagonal flight is as fast as straight flight', () => {
  const straight = flightStep(new Set(['KeyW']), 0, 1);
  const diagonal = flightStep(new Set(['KeyW', 'KeyD']), 0, 1);
  const climbing = flightStep(new Set(['KeyW', 'KeyD', 'Space']), 0, 1);
  assert.ok(Math.abs(Math.hypot(...straight) - 1) < 1e-9);
  assert.ok(Math.abs(Math.hypot(...diagonal) - 1) < 1e-9);
  assert.ok(Math.abs(Math.hypot(...climbing) - 1) < 1e-9);
  assert.deepEqual(flightStep(new Set(['KeyW', 'KeyS']), 0, 1), [0, 0, 0]);
});

void test('direction helpers reuse the caller buffer', () => {
  const out: [number, number, number] = [0, 0, 0];
  assert.equal(lookDirection(90, 0, out), out);
  assert.equal(flightStep(new Set(['KeyW']), 0, 1, out), out);
});

void test('the free camera uses spectator keys and leaves Space, Shift, and E to the shortcuts until the mouse is captured', () => {
  for (const code of ['KeyW', 'KeyA', 'KeyS', 'KeyD']) {
    assert.deepEqual(decideKey(code, false, 'follow', false), { capture: true, enterFree: true });
    assert.deepEqual(decideKey(code, false, 'free', false), { capture: true, enterFree: false });
  }
  for (const code of ['Space', 'ShiftLeft', 'ShiftRight', 'KeyE', 'KeyQ']) {
    assert.equal(decideKey(code, false, 'free', false).capture, false, code);
    assert.equal(decideKey(code, false, 'follow', false).capture, false, code);
  }
  for (const code of ['Space', 'ShiftLeft', 'KeyW']) assert.deepEqual(decideKey(code, false, 'free', true), { capture: true, enterFree: false });
  for (const code of ['KeyE', 'KeyQ']) assert.equal(decideKey(code, false, 'free', true).capture, false, code);
  assert.equal(decideKey('KeyW', true, 'free', true).capture, false);
});

void test('mouse look ignores the spike right after the pointer locks', () => {
  assert.equal(clampMouseStep(5000), 120);
  assert.equal(clampMouseStep(-5000), -120);
  assert.equal(clampMouseStep(7), 7);
});
