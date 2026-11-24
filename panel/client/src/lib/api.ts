import { ApiHttpError, apiErrorBodyCode, parseRetryAfterSeconds } from './http-error';
import { attachSessionKey, observeSessionResponse } from './panel-session';

export function getAvatarUrl(uuid: string, size = 32, _overlay = true): string {
  return `https://mc-heads.net/avatar/${uuid}/${size}`;
}

export type RequestMethod = 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';

export type RateLimitFeedback = 'toast' | 'caller';

export interface ApiRequestOptions extends Omit<RequestInit, 'method' | 'body' | 'credentials'> {
  method?: RequestMethod;
  body?: unknown;
  rateLimitFeedback?: RateLimitFeedback;
}

function isBinaryBody(body: unknown): body is FormData | Blob | URLSearchParams {
  return body instanceof FormData || body instanceof Blob || body instanceof URLSearchParams;
}

function encodeBody(body: unknown): BodyInit | null {
  if (body === undefined || body === null) return null;
  if (typeof body === 'string' || isBinaryBody(body)) return body;
  return JSON.stringify(body);
}

function createHeaders(init: HeadersInit | undefined, body: unknown): Headers {
  const headers = new Headers(init);
  const sendsJson = body !== undefined && body !== null && !isBinaryBody(body);
  if (sendsJson && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json');
  }
  return headers;
}

const GENERIC_RATE_LIMIT_CODE = 'RATE_LIMITED';

function isGenericRateLimit(bodyText: string): boolean {
  const code = apiErrorBodyCode(bodyText);
  return code === null || code === GENERIC_RATE_LIMIT_CODE;
}

async function handleRateLimitIfNeeded(response: Response, feedback: RateLimitFeedback): Promise<void> {
  if (response.status !== 429) return;
  const bodyText = await response.clone().text().catch(() => '');
  if (feedback === 'toast' && isGenericRateLimit(bodyText)) {
    const { handleRateLimitResponse } = await import('../utils/rate-limit-handler');
    await handleRateLimitResponse(response);
  }
  throw new ApiHttpError(
    response.status,
    response.statusText,
    bodyText || 'Rate limit exceeded',
    parseRetryAfterSeconds(response.headers.get('Retry-After')),
  );
}

function normalizePath(path: string): string {
  return path.startsWith('/') ? path : `/${path}`;
}

export async function apiFetch(path: string, options: ApiRequestOptions = {}): Promise<Response> {
  const { method = 'GET', body, rateLimitFeedback = 'toast', ...init } = options;
  const requestPath = normalizePath(path);
  const headers = createHeaders(init.headers, body);
  const sentSessionKey = attachSessionKey(requestPath, headers);

  const response = await fetch(requestPath, {
    ...init,
    method,
    headers,
    credentials: 'same-origin',
    body: encodeBody(body),
  });

  await observeSessionResponse(method, requestPath, response, sentSessionKey);
  await handleRateLimitIfNeeded(response, rateLimitFeedback);
  return response;
}
