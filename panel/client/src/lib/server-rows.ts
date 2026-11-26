import type { ConnectedServer } from '@/hooks/data/servers';
import type { ProxiedServer, ServerKey, ServerKeyListing } from '@/hooks/data/server-keys';

export interface ServerRow {
  id: string;
  serverKey: ServerKey | null;
  proxied: ProxiedServer | null;
  connections: ConnectedServer[];
}

export function serverRows({ keys, proxiedServers }: ServerKeyListing, connections: readonly ConnectedServer[]): ServerRow[] {
  const keyNames = new Set(keys.map((serverKey) => serverKey.name));
  const proxiedByKey = new Map(proxiedServers.flatMap((proxied) => (proxied.keyId === null ? [] : [[proxied.keyId, proxied] as const])));
  const keyRows = keys.map((serverKey) => ({
    id: `key-${serverKey.id}`,
    serverKey,
    proxied: proxiedByKey.get(serverKey.id) ?? null,
    connections: connections.filter((connection) => connection.keyName === serverKey.name),
  }));
  const keylessRows = connections
    .filter((connection) => connection.keyName === null || !keyNames.has(connection.keyName))
    .map((connection) => ({
      id: `connection-${connection.instanceId || connection.name}`,
      serverKey: null,
      proxied: null,
      connections: [connection],
    }));
  const proxiedRows = proxiedServers
    .filter((proxied) => proxied.keyId === null)
    .map((proxied) => ({ id: `proxied-${proxied.name}`, serverKey: null, proxied, connections: [] }));
  return [...keylessRows, ...proxiedRows, ...keyRows];
}

export function isRowConnected(row: ServerRow): boolean {
  return row.connections.some((connection) => connection.role !== 'refused') || row.serverKey?.connected === true;
}

export function lastUsedAt(row: ServerRow): string | null {
  if (row.connections.length > 0 || row.serverKey?.connected === true) return null;
  return row.serverKey?.lastSeenAt ?? null;
}

export function needsServerKey(row: ServerRow): row is ServerRow & { proxied: ProxiedServer } {
  return row.serverKey === null && row.proxied?.state === 'no-key';
}
