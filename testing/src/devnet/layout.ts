import { join, resolve } from "node:path";
import type { BotDaemonHome } from "../lib/bots/daemon-client";
import { HARNESS_ROOT, SOURCE_DIR } from "../lib/paths";
import type { MemoryProfile } from "../lib/settings";
import type { MinecraftKind, ServerRole, ServerState } from "../lib/state";

const HOME_OVERRIDE = process.env["MODL_DEVNET_HOME"];
const PROJECT_OVERRIDE = process.env["MODL_DEVNET_PROJECT"];

export const DEV_DIR = HOME_OVERRIDE === undefined || HOME_OVERRIDE === "" ? join(HARNESS_ROOT, ".dev") : resolve(HOME_OVERRIDE);
export const DEV_STATE_FILE = join(DEV_DIR, "state.json");
export const DEV_COMPOSE_FILE = join(DEV_DIR, "compose.yml");
export const DEV_IDENTITY_DIR = join(DEV_DIR, "identity");
export const DEV_SERVERS_DIR = join(DEV_DIR, "servers");
export const DEV_PROJECT = PROJECT_OVERRIDE === undefined || PROJECT_OVERRIDE === "" ? "modl-devnet" : PROJECT_OVERRIDE;
export const DEV_LABEL = "gg.modl.devnet";

export const DEV_BOT_DAEMON: BotDaemonHome = {
  directory: DEV_DIR,
  file: join(DEV_DIR, "botd.json"),
  log: join(DEV_DIR, "botd.log"),
  spawnLock: join(DEV_DIR, "botd.lock"),
  script: join(SOURCE_DIR, "devnet", "botd.ts"),
};

export const PREFERRED_PORTS = { join: 25565, botGate: 25566, panel: 18200, logs: 18201 } as const;

export const RESERVED_PORTS: ReadonlySet<number> = new Set([
  3000, 8080, 18080, 18123, 18124, 18125, 25700, 25701, 25702, 25750, 25751, 25752, 25753,
  ...Object.values(PREFERRED_PORTS),
]);

export const MINECRAFT_PORT = 25565;
export const LOGS_CONTAINER_PORT = 8080;

export const DEV_SERVER_NAMES = ["proxy", "botgate", "latest", "legacy"] as const;
export type DevServerName = (typeof DEV_SERVER_NAMES)[number];

export function isDevServerName(value: string): value is DevServerName {
  return (DEV_SERVER_NAMES as readonly string[]).includes(value);
}

export const BACKEND_NAMES = ["latest", "legacy"] as const satisfies readonly DevServerName[];
export type BackendName = (typeof BACKEND_NAMES)[number];

export function isBackendName(value: string): value is BackendName {
  return (BACKEND_NAMES as readonly string[]).includes(value);
}

export const PANEL_LOG_SERVICE = "panel-log";
export const LOG_VIEWER_SERVICE = "logs";

export interface DevServer {
  name: DevServerName;
  kind: Exclude<MinecraftKind, "bungeecord">;
  role: Extract<ServerRole, "proxy" | "member">;
  memory: MemoryProfile;
}

export const DEV_SERVERS: Record<DevServerName, DevServer> = {
  proxy: { name: "proxy", kind: "velocity", role: "proxy", memory: { limit: "3328m", heapMb: 2048 } },
  botgate: { name: "botgate", kind: "velocity", role: "proxy", memory: { limit: "704m", heapMb: 352 } },
  latest: { name: "latest", kind: "paper", role: "member", memory: { limit: "1664m", heapMb: 1088 } },
  legacy: { name: "legacy", kind: "paper", role: "member", memory: { limit: "1024m", heapMb: 512 } },
};

export function devContainerName(service: string): string {
  return `${DEV_PROJECT}-${service}`;
}

export function devServerDir(name: DevServerName): string {
  return join(DEV_SERVERS_DIR, name);
}

export function devServerState(name: DevServerName): ServerState {
  const server = DEV_SERVERS[name];
  return {
    name,
    kind: server.kind,
    role: server.role,
    service: name,
    container: devContainerName(name),
    dataDir: devServerDir(name),
    dataMount: "host",
    gamePort: null,
    rconPort: null,
    httpPort: null,
    hasConsole: true,
  };
}
