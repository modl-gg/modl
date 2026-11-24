import test from 'node:test';
import assert from 'node:assert/strict';

import { withoutReasonCopy } from './punishment-notes.ts';

const note = (text: string) => ({ text });

await test('a first note that repeats the reason is not shown again', () => {
  assert.deepEqual(withoutReasonCopy([note('Cheating'), note('informant is Alex')], 'Cheating'), [note('informant is Alex')]);
});

await test('notes stay when the reason differs, is missing, or only a later note repeats it', () => {
  assert.deepEqual(withoutReasonCopy([note('informant is Alex')], 'Cheating'), [note('informant is Alex')]);
  assert.deepEqual(withoutReasonCopy([note('Cheating')], undefined), [note('Cheating')]);
  assert.deepEqual(withoutReasonCopy([note('first'), note('Cheating')], 'Cheating'), [note('first'), note('Cheating')]);
});
