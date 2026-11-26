import { useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import type { TFunction } from 'i18next';
import { LoadingSpinner } from '@/components/ui/loading-spinner';
import { StatusBadge } from '@/components/ui/status-badge';
import { modificationRecordLabel } from '@/lib/punishment-modification-labels';
import { Button } from '@/components/ui/button';
import { ClickablePlayer } from '@/components/ui/clickable-player';
import { EvidenceList, type EvidenceEntry } from '@/components/ui/evidence-list';
import { PunishmentStatusBadge } from '@/components/ui/punishment-status-badge';
import { OffenseStatusBadge, PunishmentOptionBadges, SeverityBadge } from '@/components/ui/punishment-level-badges';
import { keyByContent } from '@/components/ui/stable-keys';
import { usePunishmentById } from '@/hooks/data/punishments';
import { PERMISSIONS, usePermissions } from '@/hooks/use-permissions';
import type { mapPunishment } from '@/lib/punishment-mapping';
import { acceptsPlayerFacingReason } from '@/lib/manual-punishments';
import { formatDate, formatDateWithRelative } from '@/utils/date-utils';
import { derivePunishmentStatus } from '@/utils/punishment-status';
import { PunishmentEvidenceForm } from '@/components/player/history/PunishmentEvidenceForm';
import { PunishmentNoteForm } from '@/components/player/history/PunishmentNoteForm';
import { ticketRoutePath } from './ticket-view';

type AppealPunishment = ReturnType<typeof mapPunishment>;
type AppealPunishmentNote = AppealPunishment['notes'][number];
type AppealPunishmentModification = AppealPunishment['modifications'][number];

const PLACEHOLDER_BADGE_VALUES: ReadonlySet<string> = new Set(['0', 'null', 'undefined', 'false']);
const MILLIS_PER_SECOND = 1000;
const MILLIS_PER_MINUTE = 60 * MILLIS_PER_SECOND;
const MILLIS_PER_HOUR = 60 * MILLIS_PER_MINUTE;
const MILLIS_PER_DAY = 24 * MILLIS_PER_HOUR;

type OpenForm = 'none' | 'evidence' | 'note';

function badgeValueOf(value: string | undefined): string | undefined {
  const trimmed = value?.trim() ?? '';
  return trimmed === '' || PLACEHOLDER_BADGE_VALUES.has(trimmed) ? undefined : value;
}

function formatCompactDuration(durationMs: number, t: TFunction): string {
  if (durationMs <= 0) return t('punishment.permanent');
  const days = Math.floor(durationMs / MILLIS_PER_DAY);
  const hours = Math.floor((durationMs % MILLIS_PER_DAY) / MILLIS_PER_HOUR);
  const minutes = Math.floor((durationMs % MILLIS_PER_HOUR) / MILLIS_PER_MINUTE);
  const seconds = Math.floor((durationMs % MILLIS_PER_MINUTE) / MILLIS_PER_SECOND);
  if (days > 0) {
    return hours > 0
      ? t('ticket.duration.daysHours', { days, hours })
      : t('ticket.duration.days', { days });
  }
  if (hours > 0) {
    return minutes > 0
      ? t('ticket.duration.hoursMinutes', { hours, minutes })
      : t('ticket.duration.hours', { hours });
  }
  if (minutes > 0) return t('ticket.duration.minutes', { minutes });
  return t('ticket.duration.seconds', { seconds });
}

function evidenceEntryOf(evidence: AppealPunishment['evidence'][number]): EvidenceEntry {
  return {
    type: evidence.type,
    text: evidence.text,
    url: evidence.url,
    uploadedBy: evidence.uploadedBy,
    uploadedById: evidence.uploadedById,
    uploadedAt: evidence.uploadedAt ?? undefined,
    fileName: evidence.fileName,
    fileType: evidence.fileType,
    fileSize: evidence.fileSize,
  };
}

function issuedByLine(issuer: string | undefined, date: string | null, t: TFunction): string {
  return t('evidence.issuedBy', {
    issuer: issuer || t('evidence.system'),
    date: date ? formatDate(date) : t('common.unknown'),
  });
}

function CardFrame({ children }: { children: ReactNode }) {
  return <div className="rounded-lg border bg-card p-4 shadow-card">{children}</div>;
}

function DetailRow({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div>
      <span className="text-muted-foreground font-medium">{label}:</span>
      <span className="ml-2">{children}</span>
    </div>
  );
}

function PunishmentNoteItem({ note }: { note: AppealPunishmentNote }) {
  const { t } = useTranslation();
  return (
    <div className="text-sm border-l-2 pl-3">
      <div className="flex items-start">
        <div className="flex-1 min-w-0">
          <span className="break-all">{note.text}</span>
          <p className="text-muted-foreground text-xs mt-1">{issuedByLine(note.issuerName, note.date, t)}</p>
        </div>
      </div>
    </div>
  );
}

function PunishmentModificationItem({ modification }: { modification: AppealPunishmentModification }) {
  const { t } = useTranslation();
  return (
    <div className="text-sm border-l-2 pl-3">
      <div className="flex items-start">
        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-2 mb-1">
            <StatusBadge>{modificationRecordLabel(modification.type, t)}</StatusBadge>
          </div>
          {modification.reason && <div className="mb-1 break-all">{modification.reason}</div>}
          {modification.effectiveDuration !== undefined && (
            <div className="text-muted-foreground text-xs mb-1">
              {t('ticket.appeal.durationLabel', { duration: formatCompactDuration(modification.effectiveDuration, t) })}
            </div>
          )}
          <p className="text-muted-foreground text-xs mt-1">{issuedByLine(modification.issuerName, modification.date, t)}</p>
        </div>
      </div>
    </div>
  );
}

function openTicketInNewTab(ticketId: string) {
  window.open(ticketRoutePath(ticketId), '_blank');
}

function PunishmentBadges({ punishment }: { punishment: AppealPunishment }) {
  const severity = badgeValueOf(punishment.severity);
  const offenseStatus = badgeValueOf(punishment.status);

  return (
    <div className="mt-2 flex flex-wrap gap-2">
      <PunishmentOptionBadges altBlocking={punishment.altBlocking === true} statWiping={punishment.statWiping === true} />
      {severity !== undefined && <SeverityBadge severity={severity} />}
      {offenseStatus !== undefined && <OffenseStatusBadge status={offenseStatus} />}
    </div>
  );
}

function expiryText(punishment: AppealPunishment, t: TFunction): string {
  if (punishment.expires) return formatDateWithRelative(punishment.expires);
  return punishment.active ? t('punishment.permanent') : t('status.inactive');
}

function PunishmentDetails({ punishment, punishmentId }: { punishment: AppealPunishment; punishmentId: string }) {
  const { t } = useTranslation();
  const [openForm, setOpenForm] = useState<OpenForm>('none');
  const { hasPermission } = usePermissions();
  const canAddEvidence = hasPermission(PERMISSIONS.PUNISHMENT_MODIFY_EVIDENCE);
  const canAddNote = hasPermission(PERMISSIONS.PUNISHMENT_MODIFY_NOTE);
  const playerUuid = punishment.playerUuid ?? '';
  const closeForm = () => {
    setOpenForm('none');
  };

  return (
    <CardFrame>
      <div className="flex items-center justify-between gap-2 mb-3">
        <h3 className="text-base font-semibold">{t('ticket.appeal.title')}</h3>
        <span className="font-mono text-xs text-muted-foreground">{punishment.id}</span>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-2 gap-x-4 gap-y-2 text-sm">
        <DetailRow label={t('evidence.player')}>
          <ClickablePlayer playerText={punishment.playerUsername ?? ''} uuid={punishment.playerUuid} className="text-sm">
            {punishment.playerUsername}
          </ClickablePlayer>
        </DetailRow>
        <DetailRow label={t('ticket.appeal.type')}>{punishment.type}</DetailRow>
        <DetailRow label={t('ticket.appeal.issued')}>{formatDateWithRelative(punishment.issued ?? '')}</DetailRow>
        <DetailRow label={t('punishment.status')}>
          <PunishmentStatusBadge status={derivePunishmentStatus(punishment)} />
        </DetailRow>
        {punishment.started && (
          <DetailRow label={t('ticket.appeal.started')}>{formatDateWithRelative(punishment.started)}</DetailRow>
        )}
        <DetailRow label={t('ticket.appeal.expiry')}>{expiryText(punishment, t)}</DetailRow>
        <DetailRow label={t('ticket.appeal.issuedBy')}>{punishment.issuerName}</DetailRow>
      </div>

      <PunishmentBadges punishment={punishment} />

      {punishment.reason && acceptsPlayerFacingReason(punishment.type) && (
        <div className="mt-3">
          <div className="text-muted-foreground font-medium text-sm mb-1">{t('ticket.appeal.reason')}:</div>
          <div className="text-sm">{punishment.reason}</div>
        </div>
      )}

      <div className="mt-3">
        <EvidenceList evidence={punishment.evidence.map(evidenceEntryOf)} />
        {openForm === 'evidence' ? (
          <PunishmentEvidenceForm playerId={playerUuid} punishmentId={punishmentId} onClose={closeForm} />
        ) : canAddEvidence && (
          <Button
            variant="outline"
            size="sm"
            className="h-6 px-2 text-xs mt-2"
            disabled={playerUuid === ''}
            onClick={() => {
              setOpenForm('evidence');
            }}
          >
            {t('player.addEvidence')}
          </Button>
        )}
      </div>

      <div className="mt-3">
        <div className="flex items-center justify-between mb-1">
          <div className="text-muted-foreground font-medium text-sm">{t('player.staffNotes')}:</div>
          {canAddNote && (
            <Button
              variant="outline"
              size="sm"
              className="h-6 px-2 text-xs"
              disabled={playerUuid === '' || openForm === 'note'}
              onClick={() => {
                setOpenForm('note');
              }}
            >
              {t('player.addNote')}
            </Button>
          )}
        </div>
        {punishment.notes.length > 0 ? (
          <div className="space-y-2">
            {keyByContent(punishment.notes, (note) => `${note.id}|${note.date ?? ''}|${note.text}`).map(({ key, item }) => (
              <PunishmentNoteItem key={key} note={item} />
            ))}
          </div>
        ) : (
          <p className="text-xs text-muted-foreground">{t('player.noStaffNotes')}</p>
        )}
        {openForm === 'note' && (
          <PunishmentNoteForm playerId={playerUuid} punishmentId={punishmentId} onClose={closeForm} />
        )}
      </div>

      {punishment.modifications.length > 0 && (
        <div className="mt-3">
          <div className="text-muted-foreground font-medium text-sm mb-1">{t('ticket.appeal.modifications')}:</div>
          <div className="space-y-2">
            {keyByContent(punishment.modifications, (mod) => `${mod.id}|${mod.type}|${mod.date ?? ''}`).map(({ key, item }) => (
              <PunishmentModificationItem key={key} modification={item} />
            ))}
          </div>
        </div>
      )}

      {punishment.attachedTicketIds.length > 0 && (
        <div className="mt-3">
          <div className="text-muted-foreground font-medium text-sm mb-1">{t('ticket.appeal.attachedReports')}:</div>
          <div className="flex flex-wrap gap-2">
            {keyByContent(punishment.attachedTicketIds, (ticketId) => ticketId).map(({ key, item: ticketId }) => (
              <Button
                key={key}
                variant="outline"
                size="sm"
                className="h-6 px-2 text-xs"
                onClick={() => {
                  openTicketInNewTab(ticketId);
                }}
              >
                {ticketId}
              </Button>
            ))}
          </div>
        </div>
      )}
    </CardFrame>
  );
}

export function AppealPunishmentCard({ punishmentId }: { punishmentId: string }) {
  const { t } = useTranslation();
  const { data: punishment, isLoading, isError } = usePunishmentById(punishmentId);

  if (isLoading) {
    return (
      <CardFrame>
        <h3 className="text-base font-semibold mb-2">{t('ticket.appeal.title')}</h3>
        <LoadingSpinner className="h-5 w-5 text-primary" label={t('ticket.appeal.loading')} />
      </CardFrame>
    );
  }

  if (isError) {
    return (
      <CardFrame>
        <h3 className="text-base font-semibold mb-2">{t('ticket.appeal.title')}</h3>
        <div className="text-sm text-destructive-text">{t('ticket.appeal.loadFailed')}</div>
      </CardFrame>
    );
  }

  if (!punishment) {
    return null;
  }

  return <PunishmentDetails punishment={punishment} punishmentId={punishmentId} />;
}
