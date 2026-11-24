import test from 'node:test';
import assert from 'node:assert/strict';

import { ApiHttpError } from './http-error.ts';
import { rateLimitAwareRetryDelay, retriesAfterRateLimit } from './query-retry.ts';

const rateLimited = (retryAfterSeconds: number | null) => new ApiHttpError(429, 'Too Many Requests', '', retryAfterSeconds);

await test('a rate limited request is retried a few times after the requested wait', () => {
  assert.equal(retriesAfterRateLimit(0, rateLimited(4)), true);
  assert.equal(retriesAfterRateLimit(2, rateLimited(4)), true);
  assert.equal(retriesAfterRateLimit(3, rateLimited(4)), false);
  assert.equal(rateLimitAwareRetryDelay(0, rateLimited(4)), 4_000);
});

await test('the rate limit wait is capped and has a default', () => {
  assert.equal(rateLimitAwareRetryDelay(0, rateLimited(600)), 30_000);
  assert.equal(rateLimitAwareRetryDelay(0, rateLimited(null)), 5_000);
});

await test('transient failures are retried once and client errors never', () => {
  assert.equal(retriesAfterRateLimit(0, new TypeError('network')), true);
  assert.equal(retriesAfterRateLimit(0, new ApiHttpError(503, 'Unavailable', '')), true);
  assert.equal(retriesAfterRateLimit(1, new ApiHttpError(503, 'Unavailable', '')), false);
  assert.equal(retriesAfterRateLimit(0, new ApiHttpError(403, 'Forbidden', '')), false);
  assert.equal(retriesAfterRateLimit(0, new ApiHttpError(400, 'Bad Request', '')), false);
});
