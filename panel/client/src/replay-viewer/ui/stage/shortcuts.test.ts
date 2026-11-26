import test from 'node:test';
import assert from 'node:assert/strict';
import { nextSpeed, shortcutFor } from './shortcuts.ts';

const key = (value: string, shiftKey = false) => ({ key: value, shiftKey, ctrlKey: false, metaKey: false, altKey: false });

await test('player keys map to playback actions', () => {
  assert.deepEqual(shortcutFor(key(' ')), { kind: 'toggle-play' });
  assert.deepEqual(shortcutFor(key('K')), { kind: 'toggle-play' });
  assert.deepEqual(shortcutFor(key('ArrowLeft')), { kind: 'seek-by', deltaMs: -5000 });
  assert.deepEqual(shortcutFor(key('ArrowRight', true)), { kind: 'seek-by', deltaMs: 50 });
  assert.deepEqual(shortcutFor(key('l')), { kind: 'seek-by', deltaMs: 10000 });
  assert.deepEqual(shortcutFor(key('3')), { kind: 'speed', speed: 1 });
  assert.deepEqual(shortcutFor(key('f')), { kind: 'fullscreen' });
  assert.equal(shortcutFor(key('9')), null);
  assert.equal(shortcutFor({ ...key('f'), ctrlKey: true }), null);
});

await test('speed cycles through the vanilla-like steps', () => {
  assert.equal(nextSpeed(1, 1), 2);
  assert.equal(nextSpeed(8, 1), 0.25);
  assert.equal(nextSpeed(0.25, -1), 8);
  assert.equal(nextSpeed(3, 1), 2);
});

await test('E reaches the shortcuts and Q and Shift are no shortcuts', () => {
  assert.deepEqual(shortcutFor(key('e')), { kind: 'inventory' });
  assert.deepEqual(shortcutFor(key('E', true)), { kind: 'inventory' });
  assert.equal(shortcutFor(key('q')), null);
  assert.equal(shortcutFor(key('Shift', true)), null);
});
