import type { ConnectedServer } from '@/hooks/data/servers';
import type { ServerKey } from '@/hooks/data/server-keys';

export interface ServerRow {
  id: string;
  serverKey: ServerKey | null;
  connections: ConnectedServer[];
}

export function serverRows(keys: readonly ServerKey[], connections: readonly ConnectedServer[]): ServerRow[] {
  const keyNames = new Set(keys.map((serverKey) => serverKey.name));
  const keyRows = keys.map((serverKey) => ({
    id: `key-${serverKey.id}`,
    serverKey,
    connections: connections.filter((connection) => connection.keyName === serverKey.name),
  }));
  const keylessRows = connections
    .filter((connection) => connection.keyName === null || !keyNames.has(connection.keyName))
    .map((connection) => ({ id: `connection-${connection.instanceId || connection.name}`, serverKey: null, connections: [connection] }));
  return [...keylessRows, ...keyRows];
}

export function isRowConnected(row: ServerRow): boolean {
  return row.connections.some((connection) => connection.role !== 'refused') || row.serverKey?.connected === true;
}

export function lastUsedAt(row: ServerRow): string | null {
  if (row.connections.length > 0 || row.serverKey?.connected === true) return null;
  return row.serverKey?.lastSeenAt ?? null;
}
