import { useTranslation } from 'react-i18next';
import { Check, Copy } from 'lucide-react';
import { BrandLogo, ModlWordmark, useBrandIconUrl } from '@/components/brand/BrandLogo';
import { useCopyToClipboard } from '@/hooks/use-copy-to-clipboard';
import type { PublicHomepage } from '@/hooks/data/homepage-settings';
import { usePublicSettings } from '@/hooks/use-public-settings';
import { cn } from '@/lib/utils';

function ServerAddress({ address }: { address: string }) {
  const { t } = useTranslation();
  const { copy, copiedValue } = useCopyToClipboard(t('home.copyAddressFailed'));
  const copied = copiedValue === address;
  const Icon = copied ? Check : Copy;
  return (
    <button
      type="button"
      data-modl="server-address"
      aria-label={t('home.copyAddress', { address })}
      title={t('home.copyAddress', { address })}
      onClick={(event) => { void copy(address, event.currentTarget); }}
      className="inline-flex max-w-full items-center gap-2.5 rounded-full border border-border/70 bg-card/70 px-4 py-2 text-sm font-medium shadow-sm backdrop-blur-sm transition-colors hover:bg-card focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
    >
      <span className="truncate font-mono">{address}</span>
      <Icon className={`h-4 w-4 shrink-0 ${copied ? 'text-success' : 'text-muted-foreground'}`} aria-hidden="true" />
      <span className="sr-only" aria-live="polite">{copied ? t('home.addressCopied') : ''}</span>
    </button>
  );
}

function PlayerCount({ players }: { players: number }) {
  const { t } = useTranslation();
  return (
    <span data-modl="player-count" className="inline-flex items-center gap-2 text-sm text-muted-foreground">
      <span className={cn('h-2 w-2 rounded-full', players > 0 ? 'bg-success' : 'bg-muted-foreground/50')} aria-hidden="true" />
      {t('home.playersOnline', { count: players })}
    </span>
  );
}

export function HomeHero({ homepage }: { homepage: PublicHomepage | undefined }) {
  const { data: settings } = usePublicSettings();
  const serverName = settings?.serverDisplayName ?? '';
  const hasIcon = useBrandIconUrl('homepage') !== '';
  const tagline = homepage?.tagline ?? '';
  const address = homepage?.serverAddress ?? '';
  const players = homepage?.onlinePlayers;
  const hasStatus = address !== '' || players !== undefined;

  return (
    <div className="flex flex-col items-center text-center">
      {hasIcon && (
        <div data-modl="logo" className="mb-6 h-16 w-16 sm:h-20 sm:w-20">
          <BrandLogo icon="homepage" decorative={serverName !== ''} wordmarkClassName="text-4xl" imageClassName="h-full w-full object-contain" />
        </div>
      )}
      <h1 data-modl="server-name" className="max-w-full break-words text-3xl font-bold tracking-tight sm:text-5xl">
        {serverName === '' ? <ModlWordmark className="text-5xl" /> : serverName}
      </h1>
      {tagline !== '' && (
        <p data-modl="tagline" className="mt-4 max-w-xl text-base text-muted-foreground sm:text-lg">{tagline}</p>
      )}
      {hasStatus && (
        <div className="mt-7 flex max-w-full flex-wrap items-center justify-center gap-x-5 gap-y-3">
          {address !== '' && <ServerAddress address={address} />}
          {players !== undefined && <PlayerCount players={players} />}
        </div>
      )}
    </div>
  );
}
