import { useTranslation } from 'react-i18next';
import { Notice } from '@/components/ui/notice';
import {
  isKeyedProvider,
  type IpLookupProviderId,
  type LookupTestOutcome,
  type LookupTestResult as LookupTestResultData,
} from '@/hooks/data/player-addresses';

const OUTCOME_MESSAGES: Record<Exclude<LookupTestOutcome, 'working'>, string> = {
  noVerdict: 'settings.playerAddresses.testNoVerdict',
  refused: 'settings.playerAddresses.testRefused',
  dailyLimit: 'settings.playerAddresses.testDailyLimit',
  unexpectedAnswer: 'settings.playerAddresses.testUnexpected',
  unreachable: 'settings.playerAddresses.testUnreachable',
  busy: 'settings.playerAddresses.testBusy',
};

interface LookupTestResultProps {
  result: LookupTestResultData;
  provider: IpLookupProviderId;
}

export function LookupTestResult({ result, provider }: LookupTestResultProps) {
  const { t } = useTranslation();

  if (result.outcome !== 'working') {
    return <Notice variant="error" announce>{t(OUTCOME_MESSAGES[result.outcome])}</Notice>;
  }
  if (!result.keyChecked && isKeyedProvider(provider)) {
    return <p className="text-xs text-muted-foreground">{t('settings.playerAddresses.testKeyNotChecked')}</p>;
  }
  return null;
}
