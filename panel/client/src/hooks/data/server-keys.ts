import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { create } from '@bufbuild/protobuf';
import {
  CreateServerKeyRequestSchema,
  CreateServerKeyResponseSchema,
  ProxiedServerState,
  ServerKeySchema,
  ServerKeyScope,
  ServerKeysResponseSchema,
  UpdateServerKeyRequestSchema,
  type ProxiedServerStatus,
  type ServerKey as ServerKeyMessage,
  type ServerKeysResponse,
} from '@proto/modl/v1/servers_pb.ts';
import { apiFetch } from '@/lib/api';
import { protoFetch, protoHttpErrorOf, protoSend } from '@/lib/proto-fetch';
import { tsToIso } from '@/lib/proto-ui';
import type { QueryGate } from './query-gate';

const SERVER_KEYS_PATH = '/v1/panel/settings/server-keys';
const SERVER_KEYS_REFRESH_MS = 10_000;

export type ServerKeyRole = 'behind-proxy' | 'core';

export interface ServerKey {
  id: string;
  name: string;
  role: ServerKeyRole;
  keyHint: string;
  createdAt: string | null;
  lastSeenAt: string | null;
  connected: boolean;
  allowProxyCommands: boolean;
}

export type ProxiedServerKeyState = 'no-key' | 'not-connected' | 'connected';

export interface ProxiedServer {
  name: string;
  address: string;
  state: ProxiedServerKeyState;
  keyId: string | null;
}

export interface ServerKeyListing {
  keys: ServerKey[];
  proxiedServers: ProxiedServer[];
}

export interface NewServerKey {
  name: string;
  role: ServerKeyRole;
  allowProxyCommands: boolean;
}

export interface IssuedServerKey {
  name: string;
  role: ServerKeyRole;
  secret: string;
  panelUrl: string;
}

function keyPath(id: string): string {
  return `${SERVER_KEYS_PATH}/${encodeURIComponent(id)}`;
}

function toRole(scope: ServerKeyScope): ServerKeyRole {
  return scope === ServerKeyScope.BEHIND_PROXY ? 'behind-proxy' : 'core';
}

function toServerKey(key: ServerKeyMessage): ServerKey {
  return {
    id: key.id,
    name: key.name,
    role: toRole(key.scope),
    keyHint: key.keyHint,
    createdAt: tsToIso(key.createdAt),
    lastSeenAt: tsToIso(key.lastSeenAt),
    connected: key.connected,
    allowProxyCommands: key.allowProxyCommands,
  };
}

const PROXIED_SERVER_KEY_STATES: ReadonlyMap<ProxiedServerState, ProxiedServerKeyState> = new Map([
  [ProxiedServerState.NO_KEY, 'no-key'],
  [ProxiedServerState.NOT_CONNECTED, 'not-connected'],
  [ProxiedServerState.CONNECTED, 'connected'],
]);

function toProxiedServer(status: ProxiedServerStatus): ProxiedServer[] {
  const state = PROXIED_SERVER_KEY_STATES.get(status.state);
  if (status.server === undefined || state === undefined) return [];
  return [{ name: status.server.name, address: status.server.address, state, keyId: status.keyId ?? null }];
}

function toServerKeyListing(response: ServerKeysResponse): ServerKeyListing {
  return { keys: response.keys.map(toServerKey), proxiedServers: response.proxiedServers.flatMap(toProxiedServer) };
}

export function useServerKeys({ enabled = true }: QueryGate = {}) {
  return useQuery({
    queryKey: [SERVER_KEYS_PATH],
    enabled,
    queryFn: async () => toServerKeyListing(await protoFetch(ServerKeysResponseSchema, SERVER_KEYS_PATH)),
    refetchInterval: SERVER_KEYS_REFRESH_MS,
    refetchIntervalInBackground: false,
    retry: false,
  });
}

export function useCreateServerKey() {
  const queryClient = useQueryClient();
  return useMutation({
    gcTime: 0,
    mutationFn: async ({ name, role, allowProxyCommands }: NewServerKey): Promise<IssuedServerKey> => {
      const behindProxy = role === 'behind-proxy';
      const request = create(CreateServerKeyRequestSchema, {
        name,
        scope: behindProxy ? ServerKeyScope.BEHIND_PROXY : ServerKeyScope.FULL,
        allowProxyCommands: behindProxy && allowProxyCommands,
      });
      const { secret, panelUrl } = await protoSend('POST', SERVER_KEYS_PATH, CreateServerKeyRequestSchema, request,
        CreateServerKeyResponseSchema);
      return { name, role, secret, panelUrl };
    },
    onSuccess: async () => queryClient.invalidateQueries({ queryKey: [SERVER_KEYS_PATH] }),
  });
}

export function useSetProxyCommandsAllowed() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, allowed }: { id: string; allowed: boolean }) => toServerKey(await protoSend(
      'PATCH',
      keyPath(id),
      UpdateServerKeyRequestSchema,
      create(UpdateServerKeyRequestSchema, { allowProxyCommands: allowed }),
      ServerKeySchema,
    )),
    onSettled: async () => queryClient.invalidateQueries({ queryKey: [SERVER_KEYS_PATH] }),
  });
}

export function useRevokeServerKey() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => {
      const response = await apiFetch(keyPath(id), { method: 'DELETE' });
      if (!response.ok) throw await protoHttpErrorOf(response);
    },
    onSettled: async () => queryClient.invalidateQueries({ queryKey: [SERVER_KEYS_PATH] }),
  });
}
