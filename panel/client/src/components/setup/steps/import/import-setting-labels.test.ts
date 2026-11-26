import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { NAMED_SETTINGS } from './import-setting-labels.ts';

const IMPORTED_SETTINGS = fileURLToPath(
  new URL('../../../../../../../proto/src/testFixtures/resources/gg/modl/proto/fixtures/hosted-import-settings.txt', import.meta.url),
);

await test('every setting a modl.gg import can name has a panel label', () => {
  const imported = readFileSync(IMPORTED_SETTINGS, 'utf8').split('\n').filter((line) => line !== '');
  assert.deepEqual([...NAMED_SETTINGS].sort(), [...imported].sort());
});
