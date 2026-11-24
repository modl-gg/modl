import { useTranslation } from 'react-i18next';
import { Link } from 'wouter';
import { StatusBanner } from '@/components/ui/status-banner';
import { usePermissions } from '@/hooks/use-permissions';
import { useToast } from '@/hooks/use-toast';
import { useDismissLookupChoice, usePlayerAddressSettings } from '@/hooks/data/player-addresses';
import { useIsSettingsPage } from '@/hooks/use-is-settings-page';
import { settingsPagePath } from '@/lib/settings-access';

export function LookupChoiceBanner() {
  const { t } = useTranslation();
  const { toast } = useToast();
  const { isSuperAdmin } = usePermissions();
  const settings = usePlayerAddressSettings(isSuperAdmin);
  const dismiss = useDismissLookupChoice();
  const onPlayerAddressesPage = useIsSettingsPage('player-addresses');
  if (!isSuperAdmin || onPlayerAddressesPage || settings.data?.lookupChoicePending !== true) return null;

  return (
    <StatusBanner
        variant="info"
        title={t('settings.playerAddresses.choiceBannerTitle')}
        dismissible={!dismiss.isPending}
        onDismiss={() => {
          dismiss.mutate(undefined, {
            onError: () => {
              toast({ title: t('toast.error'), description: t('settings.playerAddresses.saveFailed'), variant: 'destructive' });
            },
          });
        }}
      >
        <p>{t('settings.playerAddresses.choiceBannerText')}</p>
        <Link href={settingsPagePath('player-addresses')} className="underline">{t('settings.playerAddresses.choiceBannerAction')}</Link>
    </StatusBanner>
  );
}
