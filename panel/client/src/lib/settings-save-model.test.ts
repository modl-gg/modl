import test from 'node:test';
import assert from 'node:assert/strict';

import { editDraft, sameValue, saveDirtySources, settleDraft, summarizeSources, type Loaded } from './settings-save-model.ts';

interface Snapshot {
  version: number;
  name: string;
}

const loadedOf = (snapshot: Snapshot): Loaded<Snapshot, string> => ({ snapshot, value: snapshot.name });

await test('values compare by content, ignoring absent optional fields', () => {
  assert.equal(sameValue({ a: 1, b: [1, { c: 'x' }] }, { b: [1, { c: 'x' }], a: 1 }), true);
  assert.equal(sameValue({ a: 1, b: undefined }, { a: 1 }), true);
  assert.equal(sameValue({ a: 1 }, { a: 2 }), false);
  assert.equal(sameValue([1, 2], [2, 1]), false);
  assert.equal(sameValue([], {}), false);
  assert.equal(sameValue(null, {}), false);
  assert.equal(sameValue('1', 1), false);
});

await test('an edit starts a draft on the loaded snapshot', () => {
  const loaded = loadedOf({ version: 3, name: 'Alpha' });
  assert.deepEqual(editDraft(null, 'Beta', loaded), { value: 'Beta', base: loaded.snapshot });
});

await test('a draft keeps the snapshot it started from', () => {
  const first = loadedOf({ version: 3, name: 'Alpha' });
  const draft = editDraft(null, 'Beta', first);
  const refreshed = loadedOf({ version: 4, name: 'Alpha' });
  assert.deepEqual(editDraft(draft, 'Gamma', refreshed), { value: 'Gamma', base: first.snapshot });
});

await test('reverting to the loaded value clears the draft', () => {
  const loaded = loadedOf({ version: 3, name: 'Alpha' });
  const draft = editDraft(null, 'Beta', loaded);
  assert.equal(editDraft(draft, 'Alpha', loaded), null);
});

await test('a saved draft settles, and an edit made while saving moves onto the saved snapshot', () => {
  const loaded = loadedOf({ version: 3, name: 'Alpha' });
  const saving = editDraft(null, 'Beta', loaded);
  assert.notEqual(saving, null);
  if (saving === null) return;
  const saved = loadedOf({ version: 4, name: 'Beta' });
  assert.equal(settleDraft(saving, saving, saved), null);
  const editedDuringSave = editDraft(saving, 'Gamma', loaded);
  assert.deepEqual(settleDraft(editedDuringSave, saving, saved), { value: 'Gamma', base: saved.snapshot });
  assert.equal(settleDraft(editDraft(saving, 'Gamma', loaded), saving, loadedOf({ version: 4, name: 'Gamma' })), null);
});

await test('reverting while a save is in flight survives the save', () => {
  const loaded = loadedOf({ version: 3, name: 'Alpha' });
  const saving = editDraft(null, 'Beta', loaded);
  assert.notEqual(saving, null);
  if (saving === null) return;
  const reverted = editDraft(saving, 'Alpha', loaded, true);
  assert.deepEqual(reverted, { value: 'Alpha', base: loaded.snapshot });
  const saved = loadedOf({ version: 4, name: 'Beta' });
  assert.deepEqual(settleDraft(reverted, saving, saved), { value: 'Alpha', base: saved.snapshot });
});

await test('a page is dirty when any source is, and valid only when every dirty source is', () => {
  assert.deepEqual(summarizeSources([]), { isDirty: false, isValid: true });
  assert.deepEqual(summarizeSources([{ isDirty: false, isValid: false }]), { isDirty: false, isValid: true });
  assert.deepEqual(summarizeSources([{ isDirty: true, isValid: true }, { isDirty: false, isValid: false }]), { isDirty: true, isValid: true });
  assert.deepEqual(summarizeSources([{ isDirty: true, isValid: true }, { isDirty: true, isValid: false }]), { isDirty: true, isValid: false });
});

await test('saving runs dirty sources in order and stops at the first failure', async () => {
  const calls: string[] = [];
  const source = (name: string, isDirty: boolean, succeeds: boolean) => ({
    status: { isDirty, isValid: true },
    save: async () => {
      calls.push(name);
      return Promise.resolve(succeeds);
    },
  });
  assert.equal(await saveDirtySources([source('a', true, true), source('b', false, true), source('c', true, false), source('d', true, true)]), false);
  assert.deepEqual(calls, ['a', 'c']);
  calls.length = 0;
  assert.equal(await saveDirtySources([source('a', true, true), source('b', true, true)]), true);
  assert.deepEqual(calls, ['a', 'b']);
});
