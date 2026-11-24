import test from 'node:test';
import assert from 'node:assert/strict';

import { dropWindow, EMPTY_WINDOW_STACK, raiseWindow, topWindow, windowLayer } from './window-stack.ts';

await test('a raised window moves to the top and keeps the order of the others', () => {
  const stack = raiseWindow(raiseWindow(raiseWindow(EMPTY_WINDOW_STACK, 'a'), 'b'), 'c');
  assert.deepEqual(raiseWindow(stack, 'a'), ['b', 'c', 'a']);
});

await test('raising the top window keeps the same stack', () => {
  const stack = raiseWindow(raiseWindow(EMPTY_WINDOW_STACK, 'a'), 'b');
  assert.equal(raiseWindow(stack, 'b'), stack);
});

await test('a dropped window leaves the stack and an unknown one changes nothing', () => {
  const stack = raiseWindow(raiseWindow(EMPTY_WINDOW_STACK, 'a'), 'b');
  assert.deepEqual(dropWindow(stack, 'a'), ['b']);
  assert.equal(dropWindow(stack, 'missing'), stack);
});

await test('the layer is the position from the bottom, starting at one', () => {
  const stack = raiseWindow(raiseWindow(EMPTY_WINDOW_STACK, 'a'), 'b');
  assert.equal(windowLayer(stack, 'a'), 1);
  assert.equal(windowLayer(stack, 'b'), 2);
});

await test('the top window is the one raised last, and there is none in an empty stack', () => {
  assert.equal(topWindow(EMPTY_WINDOW_STACK), null);
  const stack = raiseWindow(raiseWindow(raiseWindow(EMPTY_WINDOW_STACK, 'a'), 'b'), 'a');
  assert.equal(topWindow(stack), 'a');
  assert.equal(topWindow(dropWindow(stack, 'a')), 'b');
});
