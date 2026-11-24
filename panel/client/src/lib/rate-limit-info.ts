import { z } from 'zod';
import { parseJsonOrNull } from '@/lib/http-error';

const RATE_LIMIT_INFO_KEY = 'rateLimitInfo';
const PRE_RATE_LIMIT_PATH_KEY = 'preRateLimitPath';

const optionalText = z.string().min(1).optional().catch(undefined);

const rateLimitInfoSchema = z.object({
  retryAfter: z.number().positive().optional().catch(undefined),
  rateLimit: optionalText,
  nextAttemptAt: optionalText,
  message: optionalText,
  securityNote: optionalText,
});

export type RateLimitInfo = z.infer<typeof rateLimitInfoSchema>;

function readSessionItem(key: string): string | null {
  try {
    return sessionStorage.getItem(key);
  } catch {
    return null;
  }
}

function removeSessionItem(key: string): void {
  try {
    sessionStorage.removeItem(key);
  } catch {
    return;
  }
}

export function readRateLimitInfo(): RateLimitInfo {
  const stored = readSessionItem(RATE_LIMIT_INFO_KEY);
  if (stored === null) return {};
  return rateLimitInfoSchema.safeParse(parseJsonOrNull(stored)).data ?? {};
}

export function rateLimitDeadline(info: RateLimitInfo, now: number): number | null {
  if (info.nextAttemptAt !== undefined) {
    const nextAttempt = new Date(info.nextAttemptAt).getTime();
    return Number.isNaN(nextAttempt) ? null : nextAttempt;
  }
  return info.retryAfter === undefined ? null : now + info.retryAfter * 1000;
}

export function takePreRateLimitPath(): string | null {
  const path = readSessionItem(PRE_RATE_LIMIT_PATH_KEY);
  clearRateLimitInfo();
  return path;
}

export function clearRateLimitInfo(): void {
  removeSessionItem(RATE_LIMIT_INFO_KEY);
  removeSessionItem(PRE_RATE_LIMIT_PATH_KEY);
}
