import { useApplyPunishment } from '@/hooks/data/punishments';
import {
  buildPunishmentRequest,
  PunishmentFormError,
  type PunishmentRequestForm,
  type PunishmentRequestType,
} from '@/lib/punishment-request';

interface PunishmentSubmissionTarget {
  readonly playerUuid: string;
  readonly punishmentTypes: readonly PunishmentRequestType[];
  readonly ticketIds?: readonly string[] | undefined;
}

export function usePunishmentSubmission(target: PunishmentSubmissionTarget) {
  const applyPunishment = useApplyPunishment();

  return async (data: PunishmentRequestForm): Promise<void> => {
    const result = buildPunishmentRequest(data, {
      punishmentTypes: target.punishmentTypes,
      ticketIds: target.ticketIds,
    });
    if (!result.ok) {
      throw new PunishmentFormError(result.issue);
    }
    await applyPunishment.mutateAsync({ uuid: target.playerUuid, request: result.request });
  };
}
