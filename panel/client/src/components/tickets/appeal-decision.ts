import type { PanelTicket } from '@/hooks/data/tickets';
import type { AppealDecision } from './quick-actions';
import type { ReduceDuration } from './ReduceDurationFields';

export interface AppealModification {
  playerUuid: string;
  punishmentId: string;
  modificationType: string;
  reason: string;
  newDuration?: { value: number; unit: string };
  successTitleKey: string;
  successDescriptionKey: string;
}

export type AppealModificationResult =
  | { ok: true; modification: AppealModification }
  | { ok: false; issueKey: string };

interface AppealTarget {
  playerUuid: string;
  punishmentId: string;
}

function modificationFor(decision: AppealDecision, reduceDuration: ReduceDuration, target: AppealTarget): AppealModificationResult {
  switch (decision) {
    case 'pardon':
      return {
        ok: true,
        modification: {
          ...target,
          modificationType: 'APPEAL_ACCEPT',
          reason: 'Appeal approved - full pardon granted',
          successTitleKey: 'ticket.punishmentPardoned',
          successDescriptionKey: 'ticket.punishmentPardonedDesc',
        },
      };
    case 'reduce':
      if (!reduceDuration.isPermanent && reduceDuration.value <= 0) {
        return { ok: false, issueKey: 'ticket.reduce.invalidDuration' };
      }
      return {
        ok: true,
        modification: {
          ...target,
          modificationType: 'APPEAL_DURATION_CHANGE',
          reason: 'Appeal partially approved - duration reduced',
          newDuration: reduceDuration.isPermanent
            ? { value: 0, unit: 'seconds' }
            : { value: reduceDuration.value, unit: reduceDuration.unit },
          successTitleKey: 'ticket.punishmentReduced',
          successDescriptionKey: 'ticket.punishmentReducedDesc',
        },
      };
    case 'reject':
      return {
        ok: true,
        modification: {
          ...target,
          modificationType: 'APPEAL_REJECT',
          reason: 'Appeal rejected - original punishment upheld',
          successTitleKey: 'ticket.appealRejected',
          successDescriptionKey: 'ticket.appealRejectedDesc',
        },
      };
  }
}

export function appealModificationOf(
  decision: AppealDecision,
  reduceDuration: ReduceDuration,
  ticket: PanelTicket,
): AppealModificationResult {
  const { appealPlayerUuid, appealPunishmentId } = ticket;
  if (appealPlayerUuid === undefined || appealPunishmentId === undefined) {
    return { ok: false, issueKey: 'ticket.appeal.missingTarget' };
  }
  return modificationFor(decision, reduceDuration, { playerUuid: appealPlayerUuid, punishmentId: appealPunishmentId });
}
