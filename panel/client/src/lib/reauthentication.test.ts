import test from 'node:test';
import assert from 'node:assert/strict';

import { authenticatorOperation, passwordOperation, proofKindFor } from './reauthentication.ts';

const BOTH = { totpEnabled: true, hasPassword: true };
const AUTHENTICATOR_ONLY = { totpEnabled: true, hasPassword: false };
const PASSWORD_ONLY = { totpEnabled: false, hasPassword: true };
const NEITHER = { totpEnabled: false, hasPassword: false };

await test('changing an existing password asks for the current password', () => {
  assert.equal(passwordOperation(BOTH), 'change-password');
  assert.equal(proofKindFor(passwordOperation(BOTH), BOTH), 'password');
  assert.equal(proofKindFor(passwordOperation(PASSWORD_ONLY), PASSWORD_ONLY), 'password');
});

await test('setting a first password is a new credential, proven by the authenticator when there is one', () => {
  assert.equal(passwordOperation(AUTHENTICATOR_ONLY), 'add-credential');
  assert.equal(proofKindFor(passwordOperation(AUTHENTICATOR_ONLY), AUTHENTICATOR_ONLY), 'code');
});

await test('a new credential is proven by the authenticator first, else the password', () => {
  assert.equal(proofKindFor('add-credential', BOTH), 'code');
  assert.equal(proofKindFor('add-credential', AUTHENTICATOR_ONLY), 'code');
  assert.equal(proofKindFor('add-credential', PASSWORD_ONLY), 'password');
});

await test('managing an enabled authenticator needs its code, enabling the first one is a new credential', () => {
  assert.equal(authenticatorOperation(BOTH), 'manage-totp');
  assert.equal(proofKindFor('manage-totp', BOTH), 'code');
  assert.equal(authenticatorOperation(PASSWORD_ONLY), 'add-credential');
  assert.equal(proofKindFor(authenticatorOperation(PASSWORD_ONLY), PASSWORD_ONLY), 'password');
});

await test('an email change asks for the authenticator first, then the password, then a code sent to the current address', () => {
  assert.equal(proofKindFor('change-email', BOTH), 'code');
  assert.equal(proofKindFor('change-email', PASSWORD_ONLY), 'password');
  assert.equal(proofKindFor('change-email', NEITHER), 'email-code');
});
