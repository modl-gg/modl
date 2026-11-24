import { useMemo, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { useLocation, useRoute } from 'wouter';
import { ArrowLeft } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import PageContainer from '@/components/layout/PageContainer';
import { useIsMobile } from '@/hooks/use-mobile';
import { PlayerProfileHeader } from '@/components/player/PlayerProfileHeader';
import { PlayerProfileTabs } from '@/components/player/PlayerProfileTabs';
import { usePlayerProfile } from '@/components/player/use-player-profile';
import { LoadFailure } from '@/components/ui/load-failure';
import { useCapturedSearchParam } from '@/hooks/use-search-param';
import { LOOKUP_PATH, PUNISHMENT_PARAM, pagePunishmentFocus, playerPagePath } from '@/lib/player-links';

function PageFrame({ heading, children }: { readonly heading: ReactNode; readonly children: ReactNode }) {
  const { t } = useTranslation();
  const [, navigate] = useLocation();
  return (
    <PageContainer>
      <div className="mb-4 flex items-center gap-2">
        <Button variant="ghost" size="icon" onClick={() => navigate(LOOKUP_PATH)} className="-ml-2" aria-label={t('player.returnToLookup')}>
          <ArrowLeft className="h-5 w-5" />
        </Button>
        {heading}
      </div>
      {children}
    </PageContainer>
  );
}

function PlayerDetailSkeleton() {
  return (
    <PageFrame heading={<Skeleton className="h-7 w-40" />}>
      <div className="flex items-center gap-4 mb-6">
        <Skeleton className="h-16 w-16 rounded-lg" />
        <div className="space-y-2">
          <Skeleton className="h-5 w-40" />
          <Skeleton className="h-4 w-24" />
        </div>
      </div>
      <div className="space-y-4">
        <Skeleton className="h-10 w-full max-w-md" />
        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
          <Skeleton className="h-32 w-full" />
          <Skeleton className="h-32 w-full" />
        </div>
        <Skeleton className="h-48 w-full" />
      </div>
    </PageFrame>
  );
}

function PlayerLoadFailed({ onRetry }: { readonly onRetry: () => void }) {
  const { t } = useTranslation();
  return (
    <PageFrame heading={null}>
      <LoadFailure className="py-12" message={t('player.loadFailed')} onRetry={onRetry} />
    </PageFrame>
  );
}

function PlayerNotFound() {
  const { t } = useTranslation();
  const [, navigate] = useLocation();
  return (
    <PageFrame heading={<h1 className="text-xl font-semibold">{t('player.notFound')}</h1>}>
      <div className="flex flex-col items-center gap-4 py-12">
        <p className="text-sm text-muted-foreground">{t('player.notFoundDesc')}</p>
        <Button variant="outline" onClick={() => navigate(LOOKUP_PATH)}>{t('player.returnToLookup')}</Button>
      </div>
    </PageFrame>
  );
}

const PlayerDetailPage = () => {
  const { t } = useTranslation();
  const [, params] = useRoute('/panel/player/:uuid');
  const [, navigate] = useLocation();
  const isMobile = useIsMobile();
  const playerId = params?.uuid ?? '';
  const state = usePlayerProfile(playerId);
  const focusPunishmentId = useCapturedSearchParam(PUNISHMENT_PARAM);
  const focus = useMemo(() => pagePunishmentFocus(focusPunishmentId), [focusPunishmentId]);

  switch (state.status) {
    case 'loading':
      return <PlayerDetailSkeleton />;
    case 'failed':
      return <PlayerLoadFailed onRetry={state.retryLoad} />;
    case 'not-found':
      return <PlayerNotFound />;
    case 'loaded':
      return (
        <PageFrame heading={<h1 className="min-w-0 truncate text-xl font-semibold">{state.profile.username || t('common.unknown')}</h1>}>
          <div className="space-y-6">
            <PlayerProfileHeader playerId={playerId} profile={state.profile} />
            <PlayerProfileTabs
              key={playerId}
              state={state}
              profile={state.profile}
              tabSelector={isMobile ? 'select' : 'list'}
              focus={focus}
              onOpenPlayer={(uuid) => { navigate(playerPagePath(uuid)); }}
            />
          </div>
        </PageFrame>
      );
  }
};

export default PlayerDetailPage;
