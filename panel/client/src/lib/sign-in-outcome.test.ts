import test from 'node:test';
import assert from 'node:assert/strict';

import { acceptsCode, acceptsPasskey, signInOutcomeOf } from './sign-in-outcome.ts';

await test('a challenge wins over a missing session', () => {
  const passkeyOnly = { challenge: 'token', email: 'mod@example.com', options: ['passkey' as const] };

  assert.equal(signInOutcomeOf(false, passkeyOnly), 'challenge');
  assert.equal(signInOutcomeOf(true, null), 'signed-in');
  assert.equal(signInOutcomeOf(false, null), 'failed');
  assert.equal(acceptsCode(passkeyOnly), false);
  assert.equal(acceptsPasskey(passkeyOnly), true);
});
