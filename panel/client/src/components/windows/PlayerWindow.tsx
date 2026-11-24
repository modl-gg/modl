import { memo } from 'react';
import { useTranslation } from 'react-i18next';
import type { TFunction } from 'i18next';
import { LoadingSpinner } from '@/components/ui/loading-spinner';
import { Button } from '@/components/ui/button';
import { LoadFailure } from '@/components/ui/load-failure';
import { ResizableWindow } from '@/components/layout/ResizableWindow';
import { PlayerProfileHeader } from '@/components/player/PlayerProfileHeader';
import { PlayerProfileTabs } from '@/components/player/PlayerProfileTabs';
import { usePlayerProfile, type PlayerProfileState } from '@/components/player/use-player-profile';
import { usePlayerWindow, type OpenPlayerWindow } from '@/contexts/PlayerWindowContext';

interface PlayerWindowProps {
  readonly playerWindow: OpenPlayerWindow;
  readonly layer: number;
  readonly stagger: number;
  readonly onActivate: (playerId: string) => void;
  readonly onClose: (playerId: string) => void;
}

const PROFILE_WINDOW_MIN_SIZE = { width: 760, height: 200 };

function windowTitle(state: PlayerProfileState, knownUsername: string, t: TFunction): string {
  switch (state.status) {
    case 'loaded':
      return state.profile.username || knownUsername;
    case 'loading':
      return knownUsername || t('player.loadingInfo');
    case 'failed':
      return knownUsername || t('player.loadFailed');
    case 'not-found':
      return knownUsername || t('player.notFound');
  }
}

function PlayerWindowBody({ playerWindow, state, onClose }: {
  readonly playerWindow: OpenPlayerWindow;
  readonly state: PlayerProfileState;
  readonly onClose: () => void;
}) {
  const { t } = useTranslation();
  const { openPlayerWindow } = usePlayerWindow();

  switch (state.status) {
    case 'loaded':
      return (
        <div className="space-y-6">
          <PlayerProfileHeader playerId={playerWindow.playerId} profile={state.profile} />
          <PlayerProfileTabs
            state={state}
            profile={state.profile}
            tabSelector="list"
            focus={playerWindow.focus}
            onOpenPlayer={openPlayerWindow}
          />
        </div>
      );
    case 'loading':
      return (
        <div className="flex h-64 items-center justify-center">
          <LoadingSpinner className="h-6 w-6 text-muted-foreground" />
        </div>
      );
    case 'failed':
      return <LoadFailure className="h-64 justify-center" message={t('player.loadFailed')} onRetry={state.retryLoad} />;
    case 'not-found':
      return (
        <div className="flex h-64 flex-col items-center justify-center gap-4">
          <p className="text-sm text-muted-foreground">{t('player.notFoundDesc')}</p>
          <Button variant="outline" onClick={onClose}>{t('common.close')}</Button>
        </div>
      );
  }
}

function PlayerWindow({ playerWindow, layer, stagger, onActivate, onClose }: PlayerWindowProps) {
  const { t } = useTranslation();
  const { playerId } = playerWindow;
  const activate = () => { onActivate(playerId); };
  const close = () => { onClose(playerId); };
  const state = usePlayerProfile(playerId);

  return (
    <ResizableWindow
      title={windowTitle(state, playerWindow.username, t)}
      layer={layer}
      stagger={stagger}
      minSize={PROFILE_WINDOW_MIN_SIZE}
      onActivate={activate}
      onClose={close}
      onRefresh={state.status === 'loaded' ? state.refresh : undefined}
      isRefreshing={state.playerQuery.isFetching || state.isLoadingLinkedAccounts || state.isSearchingLinkedAccounts}
    >
      <PlayerWindowBody playerWindow={playerWindow} state={state} onClose={close} />
    </ResizableWindow>
  );
}

export default memo(PlayerWindow);
