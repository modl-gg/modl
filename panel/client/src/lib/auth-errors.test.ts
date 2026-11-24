import test from 'node:test';
import assert from 'node:assert/strict';

import { authFailureKey, authFailureOf } from './auth-errors.ts';

await test('machine codes choose the message, never the message text', () => {
  assert.equal(authFailureOf('INVALID_CREDENTIALS'), 'invalid-credentials');
  assert.equal(authFailureOf('PASSWORD_HASHING_BUSY'), 'busy');
  assert.equal(authFailureOf(null), null);
  assert.equal(authFailureKey('SOMETHING_ELSE', 'toast.error'), 'toast.error');
});
