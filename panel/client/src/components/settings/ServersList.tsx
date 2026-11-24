import { useTranslation } from 'react-i18next';
import { Loader2, Trash2 } from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import { IconAction } from '@/components/ui/icon-action';
import { Label } from '@/components/ui/label';
import { LoadingSpinner } from '@/components/ui/loading-spinner';
import { Notice } from '@/components/ui/notice';
import { StatusBadge } from '@/components/ui/status-badge';
import { Switch } from '@/components/ui/switch';
import { useConfirmation } from '@/hooks/use-confirmation';
import { usePermissions } from '@/hooks/use-permissions';
import { useToast } from '@/hooks/use-toast';
import { useConnectedServers, type ConnectedServer, type ServerVersionCompatibility } from '@/hooks/data/servers';
import {
  useRevokeServerKey,
  useServerKeys,
  useSetProxyCommandsAllowed,
  type ServerKey,
  type ServerKeyRole,
} from '@/hooks/data/server-keys';
import { isRowConnected, lastUsedAt, serverRows, type ServerRow } from '@/lib/server-rows';

const KEY_ROLE_KEYS: Record<ServerKeyRole, string> = {
  'behind-proxy': 'settings.servers.roleBehindProxy',
  core: 'settings.servers.roleCore',
};

const PLATFORM_LABEL_KEYS: ReadonlyMap<string, string> = new Map([
  ['SPIGOT', 'settings.servers.platformPaper'],
  ['VELOCITY', 'settings.servers.platformVelocity'],
  ['BUNGEECORD', 'settings.servers.platformBungee'],
]);

const COMPATIBILITY_KEYS: Record<ServerVersionCompatibility, string | null> = {
  compatible: null,
  'plugin-older': 'settings.servers.compatibilityPluginOlder',
  'server-older': 'settings.servers.compatibilityServerOlder',
  unknown: null,
};

function compatibilityKey(connection: ConnectedServer): string | null {
  if (connection.role === 'refused' && connection.compatibility === 'unknown') return 'settings.servers.compatibilityUnknown';
  return COMPATIBILITY_KEYS[connection.compatibility];
}

function formatDateTime(iso: string, language: string): string {
  return new Intl.DateTimeFormat(language, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(iso));
}

function ConnectionDetails({ connection }: { connection: ConnectedServer }) {
  const { t } = useTranslation();
  const platformKey = PLATFORM_LABEL_KEYS.get(connection.platform.toUpperCase());
  const issueKey = compatibilityKey(connection);
  const parts = [
    connection.platform === '' ? null : platformKey === undefined ? connection.platform : t(platformKey),
    t('settings.servers.pluginVersionValue', { version: connection.pluginVersion || t('common.unknown') }),
    connection.serverVersion === '' ? null : connection.serverVersion,
    t('settings.servers.playersOnline', { number: connection.onlinePlayers }),
  ].filter((part) => part !== null);

  return (
    <div className="flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
      <span className="break-all">{parts.join(' · ')}</span>
      {connection.role === 'refused' && <Badge variant="destructive">{t('settings.servers.refused')}</Badge>}
      {issueKey !== null && <Badge variant="destructive">{t(issueKey)}</Badge>}
    </div>
  );
}

function RevokeKeyButton({ serverKey }: { serverKey: ServerKey }) {
  const { t } = useTranslation();
  const { toast } = useToast();
  const revokeKey = useRevokeServerKey();
  const confirmation = useConfirmation();

  const revoke = async () => {
    const confirmed = await confirmation.confirm({
      title: t('common.areYouSure'),
      description: t('settings.servers.revokeConfirm', { name: serverKey.name }),
      action: t('settings.servers.revoke'),
      destructive: true,
    });
    if (!confirmed) return;
    revokeKey.mutate(serverKey.id, {
      onError: () => {
        toast({ title: t('toast.error'), description: t('settings.servers.revokeFailed'), variant: 'destructive' });
      },
    });
  };

  return (
    <>
      <IconAction icon={Trash2} label={t('settings.servers.revoke')} destructive disabled={revokeKey.isPending} onClick={() => { void revoke(); }} />
      {confirmation.dialog}
    </>
  );
}

function ProxyCommandsSwitch({ serverKey }: { serverKey: ServerKey }) {
  const { t } = useTranslation();
  const { toast } = useToast();
  const setAllowed = useSetProxyCommandsAllowed();
  const inputId = `server-key-proxy-commands-${serverKey.id}`;

  const change = (allowed: boolean) => {
    setAllowed.mutate({ id: serverKey.id, allowed }, {
      onError: () => {
        toast({ title: t('toast.error'), description: t('settings.servers.proxyCommandsFailed'), variant: 'destructive' });
      },
    });
  };

  return (
    <div className="flex items-center gap-2">
      <Switch id={inputId} checked={serverKey.allowProxyCommands} disabled={setAllowed.isPending} onCheckedChange={change} />
      <Label htmlFor={inputId} className="text-sm font-normal">{t('settings.servers.allowProxyCommands')}</Label>
    </div>
  );
}

function LastUsed({ at }: { at: string }) {
  const { t, i18n } = useTranslation();
  return <p className="text-xs text-muted-foreground">{t('settings.servers.keyLastSeen', { time: formatDateTime(at, i18n.language) })}</p>;
}

function ServerRowItem({ row }: { row: ServerRow }) {
  const { t } = useTranslation();
  const { serverKey, connections } = row;
  const name = serverKey?.name ?? connections[0]?.name ?? '';
  const connected = isRowConnected(row);
  const lastUsed = lastUsedAt(row);
  const hasDetails = connections.length > 0 || lastUsed !== null || serverKey?.role === 'behind-proxy';

  return (
    <li className="space-y-2 px-4 py-3">
      <div className="flex min-h-8 flex-wrap items-center gap-x-3 gap-y-1">
        <span className="break-all text-sm font-medium">{name || t('settings.servers.unnamed')}</span>
        {serverKey !== null && <span className="text-xs text-muted-foreground">{t(KEY_ROLE_KEYS[serverKey.role])}</span>}
        <div className="ml-auto flex items-center gap-2">
          <StatusBadge intent={connected ? 'success' : 'neutral'}>
            {t(connected ? 'settings.servers.connected' : 'settings.servers.notConnected')}
          </StatusBadge>
          {serverKey !== null && <RevokeKeyButton serverKey={serverKey} />}
        </div>
      </div>
      {hasDetails && (
        <div className="space-y-2">
          {connections.map((connection) => (
            <ConnectionDetails key={connection.instanceId || connection.name} connection={connection} />
          ))}
          {lastUsed !== null && <LastUsed at={lastUsed} />}
          {serverKey?.role === 'behind-proxy' && <ProxyCommandsSwitch serverKey={serverKey} />}
        </div>
      )}
    </li>
  );
}

export default function ServersList() {
  const { t } = useTranslation();
  const { isSuperAdmin } = usePermissions();
  const keys = useServerKeys({ enabled: isSuperAdmin });
  const connections = useConnectedServers({ enabled: isSuperAdmin });

  if (!isSuperAdmin) return null;

  const renderBody = () => {
    if (keys.isLoading || connections.isLoading) {
      return (
        <div className="flex justify-center py-4">
          <LoadingSpinner className="h-5 w-5 text-muted-foreground" />
        </div>
      );
    }
    if (keys.data === undefined || connections.data === undefined) {
      return <Notice variant="error" announce>{t('settings.servers.loadFailed')}</Notice>;
    }
    const rows = serverRows(keys.data, connections.data);
    if (rows.length === 0) {
      return (
        <p className="flex items-center gap-2 py-4 text-sm text-muted-foreground">
          <Loader2 className="h-4 w-4 shrink-0 animate-spin" aria-hidden="true" />
          {t('settings.servers.waiting')}
        </p>
      );
    }
    return (
      <ul className="divide-y rounded-md border">
        {rows.map((row) => <ServerRowItem key={row.id} row={row} />)}
      </ul>
    );
  };

  return (
    <section aria-label={t('settings.servers.listTitle')}>
      {renderBody()}
    </section>
  );
}
