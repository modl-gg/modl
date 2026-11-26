import test from 'node:test';
import assert from 'node:assert/strict';
import { autoGuiScale, guiFrame, nextGuiScaleChoice, parseGuiScaleChoice } from './gui-scale.ts';

const desktop = { cssWidth: 1296, cssHeight: 748, pixelRatio: 1 };

await test('auto scale follows the game rule of 320 by 240 framebuffer pixels', () => {
  assert.equal(autoGuiScale(desktop), 3);
  assert.equal(autoGuiScale({ ...desktop, pixelRatio: 2 }), 6);
  assert.equal(autoGuiScale({ cssWidth: 1296, cssHeight: 748, pixelRatio: 1.25 }), 3);
  assert.equal(autoGuiScale({ cssWidth: 390, cssHeight: 780, pixelRatio: 3 }), 6);
});

await test('every GUI pixel covers a whole number of device pixels', () => {
  const frame = guiFrame({ cssWidth: 1296, cssHeight: 748, pixelRatio: 1.25 }, 'auto');
  assert.equal(frame.deviceScale, 3);
  assert.equal(frame.cssScale, 2.4);
  assert.deepEqual(guiFrame(desktop, 2), { deviceScale: 2, cssScale: 2, width: 648, height: 374 });
});

await test('a fixed scale never exceeds what fits', () => {
  assert.equal(guiFrame({ cssWidth: 400, cssHeight: 300, pixelRatio: 1 }, 9).deviceScale, 1);
});

await test('the scale choice cycles up to the largest that fits and survives storage', () => {
  assert.equal(nextGuiScaleChoice('auto', desktop), 1);
  assert.equal(nextGuiScaleChoice(2, desktop), 3);
  assert.equal(nextGuiScaleChoice(3, desktop), 'auto');
  assert.equal(parseGuiScaleChoice('3'), 3);
  assert.equal(parseGuiScaleChoice('junk'), 'auto');
  assert.equal(parseGuiScaleChoice(null), 'auto');
});
