import { ApiHttpError, isRateLimitedError, isRetryableHttpError } from './http-error.ts';

const MAX_RATE_LIMIT_RETRIES = 3;
const MAX_TRANSIENT_RETRIES = 1;
const DEFAULT_RATE_LIMIT_WAIT_SECONDS = 5;
const MAX_RATE_LIMIT_WAIT_SECONDS = 30;
const BASE_BACKOFF_MS = 1_000;
const MAX_BACKOFF_MS = 30_000;
const MILLIS_PER_SECOND = 1_000;

function rateLimitWaitSeconds(error: unknown): number {
  const requested = error instanceof ApiHttpError ? error.retryAfterSeconds : null;
  return Math.min(requested ?? DEFAULT_RATE_LIMIT_WAIT_SECONDS, MAX_RATE_LIMIT_WAIT_SECONDS);
}

export function retriesAfterRateLimit(failureCount: number, error: unknown): boolean {
  if (isRateLimitedError(error)) return failureCount < MAX_RATE_LIMIT_RETRIES;
  return failureCount < MAX_TRANSIENT_RETRIES && isRetryableHttpError(error);
}

export function rateLimitAwareRetryDelay(failureCount: number, error: unknown): number {
  if (isRateLimitedError(error)) return rateLimitWaitSeconds(error) * MILLIS_PER_SECOND;
  return Math.min(BASE_BACKOFF_MS * 2 ** failureCount, MAX_BACKOFF_MS);
}

export const RATE_LIMIT_AWARE_RETRY = {
  retry: retriesAfterRateLimit,
  retryDelay: rateLimitAwareRetryDelay,
} as const;
