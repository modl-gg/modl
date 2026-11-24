import { apiErrorCode } from './http-error.ts';

export const MAX_PUBLIC_REPLY_CHARACTERS = 10_000;
export const TICKET_THREAD_FULL_CODE = 'TICKET_THREAD_FULL';

export function isThreadFullError(error: unknown): boolean {
  return apiErrorCode(error) === TICKET_THREAD_FULL_CODE;
}
