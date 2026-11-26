import type { YamlPatches } from "../config-files";

export const EMBEDDED_PORT = 8080;
export const BEHIND_PROXY_MODE = "behind-proxy";

export function embeddedConnectionPatches(presetAdminEmail: string | null, panelUrl: string): YamlPatches {
  return {
    mode: "embedded",
    "embedded.port": EMBEDDED_PORT,
    "embedded.bind-address": "0.0.0.0",
    "embedded.admin-email": presetAdminEmail ?? "",
    "embedded.public-url": presetAdminEmail ? panelUrl : "",
  };
}

export function remoteConnectionPatches(url: string, apiKey: string): YamlPatches {
  return { mode: "remote", "remote.url": url, "remote.api-key": apiKey };
}

export function memberConnectionPatches(url: string, serverKey: string): YamlPatches {
  return { mode: BEHIND_PROXY_MODE, "remote.url": url, "remote.api-key": serverKey };
}

export function modlConfigPatches(serverName: string, updateChecks: boolean): YamlPatches {
  return {
    "server.name": serverName,
    "realtime.enabled": true,
    "panel_login.allow_offline_mode": true,
    "update_checker.enabled": updateChecks,
    "ip-lookup.enabled": false,
  };
}

export function serverFeaturesPatches(serverName: string): YamlPatches {
  return { "server-name": serverName };
}

export const MEMBER_STAT_WIPE_COMMAND = "say modl-statwipe {player}";

export function memberServerFeaturesPatches(serverName: string): YamlPatches {
  return { ...serverFeaturesPatches(serverName), "stat-wipe-commands": [MEMBER_STAT_WIPE_COMMAND] };
}
