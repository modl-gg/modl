import test from 'node:test';
import assert from 'node:assert/strict';

import { passwordLength, passwordProblem } from './password-policy.ts';

await test('passwords are measured in code points after NFKC without trimming', () => {
  assert.equal(passwordLength('\u{1F680}'.repeat(12)), 12);
  assert.equal(passwordLength('é'), 1);
  assert.equal(passwordLength('  padded  '), 10);
});

await test('only the 12 to 128 length rule applies', () => {
  assert.equal(passwordProblem('a'.repeat(11)), 'too-short');
  assert.equal(passwordProblem('a'.repeat(12)), null);
  assert.equal(passwordProblem('a'.repeat(128)), null);
  assert.equal(passwordProblem('a'.repeat(129)), 'too-long');
});
