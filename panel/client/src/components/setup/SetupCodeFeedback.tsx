import { useTranslation } from 'react-i18next';
import { Link } from 'wouter';
import { Button } from '@/components/ui/button';
import { Notice } from '@/components/ui/notice';
import { formatCountdown, useSecondsRemaining } from '@/hooks/use-seconds-remaining';
import type { SetupCodeRejection } from '@/lib/setup-code';
import { PANEL_SIGN_IN_PATH } from '@/lib/return-path';

interface CooldownMessageProps {
  retryAt: number | null;
  waitingKey: string;
  unknownWaitKey: string;
}

function CooldownMessage({ retryAt, waitingKey, unknownWaitKey }: CooldownMessageProps) {
  const { t } = useTranslation();
  const secondsLeft = useSecondsRemaining(retryAt);
  const waiting = retryAt === null || secondsLeft > 0;
  const announcement = waiting ? t(unknownWaitKey) : t('pages.setup.code.tryAgainNow');

  return (
    <>
      {retryAt !== null && (
        <span role="timer" aria-hidden={!waiting}>
          {waiting ? t(waitingKey, { time: formatCountdown(secondsLeft) }) : announcement}
        </span>
      )}
      <span role="status" className={retryAt === null ? undefined : 'sr-only'}>{announcement}</span>
    </>
  );
}

const REJECTION_MESSAGES: Record<'locked' | 'expired' | 'failed', string> = {
  locked: 'pages.setup.code.locked',
  expired: 'pages.setup.code.expired',
  failed: 'pages.setup.code.failed',
};

interface SetupCodeRejectionNoticeProps {
  rejection: SetupCodeRejection;
  retryAt: number | null;
  detail: string | null;
}

export function SetupCodeRejectionNotice({ rejection, retryAt, detail }: SetupCodeRejectionNoticeProps) {
  const { t } = useTranslation();

  if (rejection.kind === 'cooldown') {
    return (
      <Notice variant="warning">
        <CooldownMessage retryAt={retryAt} waitingKey="pages.setup.code.cooldown" unknownWaitKey="pages.setup.code.cooldownUnknown" />
      </Notice>
    );
  }
  if (rejection.kind === 'invalid-input') {
    return <Notice variant="error" announce>{detail ?? t('pages.setup.code.invalidInput')}</Notice>;
  }
  if (rejection.kind === 'locked' || rejection.kind === 'expired' || rejection.kind === 'failed') {
    return <Notice variant="error" announce>{t(REJECTION_MESSAGES[rejection.kind])}</Notice>;
  }
  return null;
}

export function SessionNotKeptNotice({ titleKey, descriptionKey }: { titleKey: string; descriptionKey: string }) {
  const { t } = useTranslation();

  return (
    <div className="space-y-4">
      <Notice variant="success" title={t(titleKey)} announce>
        {t(descriptionKey)}
      </Notice>
      <Button asChild className="w-full">
        <Link href={PANEL_SIGN_IN_PATH}>{t('pages.setup.goToSignIn')}</Link>
      </Button>
    </div>
  );
}
