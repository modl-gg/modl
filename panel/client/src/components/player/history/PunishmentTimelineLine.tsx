import { useTranslation } from 'react-i18next';
import { formatDateWithTime } from '@/utils/date-utils';
import { formatCompactDuration, type PunishmentTimeline } from '../punishment-timeline';

function useTimelineText(timeline: PunishmentTimeline): string {
  const { t } = useTranslation();
  switch (timeline.kind) {
    case 'pardoned':
      return timeline.at === null
        ? t('player.timeline.pardoned')
        : t('player.timeline.pardonedAgo', { elapsed: formatCompactDuration(timeline.elapsedMs, t), date: formatDateWithTime(timeline.at) });
    case 'rolledBack':
      return timeline.at === null
        ? t('player.timeline.rolledBack')
        : t('player.timeline.rolledBackAgo', { elapsed: formatCompactDuration(timeline.elapsedMs, t), date: formatDateWithTime(timeline.at) });
    case 'waiting':
      return '';
    case 'expired':
      return t('player.timeline.expiredAgo', { elapsed: formatCompactDuration(timeline.elapsedMs, t), date: formatDateWithTime(timeline.at) });
    case 'expires':
      return t('player.timeline.expiresIn', { remaining: formatCompactDuration(timeline.remainingMs, t), date: formatDateWithTime(timeline.at) });
    case 'invalidExpiry':
      return t('player.timeline.invalidExpiry');
    case 'permanent':
      return t('punishment.permanent');
    case 'duration':
      return t('player.timeline.duration', { duration: formatCompactDuration(timeline.durationMs, t) });
  }
}

export function PunishmentTimelineLine({ timeline }: { readonly timeline: PunishmentTimeline }) {
  const text = useTimelineText(timeline);
  return text === '' ? null : <p className="mt-0.5 text-xs text-muted-foreground">{text}</p>;
}
