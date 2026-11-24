import { useState } from 'react';
import PlayerPunishment, { type PlayerPunishmentData } from '@/components/ui/player-punishment';
import type { PunishmentTypeConfig } from '@/lib/punishment-preview';
import type { PlayerTicket } from '@/hooks/data/tickets';
import { usePunishmentSubmission } from '@/hooks/use-punishment-submission';
import { attachableTickets, type PlayerProfile } from './player-profile';
import type { PunishmentTypesByCategory } from '@/lib/punishment-categories';

interface PlayerPunishTabProps {
  readonly playerId: string;
  readonly profile: PlayerProfile;
  readonly tickets: readonly PlayerTicket[];
  readonly punishmentTypes: readonly PunishmentTypeConfig[];
  readonly permittedTypesByCategory: PunishmentTypesByCategory;
  readonly isLoadingPunishmentTypes: boolean;
}

export function PlayerPunishTab({ playerId, profile, tickets, punishmentTypes, permittedTypesByCategory, isLoadingPunishmentTypes }: PlayerPunishTabProps) {
  const [form, setForm] = useState<PlayerPunishmentData>({});
  const apply = usePunishmentSubmission({
    playerUuid: playerId,
    punishmentTypes,
  });

  return (
    <PlayerPunishment
      playerId={playerId}
      playerName={profile.username}
      playerStatus={profile.isOnline ? 'Online' : 'Offline'}
      data={form}
      onChange={setForm}
      onApply={apply}
      punishmentTypesByCategory={permittedTypesByCategory}
      isLoading={isLoadingPunishmentTypes}
      compact={false}
      availableTickets={attachableTickets(tickets)}
    />
  );
}
