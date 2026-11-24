import { useTranslation } from 'react-i18next';
import { Notice } from '@/components/ui/notice';
import type { AiTestResult } from '@/hooks/data/ai';
import { checkPassed, type CheckOutcome } from '@/hooks/use-check-outcome';
import { aiErrorMessageKey, FAILURE_MESSAGES } from './ai-messages';

export function aiConnectionTestPassed(outcome: CheckOutcome<AiTestResult> | null, verified: boolean): boolean | null {
  if (outcome === null) return verified ? true : null;
  return checkPassed(outcome, (result) => result.success);
}

function failureKey(outcome: CheckOutcome<AiTestResult>): string | null {
  if (outcome.kind === 'error') return aiErrorMessageKey(outcome.error, 'settings.ai.failure.providerError');
  return outcome.result.success ? null : FAILURE_MESSAGES[outcome.result.failure ?? 'providerError'];
}

export function AiConnectionTestFailure({ outcome }: { outcome: CheckOutcome<AiTestResult> | null }) {
  const { t } = useTranslation();
  const key = outcome === null ? null : failureKey(outcome);
  if (key === null) return null;
  return <Notice variant="error" announce>{t(key)}</Notice>;
}
