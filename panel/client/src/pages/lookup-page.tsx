import { useState } from 'react';
import { useLocation } from 'wouter';
import { Search, ChevronRight, X } from 'lucide-react';
import PageContainer from '@/components/layout/PageContainer';
import { LoadingSpinner } from '@/components/ui/loading-spinner';
import { Input } from '@/components/ui/input';
import { Button } from '@/components/ui/button';
import { foundPlayers, usePlayerSearch, type PlayerSearch } from '@/hooks/use-data';
import { PlayerSearchFailure } from '@/components/player/PlayerSearchFailure';
import { useRecentPlayerSearches } from '@/hooks/use-recent-player-searches';
import { getAvatarUrl } from '@/lib/api';
import { recentPlayerOf, type RecentPlayer } from '@/lib/recent-player-searches';
import { useTranslation } from 'react-i18next';
import { playerPagePath } from '@/lib/player-links';

const VISIBLE_RECENT_SEARCHES = 5;

function PlayerRow({ player, onSelect }: { player: RecentPlayer; onSelect: (player: RecentPlayer) => void }) {
  const { t } = useTranslation();
  const displayName = player.username || t('pages.lookup.unknownPlayer');
  return (
    <button
      type="button"
      className="flex w-full items-center justify-between rounded-md px-2 py-2 text-left hover:bg-muted/50 focus-visible:outline-hidden focus-visible:ring-2 focus-visible:ring-ring"
      onClick={() => onSelect(player)}
    >
      <div className="flex items-center">
        <div className="relative h-10 w-10 bg-muted rounded-lg flex items-center justify-center overflow-hidden mr-3">
          <img
            src={getAvatarUrl(player.uuid, 40, true)}
            alt={t('pages.lookup.playerAvatar', { name: displayName })}
            className="w-full h-full object-cover"
            onError={(e) => {
              e.currentTarget.style.display = 'none';
              e.currentTarget.nextElementSibling?.classList.remove('hidden');
            }}
          />
          <span className="hidden text-sm font-bold text-primary">
            {player.username.substring(0, 2).toUpperCase() || '??'}
          </span>
        </div>
        <div>
          <p className="font-medium text-sm">{displayName}</p>
          <p className="text-xs text-muted-foreground">
            {player.isOnline ? t('pages.lookup.online') : t('pages.lookup.offline')}
          </p>
        </div>
      </div>

      <ChevronRight className="h-4 w-4 text-muted-foreground" />
    </button>
  );
}

function PlayerList({ players, onSelect }: { players: readonly RecentPlayer[]; onSelect: (player: RecentPlayer) => void }) {
  return (
    <div className="space-y-1">
      {players.map((player) => <PlayerRow key={player.uuid} player={player} onSelect={onSelect} />)}
    </div>
  );
}

function LookupResults({ search, query, recentPlayers, onSelect }: {
  search: PlayerSearch;
  query: string;
  recentPlayers: readonly RecentPlayer[];
  onSelect: (player: RecentPlayer) => void;
}) {
  const { t } = useTranslation();
  switch (search.state) {
    case 'inactive':
      return recentPlayers.length > 0 && (
        <div className="space-y-1">
          <h3 className="mb-2 text-sm font-medium text-muted-foreground">{t('pages.lookup.recentLookups')}</h3>
          <PlayerList players={recentPlayers} onSelect={onSelect} />
        </div>
      );
    case 'pending':
      return (
        <div className="flex items-center justify-center py-8">
          <LoadingSpinner className="h-6 w-6 text-muted-foreground" />
        </div>
      );
    case 'failed':
      return <PlayerSearchFailure onRetry={search.retry} />;
    case 'settled':
      return search.players.length > 0
        ? <PlayerList players={search.players.map(recentPlayerOf)} onSelect={onSelect} />
        : <p className="py-8 text-center text-sm text-muted-foreground">{t('search.noPlayersFound', { query: query.trim() })}</p>;
  }
}

const LookupPage = () => {
  const { t } = useTranslation();
  const [, navigate] = useLocation();
  const [searchQuery, setSearchQuery] = useState('');
  const [recentSearches, rememberPlayer] = useRecentPlayerSearches();

  const playerSearch = usePlayerSearch(searchQuery);

  const handlePlayerSelect = (player: RecentPlayer) => {
    rememberPlayer(player);
    navigate(playerPagePath(player.uuid));
  };

  const handleClearSearch = () => {
    setSearchQuery('');
  };

  return (
    <PageContainer>
      <h2 className="text-xl font-semibold mb-4">{t('pages.lookup.title')}</h2>

      <div className="relative mb-4">
        <div className="absolute inset-y-0 left-0 pl-3 flex items-center pointer-events-none z-10">
          <Search className="h-4 w-4 text-muted-foreground" />
        </div>

        <Input
          type="text"
          placeholder={t('pages.lookup.searchPlaceholder')}
          aria-label={t('pages.lookup.searchPlaceholder')}
          className="pl-10 pr-10"
          value={searchQuery}
          onChange={(e) => setSearchQuery(e.target.value)}
          onKeyDown={(e) => {
            const firstPlayer = foundPlayers(playerSearch)[0];
            if (e.key === 'Enter' && firstPlayer) {
              e.preventDefault();
              handlePlayerSelect(recentPlayerOf(firstPlayer));
            }
          }}
          autoFocus
          autoCorrect="off"
          autoCapitalize="off"
        />

        {searchQuery !== '' && (
          <div className="absolute inset-y-0 right-0 pr-3 flex items-center">
            <Button
              variant="ghost"
              size="sm"
              className="h-6 w-6 p-0"
              onClick={handleClearSearch}
              aria-label={t('pages.lookup.clearSearch')}
            >
              <X className="h-4 w-4" />
            </Button>
          </div>
        )}
      </div>

      <LookupResults
        search={playerSearch}
        query={searchQuery}
        recentPlayers={recentSearches.slice(0, VISIBLE_RECENT_SEARCHES).map(({ player }) => player)}
        onSelect={handlePlayerSelect}
      />
    </PageContainer>
  );
};

export default LookupPage;