import test from 'node:test';
import assert from 'node:assert/strict';

import { positiveWholeNumberOf } from './whole-number.ts';

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
