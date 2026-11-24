import test from 'node:test';
import assert from 'node:assert/strict';

import { ApiHttpError } from './http-error.ts';
import {
  classifySetupCodeRejection,
  isValidSetupCode,
  normalizeSetupCode,
  retryDeadlineOf,
} from './setup-code.ts';

const errorBody = (status: number, code?: string) => JSON.stringify({ status, error: 'Rejected', message: 'Rejected', code });

await test('a setup code is normalised to upper case without separators', () => {
  assert.equal(normalizeSetupCode('abcd-efgh'), 'ABCDEFGH');
  assert.equal(normalizeSetupCode(' ab cd\tef-gh '), 'ABCDEFGH');
  assert.equal(normalizeSetupCode('ABCD--EFGH'), 'ABCDEFGH');
});

await test('a setup code is valid once normalised, in either printed or bare form', () => {
  assert.equal(isValidSetupCode('ABCD-EFGH'), true);
  assert.equal(isValidSetupCode('abcdefgh'), true);
  assert.equal(isValidSetupCode('  wxyz 2345 '), true);
});

await test('setup codes outside the alphabet or of the wrong length are rejected', () => {
  assert.equal(isValidSetupCode(''), false);
  assert.equal(isValidSetupCode('ABCD-EFG'), false);
  assert.equal(isValidSetupCode('ABCD-EFGHJ'), false);
  assert.equal(isValidSetupCode('ABCD-EFG1'), false);
  assert.equal(isValidSetupCode('ABCD-EFGO'), false);
  assert.equal(isValidSetupCode('ABCD-EFGI'), false);
  assert.equal(isValidSetupCode('ABCD-EFG0'), false);
  assert.equal(isValidSetupCode('ABCD_EFGH'), false);
});

await test('a rejected setup code is classified by its machine code', () => {
  assert.deepEqual(classifySetupCodeRejection(new ApiHttpError(401, 'Unauthorized', errorBody(401, 'SETUP_CODE_INVALID'))), { kind: 'invalid-code' });
  assert.deepEqual(classifySetupCodeRejection(new ApiHttpError(401, 'Unauthorized', errorBody(401, 'SETUP_CODE_LOCKED'))), { kind: 'locked' });
  assert.deepEqual(classifySetupCodeRejection(new ApiHttpError(401, 'Unauthorized', errorBody(401, 'SETUP_CODE_EXPIRED'))), { kind: 'expired' });
  assert.deepEqual(classifySetupCodeRejection(new ApiHttpError(401, 'Unauthorized', '')), { kind: 'invalid-code' });
});

await test('a claim or sign-in that no longer applies to the phase is reported as a phase change', () => {
  assert.deepEqual(classifySetupCodeRejection(new ApiHttpError(409, 'Conflict', errorBody(409, 'SETUP_ALREADY_CLAIMED'))), { kind: 'phase-changed' });
  assert.deepEqual(classifySetupCodeRejection(new ApiHttpError(409, 'Conflict', errorBody(409, 'SETUP_SIGN_IN_NOT_AVAILABLE'))), { kind: 'phase-changed' });
});

await test('a rate limited attempt carries the wait from the retry-after header', () => {
  assert.deepEqual(classifySetupCodeRejection(new ApiHttpError(429, 'Too Many Requests', 'Rate limit exceeded', 30)), { kind: 'cooldown', retryAfterSeconds: 30 });
  assert.deepEqual(classifySetupCodeRejection(new ApiHttpError(429, 'Too Many Requests', 'Rate limit exceeded')), { kind: 'cooldown', retryAfterSeconds: null });
});

await test('rejected owner details are told apart from a rejected code', () => {
  assert.deepEqual(classifySetupCodeRejection(new ApiHttpError(400, 'Bad Request', errorBody(400, 'INVALID_ARGUMENT'))), { kind: 'invalid-input' });
});

await test('only a cooldown with a known wait has a retry deadline', () => {
  assert.equal(retryDeadlineOf({ kind: 'cooldown', retryAfterSeconds: 30 }, 1_000), 31_000);
  assert.equal(retryDeadlineOf({ kind: 'cooldown', retryAfterSeconds: null }, 1_000), null);
  assert.equal(retryDeadlineOf({ kind: 'locked' }, 1_000), null);
});

await test('other failures are generic', () => {
  assert.deepEqual(classifySetupCodeRejection(new ApiHttpError(500, 'Internal Server Error', '')), { kind: 'failed' });
  assert.deepEqual(classifySetupCodeRejection(new TypeError('Failed to fetch')), { kind: 'failed' });
});

