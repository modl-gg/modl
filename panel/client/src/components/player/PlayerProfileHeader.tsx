import { useTranslation } from 'react-i18next';
import { PUNISHMENT_CATEGORY_LABEL_KEYS } from '@/lib/punishment-category-labels';
import type { TFunction } from 'i18next';
import { StatusBadge } from '@/components/ui/status-badge';
import { formatDateWithTime } from '@/utils/date-utils';
import { PlayerAvatar } from './PlayerAvatar';
import type { OffenderLevel, PlayerProfile } from './player-profile';

const OFFENDER_LEVEL_LABEL_KEYS = {
  low: 'punishment.offenseFirst',
  medium: 'punishment.offenseMedium',
  habitual: 'punishment.offenseHabitual',
} as const satisfies Record<OffenderLevel, string>;

const OFFENDER_LEVEL_INTENTS = {
  low: 'success',
  medium: 'warning',
  habitual: 'destructive',
} as const satisfies Record<OffenderLevel, 'success' | 'warning' | 'destructive'>;

const SECONDS_PER_HOUR = 3600;
const SECONDS_PER_MINUTE = 60;

function playtimeLabel(totalSeconds: number, t: TFunction): string {
  const hours = Math.floor(totalSeconds / SECONDS_PER_HOUR);
  const minutes = Math.floor((totalSeconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE);
  return hours > 0 ? t('player.compactDuration.hoursMinutes', { hours, minutes }) : t('player.compactDuration.minutes', { minutes });
}

function lastSeenLabel(profile: PlayerProfile, t: TFunction): string {
  return profile.lastSeen === null ? t('common.unknown') : formatDateWithTime(profile.lastSeen);
}

function InfoField({ label, value }: { label: string; value: string }) {
  return (
    <div className="contents">
      <dt className="text-muted-foreground">{label}</dt>
      <dd className="min-w-0 break-words">{value}</dd>
    </div>
  );
}

function locationOf(profile: PlayerProfile): string {
  return [profile.region, profile.country].filter((part) => part !== '').join(', ');
}

function OffenderBadge({ label, level }: { label: string; level: OffenderLevel }) {
  const { t } = useTranslation();
  return (
    <StatusBadge intent={OFFENDER_LEVEL_INTENTS[level]}>
      {t('player.offenderLevel', { category: label, level: t(OFFENDER_LEVEL_LABEL_KEYS[level]) })}
    </StatusBadge>
  );
}

export function PlayerProfileHeader({ playerId, profile }: { playerId: string; profile: PlayerProfile }) {
  const { t } = useTranslation();
  const location = locationOf(profile);

  return (
    <div className="space-y-3">
      <div className="flex items-center gap-4">
        <PlayerAvatar key={playerId} playerId={playerId} username={profile.username} />
        <div className="min-w-0 flex-1 space-y-2">
          <div className="min-w-0">
            <p className="truncate text-lg font-semibold leading-tight">{profile.username}</p>
            <p className="truncate font-mono text-xs text-muted-foreground">{playerId}</p>
          </div>
          <div className="flex min-w-0 flex-wrap gap-1.5">
            <StatusBadge intent={profile.isOnline ? 'success' : 'neutral'}>
              {profile.isOnline ? t('player.online') : t('player.offline')}
            </StatusBadge>
            {profile.restriction !== null && (
              <StatusBadge intent={profile.restriction === 'banned' ? 'destructive' : 'warning'}>
                {profile.restriction === 'banned' ? t('player.currentlyBanned') : t('player.currentlyMuted')}
              </StatusBadge>
            )}
            <OffenderBadge label={t(PUNISHMENT_CATEGORY_LABEL_KEYS.social)} level={profile.social} />
            <OffenderBadge label={t(PUNISHMENT_CATEGORY_LABEL_KEYS.gameplay)} level={profile.gameplay} />
          </div>
        </div>
      </div>
      <dl className="grid grid-cols-[max-content_minmax(0,1fr)] gap-x-4 gap-y-1 text-sm sm:pl-20 lg:grid-cols-[max-content_minmax(0,1fr)_max-content_minmax(0,1fr)]">
        {location !== '' && <InfoField label={t('player.location')} value={location} />}
        {profile.latestIpActivity !== null && (
          <>
            <InfoField
              label={t('player.latestIpLastLogin')}
              value={profile.latestIpActivity.lastLogin === null ? t('common.unknown') : formatDateWithTime(profile.latestIpActivity.lastLogin)}
            />
            <InfoField label={t('player.latestIpLogins')} value={String(profile.latestIpActivity.loginCount)} />
          </>
        )}
        <InfoField label={t('player.firstJoin')} value={profile.firstJoined === null ? t('common.unknown') : formatDateWithTime(profile.firstJoined)} />
        {!profile.isOnline && <InfoField label={t('player.lastJoin')} value={lastSeenLabel(profile, t)} />}
        {profile.playtimeSeconds > 0 && <InfoField label={t('player.playtime')} value={playtimeLabel(profile.playtimeSeconds, t)} />}
        <InfoField
          label={profile.isOnline ? t('player.currentServer') : t('player.lastServer')}
          value={profile.lastServer || t('common.unknown')}
        />
      </dl>
    </div>
  );
}
