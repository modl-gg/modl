import test from 'node:test';
import assert from 'node:assert/strict';

import {
  EMPTY_NEW_PASSWORD_ENTRY,
  isMismatchShown,
  isNewPasswordEntryReady,
  nextNewPasswordEntry,
  shownPasswordProblem,
  type NewPasswordEntry,
  type NewPasswordEvent,
} from './new-password-entry.ts';

const VALID = 'correct horse battery';

function play(...events: NewPasswordEvent[]): NewPasswordEntry {
  return events.reduce(nextNewPasswordEntry, EMPTY_NEW_PASSWORD_ENTRY);
}

function typePassword(value: string): NewPasswordEvent {
  return { type: 'password-changed', value };
}

function typeConfirmation(value: string): NewPasswordEvent {
  return { type: 'confirmation-changed', value };
}

const ENTER: NewPasswordEvent = { type: 'enter-pressed' };

await test('a short password shows no error while it is being typed', () => {
  const entry = play(typePassword('a'), typePassword('ab'), typePassword('abc'));
  assert.equal(shownPasswordProblem(entry), null);
});

await test('leaving the password field reveals the length error', () => {
  const entry = play(typePassword('abc'), { type: 'password-left' });
  assert.equal(shownPasswordProblem(entry), 'too-short');
});

await test('leaving an empty password field shows nothing', () => {
  assert.equal(shownPasswordProblem(play({ type: 'password-left' })), null);
});

await test('typing again returns the length rule to the neutral hint', () => {
  const entry = play(typePassword('abc'), { type: 'password-left' }, typePassword('abcd'));
  assert.equal(shownPasswordProblem(entry), null);
});

await test('pressing Enter reveals every problem, empty fields included', () => {
  const entry = play(ENTER);
  assert.equal(shownPasswordProblem(entry), 'too-short');
  assert.equal(isMismatchShown(entry), false);
  const typed = play(typePassword(VALID), ENTER);
  assert.equal(shownPasswordProblem(typed), null);
  assert.equal(isMismatchShown(typed), true);
});

await test('leaving an empty password field after pressing Enter keeps every problem shown', () => {
  const entry = play(ENTER, { type: 'password-left' });
  assert.equal(shownPasswordProblem(entry), 'too-short');
  const typed = play(typePassword(VALID), ENTER, { type: 'confirmation-entered' }, { type: 'confirmation-left' });
  assert.equal(isMismatchShown(typed), true);
});

await test('an over-long password is reported after leaving the field', () => {
  const entry = play(typePassword('a'.repeat(129)), { type: 'password-left' });
  assert.equal(shownPasswordProblem(entry), 'too-long');
});

await test('the mismatch never shows while the focused confirmation is shorter than the password', () => {
  const typing = play(
    typePassword(VALID),
    { type: 'password-left' },
    { type: 'confirmation-entered' },
    typeConfirmation('correct'),
  );
  assert.equal(isMismatchShown(typing), false);
  assert.equal(isMismatchShown(nextNewPasswordEntry(typing, ENTER)), false);
});

await test('a wrong confirmation shows the mismatch after leaving the field', () => {
  const entry = play(
    typePassword(VALID),
    { type: 'confirmation-entered' },
    typeConfirmation('correct horse battery!'),
    { type: 'confirmation-left' },
  );
  assert.equal(isMismatchShown(entry), true);
});

await test('a focused confirmation as long as the password shows the mismatch when Enter is pressed', () => {
  const entry = play(
    typePassword(VALID),
    { type: 'confirmation-entered' },
    typeConfirmation('correct horse batterx'),
    ENTER,
  );
  assert.equal(isMismatchShown(entry), true);
});

await test('editing the password hides the mismatch until a field is left again', () => {
  const left = play(
    typePassword(VALID),
    { type: 'confirmation-entered' },
    typeConfirmation('something else entirely'),
    { type: 'confirmation-left' },
  );
  const editing = nextNewPasswordEntry(left, typePassword('something else'));
  assert.equal(isMismatchShown(editing), false);
  assert.equal(isMismatchShown(nextNewPasswordEntry(editing, { type: 'password-left' })), true);
});

await test('a fixed mismatch disappears at once', () => {
  const entry = play(
    typePassword(VALID),
    { type: 'confirmation-entered' },
    typeConfirmation('wrong wrong wrong wrong'),
    { type: 'confirmation-left' },
  );
  assert.equal(isMismatchShown(entry), true);
  const fixed = nextNewPasswordEntry(entry, typePassword('wrong wrong wrong wrong'));
  assert.equal(isMismatchShown(fixed), false);
  assert.equal(isNewPasswordEntryReady(fixed), true);
});

await test('clearing returns to the empty entry', () => {
  const entry = play(typePassword(VALID), ENTER, { type: 'cleared' });
  assert.deepEqual(entry, EMPTY_NEW_PASSWORD_ENTRY);
});
