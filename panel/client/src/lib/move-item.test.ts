import test from 'node:test';
import assert from 'node:assert/strict';

import { canMoveBetween, EVERY_POSITION_MOVABLE, keyboardMoveTarget, keyboardStep, movedItem, renumbered } from './move-item.ts';

const LOCKED_TOP = (index: number) => index >= 2;

await test('a move stays inside the list and needs a different position', () => {
  assert.equal(canMoveBetween(3, 0, 2, EVERY_POSITION_MOVABLE), true);
  assert.equal(canMoveBetween(3, 1, 1, EVERY_POSITION_MOVABLE), false);
  assert.equal(canMoveBetween(3, 0, -1, EVERY_POSITION_MOVABLE), false);
  assert.equal(canMoveBetween(3, 2, 3, EVERY_POSITION_MOVABLE), false);
});

await test('a move needs every position it passes to be movable', () => {
  assert.equal(canMoveBetween(5, 4, 2, LOCKED_TOP), true);
  assert.equal(canMoveBetween(5, 2, 1, LOCKED_TOP), false);
  assert.equal(canMoveBetween(5, 4, 0, LOCKED_TOP), false);
  assert.equal(canMoveBetween(5, 3, 4, (index) => index !== 1), true);
  assert.equal(canMoveBetween(5, 0, 2, (index) => index !== 1), false);
});

await test('arrow keys move one position and stop at an item that cannot move', () => {
  const canMove = (from: number, to: number) => canMoveBetween(5, from, to, LOCKED_TOP);
  assert.equal(keyboardStep('ArrowUp'), -1);
  assert.equal(keyboardStep('ArrowDown'), 1);
  assert.equal(keyboardStep('Enter'), undefined);
  assert.equal(keyboardMoveTarget(3, -1, canMove), 2);
  assert.equal(keyboardMoveTarget(2, -1, canMove), null);
  assert.equal(keyboardMoveTarget(4, 1, canMove), null);
  assert.equal(keyboardMoveTarget(3, 1, canMove), 4);
});

await test('a moved item shifts the items between and renumbering follows the new positions', () => {
  assert.deepEqual(movedItem(['a', 'b', 'c'], 0, 2), ['b', 'c', 'a']);
  assert.deepEqual(renumbered([{ id: 'b', order: 4 }, { id: 'a', order: 1 }]), [{ id: 'b', order: 0 }, { id: 'a', order: 1 }]);
});
