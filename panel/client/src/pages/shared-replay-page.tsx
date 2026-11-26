import { useRef, useState, type RefObject } from 'react';
import { useTranslation } from 'react-i18next';
import { useParams } from 'wouter';
import { BrandLogo } from '@/components/brand/BrandLogo';
import { SourceCodeLink } from '@/components/layout/SourceCodeLink';
import { DarkSpinner, DarkSurface, ReplayViewerSurface } from '@/components/replays/ReplayViewerSurface';
import { Button } from '@/components/ui/button';
import { useSharedReplay, type SharedReplay } from '@/hooks/data/replay-shares';
import { formatDateWithTime } from '@/utils/date-utils';

function SharedHeader({ replay }: { readonly replay: SharedReplay | null }) {
  const details = replay === null
    ? []
    : [replay.createdAt === null ? '' : formatDateWithTime(new Date(replay.createdAt)), replay.mcVersion].filter((part) => part !== '');
  return (
    <header className="flex h-12 shrink-0 items-center gap-3 border-b border-white/10 px-4">
      <BrandLogo icon="homepage" wordmarkClassName="text-xl" imageClassName="h-7 w-auto max-w-24 object-contain" />
      {replay !== null && (
        <div className="flex min-w-0 items-baseline gap-3">
          <h1 className="truncate text-sm font-semibold text-white">{replay.targetName}</h1>
          {details.length > 0 && <p className="hidden truncate text-xs text-white/60 sm:block">{details.join(' · ')}</p>}
        </div>
      )}
      <SourceCodeLink className="ml-auto shrink-0 text-white/40 hover:text-white/70" />
    </header>
  );
}

interface SharedViewerProps {
  readonly token: string;
  readonly replay: SharedReplay;
  readonly shortcutScope: RefObject<HTMLElement | null>;
}

function SharedViewer({ token, replay, shortcutScope }: SharedViewerProps) {
  const [attempt, setAttempt] = useState(0);
  return (
    <ReplayViewerSurface
      attempt={attempt}
      resetKey={token}
      source={{ replayUrl: replay.fileUrl, access: 'public', minecraftVersion: replay.mcVersion }}
      shortcutScope={shortcutScope}
      labeling={null}
      onRetry={() => { setAttempt((value) => value + 1); }}
    />
  );
}

function SharedBody({ token, shortcutScope }: { readonly token: string; readonly shortcutScope: RefObject<HTMLElement | null> }) {
  const { t } = useTranslation();
  const shared = useSharedReplay(token);
  if (shared.isPending) return <DarkSpinner />;
  if (shared.isError) {
    return (
      <DarkSurface>
        {t('replayViewer.failure.failed')}
        <Button type="button" variant="secondary" size="sm" onClick={() => { void shared.refetch(); }}>{t('common.retry')}</Button>
      </DarkSurface>
    );
  }
  if (shared.data === null) return <DarkSurface>{t('replayViewer.share.unavailable')}</DarkSurface>;
  return <SharedViewer token={token} replay={shared.data} shortcutScope={shortcutScope} />;
}

export default function SharedReplayPage() {
  const { token = '' } = useParams<{ token?: string }>();
  const shared = useSharedReplay(token);
  const pageRef = useRef<HTMLDivElement>(null);
  return (
    <div ref={pageRef} tabIndex={-1} className="flex h-dvh w-full flex-col overflow-hidden bg-black text-white outline-none">
      <SharedHeader replay={shared.data ?? null} />
      <main className="min-h-0 flex-1">
        <SharedBody token={token} shortcutScope={pageRef} />
      </main>
    </div>
  );
}
