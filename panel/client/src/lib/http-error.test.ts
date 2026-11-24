import test from 'node:test';
import assert from 'node:assert/strict';

import {
  ApiHttpError,
  apiErrorBodyCode,
  apiErrorBodyMessage,
  apiErrorCode,
  describeError,
  isRetryableHttpError,
  parseRetryAfterSeconds,
  serverErrorMessage,
} from './http-error.ts';

await test('an http error message carries the status and the response body', () => {
  assert.equal(new ApiHttpError(403, 'Forbidden', 'Insufficient permissions').message, '403: Insufficient permissions');
  assert.equal(new ApiHttpError(403, 'Forbidden', '').message, '403: Forbidden');
});

await test('rate limited requests are never retried', () => {
  assert.equal(isRetryableHttpError(new ApiHttpError(429, 'Too Many Requests', 'Rate limit exceeded')), false);
});

await test('server errors are retried', () => {
  assert.equal(isRetryableHttpError(new ApiHttpError(500, 'Internal Server Error', '')), true);
  assert.equal(isRetryableHttpError(new ApiHttpError(503, 'Service Unavailable', 'upstream unavailable')), true);
});

await test('authorization failures are not retried', () => {
  assert.equal(isRetryableHttpError(new ApiHttpError(403, 'Forbidden', 'Insufficient permissions')), false);
  assert.equal(isRetryableHttpError(new ApiHttpError(401, 'Unauthorized', '')), false);
});

await test('failures that carry no response status are treated as network failures and retried', () => {
  assert.equal(isRetryableHttpError(new TypeError('Failed to fetch')), true);
  assert.equal(isRetryableHttpError(new Error('HTTP 403: Forbidden')), true);
  assert.equal(isRetryableHttpError('offline'), true);
});

await test('a retry-after header in seconds is read, anything else is ignored', () => {
  assert.equal(parseRetryAfterSeconds('120'), 120);
  assert.equal(parseRetryAfterSeconds(' 7 '), 7);
  assert.equal(parseRetryAfterSeconds(null), null);
  assert.equal(parseRetryAfterSeconds(''), null);
  assert.equal(parseRetryAfterSeconds('Wed, 21 Oct 2026 07:28:00 GMT'), null);
  assert.equal(parseRetryAfterSeconds('-5'), null);
});

await test('an http error keeps the retry-after seconds it was given', () => {
  assert.equal(new ApiHttpError(429, 'Too Many Requests', '', 42).retryAfterSeconds, 42);
  assert.equal(new ApiHttpError(500, 'Internal Server Error', '').retryAfterSeconds, null);
});

await test('the machine code is read from a json error body', () => {
  const body = JSON.stringify({ status: 401, error: 'Wrong code', message: 'Wrong code', code: 'SETUP_CODE_INVALID' });
  assert.equal(apiErrorCode(new ApiHttpError(401, 'Unauthorized', body)), 'SETUP_CODE_INVALID');
});

await test('errors without a json body or a code have no machine code', () => {
  assert.equal(apiErrorCode(new ApiHttpError(429, 'Too Many Requests', 'Rate limit exceeded')), null);
  assert.equal(apiErrorCode(new ApiHttpError(400, 'Bad Request', JSON.stringify({ error: 'Bad input' }))), null);
  assert.equal(apiErrorCode(new ApiHttpError(400, 'Bad Request', JSON.stringify({ code: 7 }))), null);
  assert.equal(apiErrorCode(new ApiHttpError(400, 'Bad Request', 'null')), null);
  assert.equal(apiErrorCode(new TypeError('Failed to fetch')), null);
});

await test('an error body exposes its message and machine code', () => {
  assert.equal(apiErrorBodyMessage(JSON.stringify({ error: 'Bad input', message: '', code: 'INVALID_ARGUMENT' })), 'Bad input');
  assert.equal(apiErrorBodyMessage(JSON.stringify({ error: 'Old text', message: 'Bad input' })), 'Bad input');
  assert.equal(apiErrorBodyCode(JSON.stringify({ error: 'Bad input', code: 'INVALID_ARGUMENT' })), 'INVALID_ARGUMENT');
  assert.equal(apiErrorBodyMessage('not json'), null);
  assert.equal(apiErrorBodyCode('not json'), null);
});

await test('the server message is read only from http errors', () => {
  const body = JSON.stringify({ status: 403, error: 'Insufficient permissions', message: 'Insufficient permissions' });
  assert.equal(serverErrorMessage(new ApiHttpError(403, 'Forbidden', body)), 'Insufficient permissions');
  assert.equal(serverErrorMessage(new ApiHttpError(429, 'Too Many Requests', 'Rate limit exceeded')), null);
  assert.equal(serverErrorMessage(new Error('Failed')), null);
});

await test('an error is described by its server message, its own message, or the fallback', () => {
  const body = JSON.stringify({ message: 'Role is in use' });
  assert.equal(describeError(new ApiHttpError(409, 'Conflict', body), 'Could not delete'), 'Role is in use');
  assert.equal(describeError(new ApiHttpError(500, 'Internal Server Error', ''), 'Could not delete'), 'Could not delete');
  assert.equal(describeError(new Error('Upload too large'), 'Could not delete'), 'Upload too large');
  assert.equal(describeError(new Error(''), 'Could not delete'), 'Could not delete');
  assert.equal(describeError('offline', 'Could not delete'), 'Could not delete');
});
