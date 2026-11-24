import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Notice } from '@/components/ui/notice';
import { Progress } from '@/components/ui/progress';
import type { UpdateStatus } from '@/hooks/data/updates';

const TAKING_LONG_MS = 20 * 60 * 1000;
const CLOCK_TICK_MS = 10_000;

function useMillisecondsWaited(waiting: boolean): number {
  const [waited, setWaited] = useState(0);
  useEffect(() => {
    if (!waiting) return undefined;
    const started = Date.now();
    const timer = window.setInterval(() => {
      setWaited(Date.now() - started);
    }, CLOCK_TICK_MS);
    return () => {
      window.clearInterval(timer);
      setWaited(0);
    };
  }, [waiting]);
  return waiting ? waited : 0;
}

export function UpdateProgress({ status, unreachable }: { status: UpdateStatus; unreachable: boolean }) {
  const { t } = useTranslation();
  const { stage, downloadPercent, problem, targetVersion } = status.install;
  const restarting = stage === 'restarting' || (unreachable && targetVersion !== null);
  const waited = useMillisecondsWaited(restarting);
  if (restarting) {
    return (
      <Notice variant={waited > TAKING_LONG_MS ? 'warning' : 'info'} announce>
        {waited > TAKING_LONG_MS ? t('updates.progress.takingLong') : t('updates.progress.restarting')}
      </Notice>
    );
  }
  switch (stage) {
    case 'downloading':
      return (
        <div className="space-y-1">
          <p className="text-sm">{t('updates.progress.downloading', { percent: downloadPercent })}</p>
          <Progress value={downloadPercent} />
        </div>
      );
    case 'preparing':
      return <Notice variant="info" announce>{t('updates.progress.preparing')}</Notice>;
    case 'failed':
      return <Notice variant="error" announce>{t(`updates.failure.${problem ?? 'internal'}`)}</Notice>;
    case 'idle':
      return null;
  }
}
