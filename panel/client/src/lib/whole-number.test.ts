import test from 'node:test';
import assert from 'node:assert/strict';

import { positiveWholeNumberOf, wholeNumberInRange } from './whole-number.ts';

await test('an empty or partial entry is no number, never zero', () => {
  assert.equal(positiveWholeNumberOf(''), null);
  assert.equal(positiveWholeNumberOf('  '), null);
  assert.equal(positiveWholeNumberOf('-'), null);
});

await test('only whole numbers of at least one are accepted', () => {
  assert.equal(positiveWholeNumberOf('0'), null);
  assert.equal(positiveWholeNumberOf('-3'), null);
  assert.equal(positiveWholeNumberOf('1.5'), null);
  assert.equal(positiveWholeNumberOf('1e3'), null);
  assert.equal(positiveWholeNumberOf('99999999999999999999'), null);
  assert.equal(positiveWholeNumberOf(' 12 '), 12);
  assert.equal(positiveWholeNumberOf('1'), 1);
});

await test('a ranged whole number accepts only digits inside the bounds', () => {
  const range = { min: 10, max: 300 };
  assert.equal(wholeNumberInRange('', range), null);
  assert.equal(wholeNumberInRange('9', range), null);
  assert.equal(wholeNumberInRange('301', range), null);
  assert.equal(wholeNumberInRange('2.5', range), null);
  assert.equal(wholeNumberInRange(' 10 ', range), 10);
  assert.equal(wholeNumberInRange('300', range), 300);
});
