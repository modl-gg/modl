import test from 'node:test';
import assert from 'node:assert/strict';
import { formatList } from './list-format.ts';

await test('lists join with the language conjunction', () => {
  assert.equal(formatList(['GrimAC', 'Polar', 'Vulcan'], 'en'), 'GrimAC, Polar, and Vulcan');
  assert.equal(formatList(['GrimAC', 'Vulcan'], 'en'), 'GrimAC and Vulcan');
  assert.equal(formatList(['GrimAC', 'Polar', 'Vulcan'], 'de'), 'GrimAC, Polar und Vulcan');
  assert.equal(formatList(['Polar'], 'en'), 'Polar');
  assert.equal(formatList([], 'en'), '');
});
