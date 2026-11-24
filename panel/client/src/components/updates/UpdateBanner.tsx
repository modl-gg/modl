import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'wouter';
import { useIsSettingsPage } from '@/hooks/use-is-settings-page';
import { Button } from '@/components/ui/button';
import { StatusBanner } from '@/components/ui/status-banner';
import { usePermissions } from '@/hooks/use-permissions';
import { useToast } from '@/hooks/use-toast';
import { useDismissUpdate, useUpdateStatus } from '@/hooks/data/updates';
import { canUpdateNow } from '@/lib/update-instructions';
import { settingsPagePath } from '@/lib/settings-access';
import { UpdateNowDialog } from './UpdateNowDialog';
import { updateErrorKey } from './update-errors';
import { useInstalledVersionReload } from './use-installed-version-reload';

const UPDATES_SETTINGS_PATH = settingsPagePath('updates');

export function UpdateBanner() {
  const { isSuperAdmin } = usePermissions();
  const onUpdatesPage = useIsSettingsPage('updates');
  if (!isSuperAdmin || onUpdatesPage) return null;
  return <SuperAdminUpdateBanner />;
}

function SuperAdminUpdateBanner() {
  const { t } = useTranslation();
  const { toast } = useToast();
  const status = useUpdateStatus();
  const dismiss = useDismissUpdate();
  const [dialogOpen, setDialogOpen] = useState(false);
  useInstalledVersionReload(status.data);
  const current = status.data;
  if (current === undefined) return null;
  const release = current.release;
  const showRelease = release !== null && !current.dismissed;

  return (
    <>
      {current.incompatibleServers > 0 && (
        <StatusBanner variant="warning" title={t('updates.banner.incompatible', { servers: current.incompatibleServers })}>
          <Link href={UPDATES_SETTINGS_PATH} className="underline">{t('updates.banner.howTo')}</Link>
        </StatusBanner>
      )}
      {showRelease && (
        <StatusBanner
          variant={release.critical ? 'error' : 'info'}
          title={t(release.critical ? 'updates.banner.critical' : 'updates.banner.available', { version: release.version })}
          action={
            <div className="flex flex-wrap gap-2">
              {canUpdateNow(release) ? (
                <Button
                  size="sm"
                  onClick={() => {
                    setDialogOpen(true);
                  }}
                >
                  {t('updates.banner.updateNow')}
                </Button>
              ) : (
                <Button size="sm" asChild>
                  <Link href={UPDATES_SETTINGS_PATH}>{t('updates.banner.howTo')}</Link>
                </Button>
              )}
              <Button
                size="sm"
                variant="outline"
                disabled={dismiss.isPending}
                onClick={() => {
                  dismiss.mutate(release.version, {
                    onError: (error) => {
                      toast({ title: t(updateErrorKey(error)), variant: 'destructive' });
                    },
                  });
                }}
              >
                {t('updates.banner.dismiss')}
              </Button>
            </div>
          }
        >
          {t('updates.banner.current', { version: current.currentVersion })}
        </StatusBanner>
      )}
      {showRelease && (
        <UpdateNowDialog status={current} release={release} open={dialogOpen} onOpenChange={setDialogOpen} />
      )}
    </>
  );
}
