import test from 'node:test';
import assert from 'node:assert/strict';
import { createLabelDraftStore, EMPTY_DRAFTS, hasUnsubmittedLabels, labelReducer, labelsToSubmit, type LabelAction, type LabelDrafts } from './label-drafts.ts';

const subjects = [{ uuid: 'a', name: 'Alex' }, { uuid: 'b', name: 'Bob' }];
const apply = (actions: readonly LabelAction[]): LabelDrafts => actions.reduce(labelReducer, EMPTY_DRAFTS);

await test('nothing is submitted until a verdict is chosen', () => {
  assert.equal(labelsToSubmit(EMPTY_DRAFTS, subjects), null);
  const drafts = apply([{ kind: 'verdict', uuid: 'b', verdict: 'legit' }]);
  assert.deepEqual(labelsToSubmit(drafts, subjects)?.map((label) => label.uuid), ['b']);
});

await test('cheating needs complete ranges for every selected cheat', () => {
  const started = apply([
    { kind: 'verdict', uuid: 'a', verdict: 'cheating' },
    { kind: 'toggle-cheat', uuid: 'a', cheat: 'aim' },
  ]);
  assert.equal(labelsToSubmit(started, subjects), null);
  const rangeId = started.players['a']?.cheats[0]?.ranges[0]?.id ?? -1;
  const complete = apply([
    { kind: 'verdict', uuid: 'a', verdict: 'cheating' },
    { kind: 'toggle-cheat', uuid: 'a', cheat: 'aim' },
    { kind: 'set-range', uuid: 'a', cheat: 'aim', id: rangeId, edge: 'start', timeMs: 1000 },
    { kind: 'set-range', uuid: 'a', cheat: 'aim', id: rangeId, edge: 'end', timeMs: 4000 },
  ]);
  const labels = labelsToSubmit(complete, subjects);
  assert.deepEqual(labels?.[0]?.cheats, [{ type: 'aim', timeRanges: [{ startMs: 1000, endMs: 4000 }] }]);
});

await test('choosing the same verdict again clears it', () => {
  const drafts = apply([{ kind: 'verdict', uuid: 'a', verdict: 'unsure' }, { kind: 'verdict', uuid: 'a', verdict: 'unsure' }]);
  assert.equal(drafts.players['a']?.verdict, null);
});

await test('only a chosen verdict counts as unsubmitted work, and reset clears it', () => {
  assert.equal(hasUnsubmittedLabels(EMPTY_DRAFTS), false);
  assert.equal(hasUnsubmittedLabels(apply([{ kind: 'verdict', uuid: 'a', verdict: 'unsure' }, { kind: 'verdict', uuid: 'a', verdict: 'unsure' }])), false);
  const chosen = apply([{ kind: 'verdict', uuid: 'a', verdict: 'legit' }]);
  assert.equal(hasUnsubmittedLabels(chosen), true);
  assert.equal(hasUnsubmittedLabels(labelReducer(chosen, { kind: 'reset' })), false);
});

await test('the draft store applies actions and tells its subscribers', () => {
  const store = createLabelDraftStore();
  let changes = 0;
  const unsubscribe = store.subscribe(() => { changes += 1; });
  store.dispatch({ kind: 'verdict', uuid: 'a', verdict: 'legit' });
  assert.equal(hasUnsubmittedLabels(store.snapshot()), true);
  unsubscribe();
  store.dispatch({ kind: 'reset' });
  assert.equal(changes, 1);
  assert.equal(store.snapshot(), EMPTY_DRAFTS);
});
