import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Loader2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Label } from '@/components/ui/label';
import { Notice } from '@/components/ui/notice';
import { Switch } from '@/components/ui/switch';
import { useToast } from '@/hooks/use-toast';
import {
  isInstalling,
  useCheckForUpdates,
  useSaveUpdateSettings,
  useUpdateStatus,
  type RefusedServer,
  type UpdateStatus,
} from '@/hooks/data/updates';
import { canUpdateNow } from '@/lib/update-instructions';
import { UpdateInstructions } from '@/components/updates/UpdateInstructions';
import { UpdateNowDialog } from '@/components/updates/UpdateNowDialog';
import { UpdateProgress } from '@/components/updates/UpdateProgress';
import { updateErrorKey } from '@/components/updates/update-errors';
import { useInstalledVersionReload } from '@/components/updates/use-installed-version-reload';
import { SettingsLoading, SettingsSection, SettingsSections } from './layout/SettingsSections';
import { useSettingsDraft } from './save/use-settings-draft';

const REFUSED_KEYS: Record<RefusedServer['compatibility'], string> = {
  pluginOlder: 'settings.servers.compatibilityPluginOlder',
  serverOlder: 'settings.servers.compatibilityServerOlder',
  unknown: 'settings.servers.compatibilityUnknown',
};

function formatDateTime(iso: string | null, language: string): string | null {
  return iso === null ? null : new Intl.DateTimeFormat(language, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(iso));
}

function CheckSummary({ status }: { status: UpdateStatus }) {
  const { t, i18n } = useTranslation();
  const lastChecked = formatDateTime(status.lastCheckedAt, i18n.language);
  const failure = status.lastOutcome === 'succeeded' ? null : status.lastOutcome;
  return (
    <div className="space-y-1 text-sm">
      <p>
        <span className="text-muted-foreground">{t('settings.updates.lastChecked')}: </span>
        {lastChecked ?? t('settings.updates.never')}
      </p>
      {failure !== null && <Notice variant="warning">{t(`settings.updates.outcome.${failure}`)}</Notice>}
    </div>
  );
}

interface UpdateCheckPreferences {
  checksEnabled: boolean;
  includePrereleases: boolean;
}

function preferencesOf(status: UpdateStatus): UpdateCheckPreferences {
  return { checksEnabled: status.checksEnabled, includePrereleases: status.includePrereleases };
}

function CheckSwitches({ status }: { status: UpdateStatus }) {
  const { t } = useTranslation();
  const save = useSaveUpdateSettings();
  const draft = useSettingsDraft({
    snapshot: status,
    fallback: preferencesOf(status),
    valueOf: preferencesOf,
    save: async (preferences) => save.mutateAsync(preferences),
    describeRejection: (error) => t(updateErrorKey(error)),
  });

  return (
    <div className="space-y-4">
      <div className="flex items-start justify-between gap-4">
        <div className="space-y-1">
          <Label htmlFor="update-checks">{t('settings.updates.automatic')}</Label>
          <p className="text-xs text-muted-foreground">
            {status.checksManagedByConfig ? t('settings.updates.managedByConfig') : t('settings.updates.automaticHint')}
          </p>
        </div>
        <Switch
          id="update-checks"
          checked={draft.value.checksEnabled}
          disabled={status.checksManagedByConfig}
          onCheckedChange={(checksEnabled) => { draft.update((current) => ({ ...current, checksEnabled })); }}
        />
      </div>
      <div className="flex items-start justify-between gap-4">
        <Label htmlFor="update-prereleases">{t('settings.updates.prereleases')}</Label>
        <Switch
          id="update-prereleases"
          checked={draft.value.includePrereleases}
          onCheckedChange={(includePrereleases) => { draft.update((current) => ({ ...current, includePrereleases })); }}
        />
      </div>
    </div>
  );
}

function RefusedServers({ servers }: { servers: RefusedServer[] }) {
  const { t } = useTranslation();
  if (servers.length === 0) return null;
  return (
    <div className="space-y-2">
      <Notice variant="warning">{t('updates.banner.incompatible', { servers: servers.length })}</Notice>
      <ul className="rounded border text-sm">
        {servers.map((server) => (
          <li key={`${server.platform}-${server.pluginVersion}-${server.refusedAt ?? ''}`}
            className="flex items-center justify-between gap-2 border-b px-3 py-1 last:border-b-0">
            <span>{server.platform}</span>
            <span className="font-mono text-xs">{server.pluginVersion}</span>
            <span className="text-xs text-muted-foreground">{t('settings.servers.refused')} · {t(REFUSED_KEYS[server.compatibility])}</span>
          </li>
        ))}
      </ul>
    </div>
  );
}

function LastFailure({ status }: { status: UpdateStatus }) {
  const { t } = useTranslation();
  const failure = status.lastFailure;
  if (failure === null) return null;
  return (
    <Notice variant="error" title={t('updates.failure.rolledBack', { version: failure.toVersion, from: failure.fromVersion })}>
      {failure.databaseRestored ? t('updates.failure.restored') : null}
    </Notice>
  );
}

export default function UpdatesSettings() {
  const { t } = useTranslation();
  const { toast } = useToast();
  const status = useUpdateStatus();
  const check = useCheckForUpdates();
  const [dialogOpen, setDialogOpen] = useState(false);
  useInstalledVersionReload(status.data);
  const current = status.data;
  if (current === undefined) {
    return status.isError ? <Notice variant="error">{t('updates.error.generic')}</Notice> : <SettingsLoading />;
  }
  const release = current.release;
  const busy = isInstalling(current);

  return (
    <SettingsSections>
      <SettingsSection>
        <dl className="grid grid-cols-[auto_1fr] gap-x-6 gap-y-1 text-sm">
          <dt className="text-muted-foreground">{t('settings.updates.current')}</dt>
          <dd className="font-mono">{current.currentVersion}</dd>
          {(release !== null || current.lastCheckedAt !== null) && (
            <>
              <dt className="text-muted-foreground">{t('settings.updates.latest')}</dt>
              <dd>
                {release === null ? t('settings.updates.upToDate') : (
                  <span className="flex flex-wrap items-baseline gap-x-3">
                    <span className="font-mono">{release.version}</span>
                    <a href={release.releaseUrl} target="_blank" rel="noreferrer" className="underline">
                      {t('settings.updates.releaseNotes')}
                    </a>
                  </span>
                )}
              </dd>
            </>
          )}
        </dl>
        {release?.critical === true && (
          <Notice variant="error" title={t('updates.banner.critical', { version: release.version })} />
        )}
        <LastFailure status={current} />
        <UpdateProgress status={current} unreachable={status.isError} />
        {release !== null && (
          <div className="space-y-3">
            {canUpdateNow(release) && (
              <div>
                <Button
                  disabled={busy}
                  onClick={() => {
                    setDialogOpen(true);
                  }}
                >
                  {t('updates.banner.updateNow')}
                </Button>
              </div>
            )}
            <UpdateInstructions deployment={current.deployment} release={release} />
            <UpdateNowDialog status={current} release={release} open={dialogOpen} onOpenChange={setDialogOpen} />
          </div>
        )}
      </SettingsSection>
      <SettingsSection>
        <div className="flex flex-wrap items-center justify-between gap-3">
          <CheckSummary status={current} />
          <Button
            variant="outline"
            disabled={check.isPending || busy}
            onClick={() => {
              check.mutate(undefined, {
                onError: (error) => {
                  toast({ title: t(updateErrorKey(error)), variant: 'destructive' });
                },
              });
            }}
          >
            {check.isPending && <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" />}
            {t('settings.updates.checkNow')}
          </Button>
        </div>
        <CheckSwitches status={current} />
        <RefusedServers servers={current.refusedServers} />
      </SettingsSection>
    </SettingsSections>
  );
}
