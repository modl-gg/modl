import test from 'node:test';
import assert from 'node:assert/strict';

import { newSettingsEntryId } from './settings-ids.ts';

await test('entry ids keep their prefix, fit the 64 character id limit and do not repeat', () => {
  const ids = new Set(Array.from({ length: 200 }, () => newSettingsEntryId('section')));
  assert.equal(ids.size, 200);
  for (const id of ids) {
    assert.match(id, /^section_[0-9a-f]{24}$/);
    assert.ok(id.length <= 64);
  }
});
