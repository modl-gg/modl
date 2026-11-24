import { useState } from 'react';
import {
  readRecentPlayerSearches,
  withRecentPlayer,
  type RecentPlayer,
  type RecentPlayerSearch,
} from '@/lib/recent-player-searches';

export function useRecentPlayerSearches(): [RecentPlayerSearch[], (player: RecentPlayer) => void] {
  const [searches, setSearches] = useState(readRecentPlayerSearches);
  const remember = (player: RecentPlayer) => {
    setSearches(withRecentPlayer(searches, player, Date.now()));
  };
  return [searches, remember];
}
