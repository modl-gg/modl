import { useTranslation } from 'react-i18next';
import { ProfileEmpty } from '../ProfileEmpty';
import { keyByContent } from '@/components/ui/stable-keys';
import type { PlayerTicket } from '@/hooks/data/tickets';
import { useNow } from '@/hooks/use-now';
import { focusRequestFor, type PunishmentFocus } from '@/lib/player-links';
import type { PunishmentHistoryEntry } from '../player-profile';
import { PunishmentHistoryItem } from './PunishmentHistoryItem';

interface PunishmentHistoryTabProps {
  readonly playerId: string;
  readonly history: readonly PunishmentHistoryEntry[];
  readonly tickets: readonly PlayerTicket[];
  readonly focus: PunishmentFocus | null;
  readonly onOpenPlayer: (uuid: string, username: string) => void;
}

const CLOCK_INTERVAL_MS = 30_000;

export function PunishmentHistoryTab({ playerId, history, tickets, focus, onOpenPlayer }: PunishmentHistoryTabProps) {
  const { t } = useTranslation();
  const now = useNow(CLOCK_INTERVAL_MS);

  return (
    <div className="space-y-2">
        {history.length > 0 ? keyByContent(history, (entry) => entry.punishment.id).map(({ key, item: entry }) => (
          <PunishmentHistoryItem
            key={key}
            playerId={playerId}
            entry={entry}
            tickets={tickets}
            now={now}
            focusRequest={focusRequestFor(focus, entry.punishment.id)}
            onOpenPlayer={onOpenPlayer}
          />
        )) : (
          <ProfileEmpty>{t('player.noHistory')}</ProfileEmpty>
        )}
    </div>
  );
}
