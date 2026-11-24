import { useQuery } from '@tanstack/react-query';
import { ServerRole } from '@proto/modl/v1/common_pb.ts';
import {
  ConnectedServersResponseSchema,
  ServerCompatibility,
  type ConnectedServer as ConnectedServerMessage,
} from '@proto/modl/v1/servers_pb.ts';
import { protoFetch } from '@/lib/proto-fetch';
import { tsToIso } from '@/lib/proto-ui';
import type { QueryGate } from './query-gate';

const CONNECTED_SERVERS_PATH = '/v1/panel/servers/connected';
const CONNECTED_SERVERS_REFRESH_MS = 3_000;

export type ConnectedServerRole = 'core' | 'member' | 'refused';

export type ServerVersionCompatibility = 'compatible' | 'plugin-older' | 'server-older' | 'unknown';

export interface ConnectedServer {
  instanceId: string;
  name: string;
  platform: string;
  pluginVersion: string;
  serverVersion: string;
  onlinePlayers: number;
  lastSeen: string | null;
  role: ConnectedServerRole;
  keyName: string | null;
  hubConnected: boolean;
  compatibility: ServerVersionCompatibility;
}

function toRole(role: ServerRole): ConnectedServerRole {
  switch (role) {
    case ServerRole.MEMBER:
      return 'member';
    case ServerRole.CORE:
      return 'core';
    case ServerRole.UNSPECIFIED:
      return 'refused';
  }
}

function toCompatibility(compatibility: ServerCompatibility): ServerVersionCompatibility {
  switch (compatibility) {
    case ServerCompatibility.COMPATIBLE:
      return 'compatible';
    case ServerCompatibility.PLUGIN_OLDER:
      return 'plugin-older';
    case ServerCompatibility.SERVER_OLDER:
      return 'server-older';
    case ServerCompatibility.UNKNOWN:
    case ServerCompatibility.UNSPECIFIED:
      return 'unknown';
  }
}

function toConnectedServer(server: ConnectedServerMessage): ConnectedServer {
  return {
    instanceId: server.instanceId,
    name: server.name,
    platform: server.platform,
    pluginVersion: server.pluginVersion,
    serverVersion: server.serverVersion,
    onlinePlayers: server.onlinePlayers,
    lastSeen: tsToIso(server.lastSeen),
    role: toRole(server.role),
    keyName: server.keyName ?? null,
    hubConnected: server.hubConnected,
    compatibility: toCompatibility(server.compatibility),
  };
}

export function useConnectedServers({ enabled = true }: QueryGate = {}) {
  return useQuery({
    queryKey: [CONNECTED_SERVERS_PATH],
    enabled,
    queryFn: async () => (await protoFetch(ConnectedServersResponseSchema, CONNECTED_SERVERS_PATH)).servers.map(toConnectedServer),
    refetchInterval: CONNECTED_SERVERS_REFRESH_MS,
    refetchIntervalInBackground: false,
    retry: false,
  });
}
