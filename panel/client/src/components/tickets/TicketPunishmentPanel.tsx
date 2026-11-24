import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { PunishmentType } from '@proto/modl/v1/settings_pb.ts';
import PlayerPunishment, { type PlayerPunishmentData } from '@/components/ui/player-punishment';
import { usePunishmentTypeCatalog } from '@/hooks/use-punishment-type-catalog';
import { usePunishmentSubmission } from '@/hooks/use-punishment-submission';

const INITIAL_PUNISHMENT_FORM: PlayerPunishmentData = {
  selectedPunishmentCategory: undefined,
  selectedSeverity: undefined,
  selectedOffenseLevel: undefined,
  duration: { value: 1, unit: 'days' },
  isPermanent: false,
  reason: '',
  evidence: [],
  staffNotes: '',
  altBlocking: false,
  statWiping: false,
  silentPunishment: false,
  kickSameIP: false,
  attachReports: [],
  banToLink: '',
  banLinkedAccounts: false,
};

interface TicketPunishmentPanelProps {
  ticketId: string;
  playerUuid: string;
  playerName: string;
  punishmentTypes: readonly PunishmentType[];
}

export function TicketPunishmentPanel({ ticketId, playerUuid, playerName, punishmentTypes }: TicketPunishmentPanelProps) {
  const { t } = useTranslation();
  const [form, setForm] = useState<PlayerPunishmentData>(INITIAL_PUNISHMENT_FORM);
  const catalog = usePunishmentTypeCatalog(punishmentTypes);
  const submit = usePunishmentSubmission({
    playerUuid,
    punishmentTypes: catalog.punishmentTypes,
    ticketIds: [ticketId],
  });

  const apply = async (data: PlayerPunishmentData): Promise<void> => {
    if (!playerUuid) {
      throw new Error(t('ticket.punishment.noPlayerUuid', { player: playerName }));
    }
    await submit(data);
  };

  return (
    <div className="mb-4">
      <div className="mb-3">
        <h4 className="text-sm font-medium text-foreground">
          {t('ticket.punishment.title', { player: playerName || t('ticket.punishment.reportedPlayer') })}
        </h4>
        <p className="text-xs text-muted-foreground">{t('ticket.punishment.description')}</p>
      </div>

      <PlayerPunishment
        playerId={playerUuid}
        playerName={playerName}
        playerStatus="Offline"
        data={form}
        onChange={setForm}
        onApply={apply}
        punishmentTypesByCategory={catalog.permittedTypesByCategory}
        isLoading={false}
        compact={false}
      />
    </div>
  );
}
