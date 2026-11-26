import { Endpoints } from "./contract";
import { HttpStatus, jsonArray, jsonField, jsonString, type HttpResult } from "./http";
import { InstanceError, type InstanceClient } from "./instance";

export const ServerKeyScopes = {
  behindProxy: "SERVER_KEY_SCOPE_BEHIND_PROXY",
  full: "SERVER_KEY_SCOPE_FULL",
} as const;
export type ServerKeyScope = (typeof ServerKeyScopes)[keyof typeof ServerKeyScopes];

export interface CreatedServerKey {
  id: string;
  name: string;
  secret: string;
}

export interface ListedServerKey {
  id: string;
  name: string;
  connected: boolean;
  allowProxyCommands: boolean;
}

export async function createServerKey(
  instance: InstanceClient,
  name: string,
  scope: ServerKeyScope = ServerKeyScopes.behindProxy,
  allowProxyCommands = false,
): Promise<CreatedServerKey> {
  const result = await instance.apiWithCredentials(Endpoints.serverKeys, {
    method: "POST",
    json: { name, scope, allowProxyCommands },
  });
  const key = jsonField(result.json, "key");
  const id = jsonString(key, "id");
  const secret = jsonString(result.json, "secret");
  if (result.status !== HttpStatus.ok || !id || !secret) throw new InstanceError(`creating the server key ${name} failed`, result);
  return { id, name, secret };
}

export async function listServerKeys(instance: InstanceClient): Promise<ListedServerKey[]> {
  const result = await instance.apiWithCredentials(Endpoints.serverKeys);
  if (result.status !== HttpStatus.ok) throw new InstanceError("listing the server keys failed", result);
  return jsonArray(result.json, "keys").flatMap((entry) => {
    const id = jsonString(entry, "id");
    const name = jsonString(entry, "name");
    if (!id || !name) return [];
    return [{ id, name, connected: jsonField(entry, "connected") === true, allowProxyCommands: jsonField(entry, "allowProxyCommands") === true }];
  });
}

export async function setProxyCommands(instance: InstanceClient, id: string, allowProxyCommands: boolean): Promise<HttpResult> {
  return instance.apiWithCredentials(Endpoints.serverKey(id), { method: "PATCH", json: { allowProxyCommands } });
}

export async function revokeServerKey(instance: InstanceClient, id: string): Promise<HttpResult> {
  return instance.apiWithCredentials(Endpoints.serverKey(id), { method: "DELETE" });
}

export async function replaceServerKey(instance: InstanceClient, name: string, scope: ServerKeyScope = ServerKeyScopes.behindProxy): Promise<CreatedServerKey> {
  for (const existing of await listServerKeys(instance)) {
    if (existing.name !== name) continue;
    const revoked = await revokeServerKey(instance, existing.id);
    if (revoked.status !== HttpStatus.noContent) throw new InstanceError(`revoking the old server key ${name} failed`, revoked);
  }
  return createServerKey(instance, name, scope);
}

export const ProxiedServerStates = {
  noKey: "PROXIED_SERVER_STATE_NO_KEY",
  notConnected: "PROXIED_SERVER_STATE_NOT_CONNECTED",
  connected: "PROXIED_SERVER_STATE_CONNECTED",
} as const;
export type ProxiedServerState = (typeof ProxiedServerStates)[keyof typeof ProxiedServerStates];

export interface ListedProxiedServer {
  name: string;
  address: string;
  state: string;
  keyId: string | null;
}

export async function listProxiedServers(instance: InstanceClient): Promise<ListedProxiedServer[]> {
  const result = await instance.apiWithCredentials(Endpoints.serverKeys);
  if (result.status !== HttpStatus.ok) throw new InstanceError("listing the proxy servers failed", result);
  return jsonArray(result.json, "proxiedServers").flatMap((entry) => {
    const server = jsonField(entry, "server");
    const name = jsonString(server, "name");
    const state = jsonString(entry, "state");
    if (!name || !state) return [];
    return [{ name, address: jsonString(server, "address") ?? "", state, keyId: jsonString(entry, "keyId") }];
  });
}
