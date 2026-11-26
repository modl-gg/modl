import { existsSync } from "node:fs";
import { z } from "zod";
import { STATE_FILE } from "./paths";
import { readJson, removePath, writeJson } from "./util";

export const TOPOLOGY_NAMES = ["standalone", "network", "network-bungee", "remote"] as const;
export type TopologyName = (typeof TOPOLOGY_NAMES)[number];

export function isTopologyName(value: string): value is TopologyName {
  return (TOPOLOGY_NAMES as readonly string[]).includes(value);
}

export const LINE_NAMES = ["latest", "1.21", "legacy", "folia"] as const;
export type LineName = (typeof LINE_NAMES)[number];

export function isLineName(value: string): value is LineName {
  return (LINE_NAMES as readonly string[]).includes(value);
}

export const MINECRAFT_KINDS = ["paper", "velocity", "bungeecord"] as const;
export type MinecraftKind = (typeof MINECRAFT_KINDS)[number];
const SERVER_KINDS = [...MINECRAFT_KINDS, "backend"] as const;
export type ServerKind = (typeof SERVER_KINDS)[number];

export function isMinecraftKind(kind: ServerKind): kind is MinecraftKind {
  return (MINECRAFT_KINDS as readonly string[]).includes(kind);
}

const SERVER_ROLES = ["standalone", "proxy", "member", "remote-paper", "backend"] as const;
export type ServerRole = (typeof SERVER_ROLES)[number];

const DATA_MOUNTS = ["host", "docker-volume"] as const;
export type DataMount = (typeof DATA_MOUNTS)[number];

export interface ServerState {
  name: string;
  kind: ServerKind;
  role: ServerRole;
  service: string;
  container: string;
  dataDir: string;
  dataMount: DataMount;
  gamePort: number | null;
  rconPort: number | null;
  httpPort: number | null;
  hasConsole: boolean;
}

export const REMOTE_BACKEND_KINDS = ["jar", "compose"] as const;
export type RemoteBackendKind = (typeof REMOTE_BACKEND_KINDS)[number];

export function isRemoteBackendKind(value: string): value is RemoteBackendKind {
  return (REMOTE_BACKEND_KINDS as readonly string[]).includes(value);
}

export interface UpOptions {
  topology: TopologyName;
  fresh: boolean;
  presetAdminEmail: string | null;
  remoteBackend: RemoteBackendKind;
  proxyPacketEvents: boolean;
  line: LineName;
  java: number | null;
  deferSetup: boolean;
  updateFixture: boolean;
  realClient: boolean;
}

export interface InstanceState {
  baseUrl: string;
  noticeServer: string;
  ownerEmail: string;
  presetAdmin: boolean;
  cookies: Record<string, string>;
  sessionKey: string | null;
  apiKey: string | null;
  lastLoginCodeAt: number | null;
}

export interface MemberDetection {
  server: string;
  firstStart: boolean;
  writtenMode: string | null;
  autoDetected: boolean;
}

export interface EnvironmentState {
  topology: TopologyName;
  project: string;
  composeFile: string;
  options: UpOptions;
  createdAt: string;
  bootedAt: string | null;
  entryServer: string;
  coreServer: string;
  dataServer: string;
  rconPassword: string;
  forwardingSecret: string;
  pluginJar: string;
  serverJar: string | null;
  servers: Record<string, ServerState>;
  instance: InstanceState;
  memberDetection: MemberDetection[];
  connectPending: boolean;
}

const serverStateSchema: z.ZodType<ServerState> = z.object({
  name: z.string(),
  kind: z.enum(SERVER_KINDS),
  role: z.enum(SERVER_ROLES),
  service: z.string(),
  container: z.string(),
  dataDir: z.string(),
  dataMount: z.enum(DATA_MOUNTS),
  gamePort: z.number().nullable(),
  rconPort: z.number().nullable(),
  httpPort: z.number().nullable(),
  hasConsole: z.boolean(),
});

const upOptionsSchema: z.ZodType<UpOptions> = z.object({
  topology: z.enum(TOPOLOGY_NAMES),
  fresh: z.boolean(),
  presetAdminEmail: z.string().nullable(),
  remoteBackend: z.enum(REMOTE_BACKEND_KINDS),
  proxyPacketEvents: z.boolean(),
  line: z.enum(LINE_NAMES),
  java: z.number().nullable(),
  deferSetup: z.boolean(),
  updateFixture: z.boolean().default(false),
  realClient: z.boolean().default(false),
});

const instanceStateSchema: z.ZodType<InstanceState> = z.object({
  baseUrl: z.string(),
  noticeServer: z.string(),
  ownerEmail: z.string(),
  presetAdmin: z.boolean(),
  cookies: z.record(z.string(), z.string()),
  sessionKey: z.string().nullable(),
  apiKey: z.string().nullable(),
  lastLoginCodeAt: z.number().nullable(),
});

const memberDetectionSchema: z.ZodType<MemberDetection> = z.object({
  server: z.string(),
  firstStart: z.boolean(),
  writtenMode: z.string().nullable(),
  autoDetected: z.boolean(),
});

const environmentStateSchema: z.ZodType<EnvironmentState> = z.object({
  topology: z.enum(TOPOLOGY_NAMES),
  project: z.string(),
  composeFile: z.string(),
  options: upOptionsSchema,
  createdAt: z.string(),
  bootedAt: z.string().nullable(),
  entryServer: z.string(),
  coreServer: z.string(),
  dataServer: z.string(),
  rconPassword: z.string(),
  forwardingSecret: z.string(),
  pluginJar: z.string(),
  serverJar: z.string().nullable(),
  servers: z.record(z.string(), serverStateSchema),
  instance: instanceStateSchema,
  memberDetection: z.array(memberDetectionSchema),
  connectPending: z.boolean().default(false),
});

export function readState(): EnvironmentState | null {
  return existsSync(STATE_FILE) ? readJson(STATE_FILE, environmentStateSchema.nullable(), null) : null;
}

export function requireState(): EnvironmentState {
  const state = readState();
  if (!state) throw new Error(`No test environment is up. Start one with: npm run up -- <${TOPOLOGY_NAMES.join("|")}>`);
  return state;
}

export function saveState(state: EnvironmentState): void {
  writeJson(STATE_FILE, state);
}

export function clearState(): void {
  removePath(STATE_FILE);
}

export function requireServer(state: EnvironmentState, name: string): ServerState {
  const server = state.servers[name];
  if (!server) {
    throw new Error(`Unknown server '${name}' in topology ${state.topology} (servers: ${Object.keys(state.servers).join(", ")})`);
  }
  return server;
}

export function entryGamePort(state: EnvironmentState): number {
  const port = requireServer(state, state.entryServer).gamePort;
  if (port === null) throw new Error(`entry server ${state.entryServer} publishes no game port`);
  return port;
}
