import { useEffect, useId, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { ChevronDown, ChevronRight } from 'lucide-react';
import { Button } from '@/components/ui/button';
import type { PlayerTicket } from '@/hooks/data/tickets';
import { PERMISSIONS, usePermissions } from '@/hooks/use-permissions';
import { MANUAL_PUNISHMENTS } from '@/lib/manual-punishments';
import { allowedModifications, type ModificationType } from '@/lib/punishment-actions';
import { formatDateWithTime } from '@/utils/date-utils';
import { derivePunishmentStatusFromState } from '@/utils/punishment-status';
import { hasPunishmentDetails, isRecordedPunishment, punishmentReason, type PunishmentHistoryEntry } from '../player-profile';
import { formatPunishmentDuration, punishmentTimeline } from '../punishment-timeline';
import { PunishmentHistoryBadges } from './PunishmentHistoryBadges';
import { PunishmentTimelineLine } from './PunishmentTimelineLine';
import { PunishmentHistoryDetails } from './PunishmentHistoryDetails';
import { PunishmentNoteForm } from './PunishmentNoteForm';
import { PunishmentEvidenceForm } from './PunishmentEvidenceForm';
import { ModifyPunishmentForm } from './ModifyPunishmentForm';
import { PunishmentTicketsForm } from './PunishmentTicketsForm';

type PunishmentActionForm = 'note' | 'evidence' | 'modify' | 'tickets';

const KICK_ORDINAL = MANUAL_PUNISHMENTS.Kick.ordinal;

interface PunishmentHistoryItemProps {
  readonly playerId: string;
  readonly entry: PunishmentHistoryEntry;
  readonly tickets: readonly PlayerTicket[];
  readonly now: number;
  readonly focusRequest: number | null;
  readonly onOpenPlayer: (uuid: string, username: string) => void;
}

function ActionButton({ label, onClick }: { readonly label: string; readonly onClick: () => void }) {
  return (
    <Button variant="outline" size="sm" className="h-8 text-xs" onClick={onClick}>
      {label}
    </Button>
  );
}

function ActionForm({ form, playerId, entry, modifications, tickets, now, onClose }: {
  readonly form: PunishmentActionForm;
  readonly modifications: readonly ModificationType[];
  readonly now: number;
  readonly playerId: string;
  readonly entry: PunishmentHistoryEntry;
  readonly tickets: readonly PlayerTicket[];
  readonly onClose: () => void;
}) {
  switch (form) {
    case 'note':
      return <PunishmentNoteForm playerId={playerId} punishmentId={entry.punishment.id} onClose={onClose} />;
    case 'evidence':
      return <PunishmentEvidenceForm playerId={playerId} punishmentId={entry.punishment.id} onClose={onClose} />;
    case 'modify':
      return <ModifyPunishmentForm playerId={playerId} entry={entry} modifications={modifications} now={now} onClose={onClose} />;
    case 'tickets':
      return <PunishmentTicketsForm playerId={playerId} entry={entry} tickets={tickets} onClose={onClose} />;
  }
}

export function PunishmentHistoryItem({ playerId, entry, tickets, now, focusRequest, onOpenPlayer }: PunishmentHistoryItemProps) {
  const { t } = useTranslation();
  const containerRef = useRef<HTMLDivElement>(null);
  const detailsId = useId();
  const isFocused = focusRequest !== null;
  const [isExpanded, setIsExpanded] = useState(isFocused);
  const [shownFocusRequest, setShownFocusRequest] = useState(focusRequest);
  if (focusRequest !== shownFocusRequest) {
    setShownFocusRequest(focusRequest);
    if (focusRequest !== null) setIsExpanded(true);
  }

  useEffect(() => {
    if (focusRequest !== null) containerRef.current?.scrollIntoView({ behavior: 'smooth', block: 'center' });
  }, [focusRequest]);

  const [actionForm, setActionForm] = useState<PunishmentActionForm | null>(null);
  const { punishment, effectiveState } = entry;
  const { hasPermission } = usePermissions();
  const modifications = allowedModifications(hasPermission, punishment, entry.punishmentType, now);
  const canAddNote = hasPermission(PERMISSIONS.PUNISHMENT_MODIFY_NOTE);
  const canAddEvidence = hasPermission(PERMISSIONS.PUNISHMENT_MODIFY_EVIDENCE);
  const canManageTickets = hasPermission(PERMISSIONS.PUNISHMENT_MODIFY_TICKETS);
  const hasAllowedAction = canAddNote || canAddEvidence || canManageTickets || modifications.length > 0;
  const isRecorded = isRecordedPunishment(entry);
  const canExpand = isRecorded && (hasAllowedAction || hasPunishmentDetails(entry));
  const isKick = punishment.typeOrdinal === KICK_ORDINAL;
  const status = derivePunishmentStatusFromState(punishment, effectiveState);
  const timeline = punishmentTimeline(entry, now);
  const duration = effectiveState.effectiveDuration;
  const reason = punishmentReason(punishment);
  const durationLabel = isRecorded && !isKick && duration !== null && duration !== undefined ? formatPunishmentDuration(duration, t) : null;

  return (
    <div
      ref={containerRef}
      className={`rounded-lg border bg-card p-3 transition-shadow ${isFocused ? 'ring-2 ring-primary' : ''}`}
    >
      <div className="relative flex items-start justify-between gap-2">
        <div className="min-w-0 flex-1">
          <PunishmentHistoryBadges entry={entry} durationLabel={durationLabel} status={isRecorded && !isKick ? status : null} />
        </div>
        {canExpand && (
          <Button
            variant="ghost"
            size="icon"
            className="h-7 w-7 shrink-0 after:absolute after:inset-0 after:content-['']"
            aria-expanded={isExpanded}
            aria-controls={detailsId}
            aria-label={isExpanded ? t('common.showLess') : t('common.showMore')}
            onClick={() => { setIsExpanded(!isExpanded); }}
          >
            {isExpanded ? <ChevronDown className="h-4 w-4" /> : <ChevronRight className="h-4 w-4" />}
          </Button>
        )}
      </div>
      {reason !== null && <p className="mt-1 text-sm">{reason}</p>}
      {timeline !== null && <PunishmentTimelineLine timeline={timeline} />}
      <p className="mt-1 text-xs text-muted-foreground">
        {[
          t('player.byIssuer', { issuer: punishment.issuerName }),
          formatDateWithTime(punishment.issued),
          isRecorded && punishment.id !== '0' ? t('player.punishmentIdLabel', { id: punishment.id }) : '',
        ].filter((part) => part !== '').join(' · ')}
      </p>
      {canExpand && isExpanded && (
        <div id={detailsId} className="mt-3 space-y-3 border-t pt-3">
          <PunishmentHistoryDetails entry={entry} onOpenPlayer={onOpenPlayer} />
          {hasAllowedAction && (
            <div className="flex flex-wrap gap-2">
              {canAddNote && <ActionButton label={t('player.addNote')} onClick={() => setActionForm('note')} />}
              {canAddEvidence && <ActionButton label={t('player.addEvidence')} onClick={() => setActionForm('evidence')} />}
              {modifications.length > 0 && <ActionButton label={t('player.modify')} onClick={() => setActionForm('modify')} />}
              {canManageTickets && <ActionButton label={t('player.tabs.tickets')} onClick={() => setActionForm('tickets')} />}
            </div>
          )}
          {actionForm !== null && (
            <ActionForm
              key={actionForm}
              form={actionForm}
              playerId={playerId}
              entry={entry}
              modifications={modifications}
              tickets={tickets}
              now={now}
              onClose={() => setActionForm(null)}
            />
          )}
        </div>
      )}
    </div>
  );
}
