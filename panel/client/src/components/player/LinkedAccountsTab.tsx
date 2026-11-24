import { useTranslation } from 'react-i18next';
import { LoadingSpinner } from '@/components/ui/loading-spinner';
import { LoadFailure } from '@/components/ui/load-failure';
import { StatusBadge } from '@/components/ui/status-badge';
import { ProfileEmpty } from './ProfileEmpty';
import { Button } from '@/components/ui/button';
import type { LinkedAccountResponse } from '@proto/modl/v1/player_pb.ts';

interface LinkedAccountsTabProps {
  readonly linkedAccounts: readonly LinkedAccountResponse[];
  readonly isLoading: boolean;
  readonly isSearching: boolean;
  readonly searchFailed: boolean;
  readonly onRetrySearch: () => void;
  readonly onOpenPlayer: (uuid: string, username: string) => void;
}

function LinkedAccountRow({ account, onOpenPlayer }: { readonly account: LinkedAccountResponse; readonly onOpenPlayer: LinkedAccountsTabProps['onOpenPlayer'] }) {
  const { t } = useTranslation();
  return (
    <li className="text-sm flex items-center gap-2 flex-wrap py-1">
      <Button
        variant="link"
        size="sm"
        className="h-auto p-0 font-medium"
        disabled={account.minecraftUuid === ''}
        onClick={() => onOpenPlayer(account.minecraftUuid, account.username)}
      >
        {account.username}
      </Button>
      {account.activeBans > 0 && (
        <StatusBadge intent="destructive">
          {t('player.activeBans', { count: account.activeBans })}
        </StatusBadge>
      )}
      {account.activeMutes > 0 && (
        <StatusBadge intent="warning">
          {t('player.activeMutes', { count: account.activeMutes })}
        </StatusBadge>
      )}
    </li>
  );
}

export function LinkedAccountsTab({ linkedAccounts, isLoading, isSearching, searchFailed, onRetrySearch, onOpenPlayer }: LinkedAccountsTabProps) {
  const { t } = useTranslation();

  return (
    <>
      {searchFailed && !isSearching && <LoadFailure className="py-3" message={t('player.linkedSearchFailed')} onRetry={onRetrySearch} />}
      {isLoading ? (
        <div className="flex justify-center py-8">
          <LoadingSpinner className="h-5 w-5 text-muted-foreground" />
        </div>
      ) : linkedAccounts.length > 0 ? (
        <ul>
          {linkedAccounts.map((account) => (
            <LinkedAccountRow key={account.minecraftUuid || account.username} account={account} onOpenPlayer={onOpenPlayer} />
          ))}
        </ul>
      ) : isSearching ? (
        <div className="flex justify-center py-8">
          <LoadingSpinner className="h-5 w-5 text-muted-foreground" />
        </div>
      ) : (
        <ProfileEmpty>{t('player.noLinkedAccounts')}</ProfileEmpty>
      )}
    </>
  );
}
