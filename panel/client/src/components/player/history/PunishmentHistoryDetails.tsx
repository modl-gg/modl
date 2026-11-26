import { useTranslation } from 'react-i18next';
import { useLocation } from 'wouter';
import { Button } from '@/components/ui/button';
import { EvidenceList } from '@/components/ui/evidence-list';
import { keyByContent } from '@/components/ui/stable-keys';
import { formatDateWithTime } from '@/utils/date-utils';
import { modificationRecordLabel } from '@/lib/punishment-modification-labels';
import { ticketRoutePath } from '@/components/tickets/ticket-view';
import { isRecordedPunishment, toEvidenceEntry, type PunishmentHistoryEntry, type PunishmentModificationRecord } from '../player-profile';
import { formatPunishmentDuration } from '../punishment-timeline';
import { LinkedBansList } from '../LinkedBansList';

function StaffNotes({ entry }: { readonly entry: PunishmentHistoryEntry }) {
  const { t } = useTranslation();
  const { notes } = entry.punishment;
  if (notes.length === 0) return null;
  return (
    <div>
      <p className="text-xs font-medium text-muted-foreground mb-1">{t('player.staffNotes')}</p>
      <ul className="space-y-2">
        {keyByContent(notes, (note) => note.id || note.text).map(({ key, item: note }) => (
          <li key={key} className="text-sm">
            <p>{note.text}</p>
            <p className="text-muted-foreground text-xs">
              {t('evidence.issuedBy', {
                issuer: note.issuerName ?? t('common.unknown'),
                date: note.date === null ? t('common.unknown') : formatDateWithTime(note.date),
              })}
            </p>
          </li>
        ))}
      </ul>
    </div>
  );
}

function AttachedTickets({ ticketIds }: { readonly ticketIds: readonly string[] }) {
  const { t } = useTranslation();
  const [, navigate] = useLocation();
  if (ticketIds.length === 0) return null;
  return (
    <div>
      <p className="text-xs font-medium text-muted-foreground mb-1">{t('player.attachedTickets')}</p>
      <div className="flex flex-wrap gap-1">
        {keyByContent(ticketIds, (ticketId) => ticketId).map(({ key, item: ticketId }) => (
          <Button key={key} variant="outline" size="sm" className="h-7 px-2 text-xs" onClick={() => navigate(ticketRoutePath(ticketId))}>
            {ticketId}
          </Button>
        ))}
      </div>
    </div>
  );
}

function ModificationItem({ modification }: { readonly modification: PunishmentModificationRecord }) {
  const { t } = useTranslation();
  const [, navigate] = useLocation();
  const appealTicketId = modification.type.includes('APPEAL') ? modification.appealTicketId ?? '' : '';
  return (
    <div className="border-l-2 pl-3 text-xs">
      <div className="flex justify-between items-start gap-2">
        <span className="text-sm font-medium">{modificationRecordLabel(modification.type, t)}</span>
        <span className="text-muted-foreground">{formatDateWithTime(modification.date)}</span>
      </div>
      {modification.reason !== '' && <p className="text-sm">{modification.reason}</p>}
      {modification.effectiveDuration !== undefined && (
        <p className="text-muted-foreground">
          {t('player.newDurationValue', { duration: formatPunishmentDuration(modification.effectiveDuration, t) })}
        </p>
      )}
      <div className="flex items-center justify-between">
        <p className="text-muted-foreground text-xs">{t('player.byIssuer', { issuer: modification.issuerName ?? t('common.unknown') })}</p>
        {appealTicketId !== '' && (
          <Button variant="outline" size="sm" className="h-7 px-2 text-xs" onClick={() => navigate(ticketRoutePath(appealTicketId))}>
            {t('player.viewAppeal')}
          </Button>
        )}
      </div>
    </div>
  );
}

interface PunishmentHistoryDetailsProps {
  readonly entry: PunishmentHistoryEntry;
  readonly onOpenPlayer: (uuid: string, username: string) => void;
}

export function PunishmentHistoryDetails({ entry, onOpenPlayer }: PunishmentHistoryDetailsProps) {
  const { t } = useTranslation();
  const { punishment, effectiveState } = entry;
  return (
    <>
      <EvidenceList evidence={punishment.evidence.map(toEvidenceEntry)} />
      <StaffNotes entry={entry} />
      <AttachedTickets ticketIds={punishment.attachedTicketIds} />
      {punishment.altBlocking === true && isRecordedPunishment(entry) && (
        <LinkedBansList punishmentId={punishment.id} onOpenPlayer={onOpenPlayer} />
      )}
      {effectiveState.hasModifications && (
        <div>
          <p className="text-xs font-medium text-muted-foreground mb-1">{t('player.modificationHistory')}</p>
          <div className="space-y-2">
            {keyByContent(effectiveState.modifications, (modification) => modification.id || modification.type).map(({ key, item: modification }) => (
              <ModificationItem key={key} modification={modification} />
            ))}
          </div>
        </div>
      )}
    </>
  );
}
