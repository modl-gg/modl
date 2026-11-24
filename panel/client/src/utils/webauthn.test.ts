import test from 'node:test';
import assert from 'node:assert/strict';
import { WebAuthnError } from '@simplewebauthn/browser';

import { isWebAuthnCancellation } from './webauthn.ts';

await test('a browser cancellation wrapped by simplewebauthn counts as a cancellation', () => {
  const cause = new Error('The operation either timed out or was not allowed.');
  cause.name = 'NotAllowedError';
  assert.equal(isWebAuthnCancellation(new WebAuthnError({ message: cause.message, code: 'ERROR_PASSTHROUGH_SEE_CAUSE_PROPERTY', cause })), true);
});

await test('an aborted ceremony counts as a cancellation', () => {
  assert.equal(isWebAuthnCancellation(new DOMException('aborted', 'AbortError')), true);
});

await test('other failures are not cancellations', () => {
  assert.equal(isWebAuthnCancellation(new DOMException('bad state', 'InvalidStateError')), false);
  assert.equal(isWebAuthnCancellation('NotAllowedError'), false);
});
