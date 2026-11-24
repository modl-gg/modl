import { useTranslation } from 'react-i18next';
import { Link } from 'wouter';
import { useIsSettingsPage } from '@/hooks/use-is-settings-page';
import { StatusBanner } from '@/components/ui/status-banner';
import { usePermissions } from '@/hooks/use-permissions';
import { useToast } from '@/hooks/use-toast';
import { settingsPagePath } from '@/lib/settings-access';
import { useDismissForwardingNotice, usePlayerAddressSettings, type ForwardingNotice } from '@/hooks/data/player-addresses';

const PLAYER_ADDRESS_SETTINGS_PAGE = settingsPagePath('player-addresses');

interface ForwardingNoticeBannerProps {
  notice: ForwardingNotice;
  settingsLink?: boolean;
}

function NoticeText({ notice }: { notice: ForwardingNotice }) {
  const { t } = useTranslation();
  if (notice.reason === 'sharedAddress') return <p>{t('settings.playerAddresses.forwardingShared')}</p>;
  return (
    <p>
      {notice.serverName === null
        ? t('settings.playerAddresses.forwardingNonPublicUnknown')
        : t('settings.playerAddresses.forwardingNonPublic', { server: notice.serverName })}
    </p>
  );
}

export function ForwardingNoticeBanner({ notice, settingsLink = false }: ForwardingNoticeBannerProps) {
  const { t } = useTranslation();
  const { toast } = useToast();
  const dismiss = useDismissForwardingNotice();
  if (!notice.showing) return null;

  return (
    <StatusBanner
      variant="warning"
      title={t('settings.playerAddresses.forwardingTitle')}
      dismissible={!dismiss.isPending}
      onDismiss={() => {
        dismiss.mutate(undefined, {
          onError: () => {
            toast({ title: t('toast.error'), description: t('settings.playerAddresses.saveFailed'), variant: 'destructive' });
          },
        });
      }}
    >
      <NoticeText notice={notice} />
      <p>{t('settings.playerAddresses.forwardingFix')}</p>
      {settingsLink && (
        <Link href={PLAYER_ADDRESS_SETTINGS_PAGE} className="underline">{t('settings.playerAddresses.openSettings')}</Link>
      )}
    </StatusBanner>
  );
}

export function AddressForwardingBanner() {
  const { isSuperAdmin } = usePermissions();
  const settings = usePlayerAddressSettings(isSuperAdmin);
  const onPlayerAddressesPage = useIsSettingsPage('player-addresses');
  const notice = settings.data?.forwardingNotice;
  if (!isSuperAdmin || !notice?.showing || onPlayerAddressesPage) return null;
  return <ForwardingNoticeBanner notice={notice} settingsLink />;
}
