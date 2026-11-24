import { z } from 'zod';

export class ApiHttpError extends Error {
  readonly status: number;
  readonly statusText: string;
  readonly bodyText: string;
  readonly retryAfterSeconds: number | null;

  constructor(status: number, statusText: string, bodyText: string, retryAfterSeconds: number | null = null) {
    super(`${status}: ${bodyText || statusText}`);
    this.name = 'ApiHttpError';
    this.status = status;
    this.statusText = statusText;
    this.bodyText = bodyText;
    this.retryAfterSeconds = retryAfterSeconds;
  }
}

const DELTA_SECONDS = /^\d+$/;

function statusOf(error: unknown): number | null {
  return error instanceof ApiHttpError ? error.status : null;
}

export function isRetryableHttpError(error: unknown): boolean {
  const status = statusOf(error);
  return status === null || status >= 500;
}

export function isRateLimitedError(error: unknown): boolean {
  return statusOf(error) === 429;
}

export function isConflictError(error: unknown): boolean {
  return statusOf(error) === 409;
}

export function parseRetryAfterSeconds(header: string | null): number | null {
  const value = header?.trim() ?? '';
  return DELTA_SECONDS.test(value) ? Number(value) : null;
}

const errorBodyText = z.string().min(1).optional().catch(undefined);

const apiErrorBodySchema = z.object({
  code: errorBodyText,
  error: errorBodyText,
  message: errorBodyText,
});

type ApiErrorBody = z.infer<typeof apiErrorBodySchema>;

export function parseJsonOrNull(text: string): unknown {
  try {
    const parsed: unknown = JSON.parse(text);
    return parsed;
  } catch {
    return null;
  }
}

function parseApiErrorBody(bodyText: string): ApiErrorBody | null {
  return apiErrorBodySchema.safeParse(parseJsonOrNull(bodyText)).data ?? null;
}

export function apiErrorBodyMessage(bodyText: string): string | null {
  const body = parseApiErrorBody(bodyText);
  return body?.message ?? body?.error ?? null;
}

export async function readResponseErrorMessage(response: Response): Promise<string | null> {
  return apiErrorBodyMessage(await response.text().catch(() => ''));
}

export function serverErrorMessage(error: unknown): string | null {
  return error instanceof ApiHttpError ? apiErrorBodyMessage(error.bodyText) : null;
}

export function describeError(error: unknown, fallback: string): string {
  if (error instanceof ApiHttpError) return serverErrorMessage(error) ?? fallback;
  return error instanceof Error && error.message ? error.message : fallback;
}

export function apiErrorBodyCode(bodyText: string): string | null {
  return parseApiErrorBody(bodyText)?.code ?? null;
}

export function apiErrorCode(error: unknown): string | null {
  return error instanceof ApiHttpError ? apiErrorBodyCode(error.bodyText) : null;
}
