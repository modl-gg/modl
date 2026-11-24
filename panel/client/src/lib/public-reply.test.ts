import test from 'node:test';
import assert from 'node:assert/strict';

import { ApiHttpError } from './http-error.ts';
import { isThreadFullError, TICKET_THREAD_FULL_CODE } from './public-reply.ts';

await test('a full conversation is recognised by its machine code', () => {
  const full = new ApiHttpError(409, 'Conflict', JSON.stringify({ code: TICKET_THREAD_FULL_CODE, message: 'full' }));
  const locked = new ApiHttpError(409, 'Conflict', JSON.stringify({ code: 'CONFLICT', message: 'locked' }));

  assert.equal(isThreadFullError(full), true);
  assert.equal(isThreadFullError(locked), false);
  assert.equal(isThreadFullError(new TypeError('Failed to fetch')), false);
});
