import { useCallback } from 'react';
import { useTranslation } from 'react-i18next';
import { authFailureKey } from '@/lib/auth-errors';
import { ApiHttpError, apiErrorCode } from '@/lib/http-error';

const SECONDS_PER_MINUTE = 60;

function minutesUntilRetry(error: unknown): number {
  const seconds = error instanceof ApiHttpError ? error.retryAfterSeconds : null;
  return Math.max(1, Math.ceil((seconds ?? SECONDS_PER_MINUTE) / SECONDS_PER_MINUTE));
}

export function useAuthFailureMessage(): (error: unknown) => string {
  const { t } = useTranslation();
  return useCallback(
    (error: unknown) => t(authFailureKey(apiErrorCode(error), 'toast.error'), { minutes: minutesUntilRetry(error) }),
    [t],
  );
}
