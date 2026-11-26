import { existsSync, mkdirSync } from "node:fs";
import { join } from "node:path";
import { artifactPath, ensureArtifacts } from "./artifacts";
import { botDaemonRunning, stopBotDaemon } from "./bots/daemon-client";
import type { ConsoleProblem } from "./console-policy";
import { compose, containerState, dockerAvailability, forceRemoveProject, harnessOneShotContainers, projectResources } from "./docker";
import { Environment, secondsSince, type StopReport } from "./environment";
import { lineOf } from "./lines";
import type { Logger } from "./log";
import { busyPorts } from "./ports";
import { activeReleaseDir, HARNESS_LANE, MARKS_FILE, RELEASE_MIRROR_DIR, RUNTIME_DIR, serverDataDir, topologyRuntimeDir } from "./paths";
import { requirePluginJar, requireServerJar } from "./product";
import { activateBaseRelease, prepareReleaseFixtures } from "./release-fixtures";
import { settings } from "./settings";
import {
  clearState,
  entryGamePort,
  isMinecraftKind,
  readState,
  saveState,
  TOPOLOGY_NAMES,
  type EnvironmentState,
  type ServerState,
  type TopologyName,
  type UpOptions,
} from "./state";
import { containerName, projectName } from "./topologies/compose";
import { publishedPorts, TOPOLOGIES } from "./topologies/index";
import { RELEASE_MIRROR_SERVICE, ReleaseMirror } from "./topologies/release-mirror";
import { loadPluginResources } from "./topologies/server-files";
import type { BootStep, PrepareContext, TopologyDefinition } from "./topologies/types";
import { errorMessage, randomSecret, removePath, sleep, writeText } from "./util";

async function portsNotHeldByThisEnvironment(definition: TopologyDefinition, options: UpOptions): Promise<number[]> {
  const ports = await Promise.all(
    definition.servers(options).map(async (spec) => {
      const running = (await containerState(containerName(definition.name, spec.name))).status === "running";
      return running ? [] : [spec.ports.game, spec.ports.rcon, spec.ports.http].filter((port): port is number => port !== undefined);
    }),
  );
  return ports.flat();
}

function buildState(
  definition: TopologyDefinition,
  options: UpOptions,
  pluginJar: string,
  serverJar: string | null,
  reuse: EnvironmentState | null,
): EnvironmentState {
  const topology = definition.name;
  const servers = Object.fromEntries(
    definition.servers(options).map((spec) => [
      spec.name,
      {
        name: spec.name,
        kind: spec.kind,
        role: spec.role,
        service: spec.service,
        container: containerName(topology, spec.name),
        dataDir: serverDataDir(topology, options.line, spec.name),
        dataMount: spec.dataMount ?? "host",
        gamePort: spec.ports.game ?? null,
        rconPort: spec.ports.rcon ?? null,
        httpPort: spec.ports.http ?? null,
        hasConsole: isMinecraftKind(spec.kind),
      } satisfies ServerState,
    ]),
  );
  return {
    topology,
    project: projectName(topology),
    composeFile: join(topologyRuntimeDir(topology, options.line), "compose.yml"),
    options,
    createdAt: reuse?.createdAt ?? new Date().toISOString(),
    bootedAt: null,
    entryServer: definition.roles.entry,
    coreServer: definition.roles.core,
    dataServer: definition.roles.data,
    rconPassword: reuse?.rconPassword ?? randomSecret(12),
    forwardingSecret: reuse?.forwardingSecret ?? randomSecret(24),
    pluginJar,
    serverJar,
    servers,
    instance: {
      baseUrl: `http://127.0.0.1:${definition.panelPort}`,
      noticeServer: definition.roles.notices,
      ownerEmail: options.presetAdminEmail ?? settings().ownerEmail,
      presetAdmin: options.presetAdminEmail !== null,
      cookies: reuse?.instance.cookies ?? {},
      sessionKey: reuse?.instance.sessionKey ?? null,
      apiKey: reuse?.instance.apiKey ?? null,
      lastLoginCodeAt: reuse?.instance.lastLoginCodeAt ?? null,
    },
    memberDetection: reuse?.memberDetection ?? [],
    connectPending: true,
  };
}

async function bootSteps(definition: TopologyDefinition, environment: Environment, options: UpOptions): Promise<BootStep[]> {
  const booted = await definition.boot(environment.bootContext());
  return options.deferSetup ? booted : [...booted, ...(await environment.connectServers())];
}

interface ProductJars {
  plugin: string;
  server: string | null;
}

async function productJars(definition: TopologyDefinition, options: UpOptions, logger: Logger): Promise<ProductJars> {
  if (!options.updateFixture) {
    return { plugin: requirePluginJar(), server: definition.needsServerJar(options) ? requireServerJar() : null };
  }
  await prepareReleaseFixtures(logger);
  mkdirSync(RELEASE_MIRROR_DIR, { recursive: true });
  return activateBaseRelease(activeReleaseDir(options.topology, options.line));
}

export interface UpReport {
  topology: string;
  project: string;
  entry: string;
  panel: string;
  servers: Record<string, { container: string; gamePort: number | null; rconPort: number | null; httpPort: number | null }>;
  steps: BootStep[];
  totalSeconds: number;
  problems: ConsoleProblem[];
}

export async function up(options: UpOptions, logger: Logger): Promise<UpReport> {
  const definition = TOPOLOGIES[options.topology];
  const existing = readState();
  if (existing && existing.topology !== options.topology) {
    throw new Error(`the ${existing.topology} topology is up; run down first (one environment at a time)`);
  }
  const otherLine = existing !== null && existing.options.line !== options.line;
  if (existing && otherLine) logger.info(`stopping the ${existing.options.line} line to start the ${options.line} line; each line keeps its own data`);
  if (existing && (otherLine || options.fresh)) await down({ keepData: true }, logger);
  if (options.fresh) {
    await forceRemoveProject(projectName(options.topology), { keepVolumes: false });
    removePath(topologyRuntimeDir(options.topology, options.line));
  }
  const reuse = options.fresh || otherLine ? null : existing;
  const docker = await dockerAvailability();
  if (!docker.ok) throw new Error(`Docker is not reachable: ${docker.error}`);
  const busy = await busyPorts(await portsNotHeldByThisEnvironment(definition, options));
  if (busy.length > 0) throw new Error(`host ports already in use: ${busy.join(", ")} (run down, or free them)`);
  await ensureArtifacts(definition.artifacts(options), logger);
  await lineOf(options).paper.prepare(logger);
  const { plugin: pluginJar, server: serverJar } = await productJars(definition, options, logger);
  const state = buildState(definition, options, pluginJar, serverJar, reuse);
  const context: PrepareContext = { state, resources: await loadPluginResources(pluginJar), artifactPath, logger };
  await definition.prepare(context);
  writeText(state.composeFile, definition.compose(context));
  saveState(state);
  const environment = new Environment(state, logger);
  const started = Date.now();
  logger.info(`starting ${definition.name}: ${Object.keys(state.servers).join(", ")}`);
  if (options.updateFixture) {
    await new ReleaseMirror(state.project).publishFiles("base");
    await environment.composeUp([RELEASE_MIRROR_SERVICE]);
  }
  const steps = await bootSteps(definition, environment, options).catch((error: unknown) => {
    throw new Error(`${definition.name} did not come up: ${errorMessage(error)}\nInspect with "logs <server>" or "errors"; tear down with "down".`);
  });
  state.bootedAt = new Date().toISOString();
  environment.save();
  return {
    topology: state.topology,
    project: state.project,
    entry: `127.0.0.1:${entryGamePort(state)}`,
    panel: state.instance.baseUrl,
    servers: Object.fromEntries(
      Object.values(state.servers).map((server) => [
        server.name,
        { container: server.container, gamePort: server.gamePort, rconPort: server.rconPort, httpPort: server.httpPort },
      ]),
    ),
    steps,
    totalSeconds: secondsSince(started),
    problems: await environment.problems({}, definition.expectedBootProblems),
  };
}

export interface Leftovers {
  containers: string[];
  networks: string[];
  volumes: string[];
  botDaemon: boolean;
  state: boolean;
  busyPorts: number[];
}

function topologiesInScope(state: EnvironmentState | null): readonly TopologyName[] {
  if (HARNESS_LANE === null) return TOPOLOGY_NAMES;
  return state ? [state.topology] : [];
}

export async function leftovers(ports: readonly number[] = [], scope: readonly TopologyName[] = topologiesInScope(readState())): Promise<Leftovers> {
  const resources = await Promise.all(scope.map(async (topology) => projectResources(projectName(topology))));
  const oneShots = await harnessOneShotContainers();
  return {
    containers: [...resources.flatMap((entry) => entry.containers), ...oneShots],
    networks: resources.flatMap((entry) => entry.networks),
    volumes: resources.flatMap((entry) => entry.volumes),
    botDaemon: await botDaemonRunning(),
    state: readState() !== null,
    busyPorts: await busyPorts(ports),
  };
}

export function isClean(found: Leftovers, options: { volumesKept: boolean }): boolean {
  const volumes = options.volumesKept ? 0 : found.volumes.length;
  return found.containers.length + found.networks.length + volumes + found.busyPorts.length === 0 && !found.botDaemon && !found.state;
}

export interface DownReport {
  topology: string | null;
  botDaemon: { wasRunning: boolean; stopped: boolean };
  stopped: StopReport[];
  archivedLogs: string | null;
  dataKept: boolean;
  leftovers: Leftovers;
  clean: boolean;
}

export async function down(options: { keepData: boolean }, logger: Logger): Promise<DownReport> {
  const state = readState();
  const botDaemon = await stopBotDaemon();
  let stopped: StopReport[] = [];
  let archivedLogs: string | null = null;
  if (state) {
    const environment = new Environment(state, logger);
    stopped = await environment.stopAll();
    archivedLogs = await environment.archiveLogs();
    if (existsSync(state.composeFile)) {
      const volumes = options.keepData ? [] : ["--volumes"];
      await compose(state.project, state.composeFile, ["down", ...volumes, "--remove-orphans", "--timeout", "30"], {
        allowFailure: true,
        timeoutMs: 180_000,
      });
    }
  }
  const scope = topologiesInScope(state);
  for (const topology of scope) await forceRemoveProject(projectName(topology), { keepVolumes: options.keepData });
  clearState();
  removePath(MARKS_FILE);
  if (!options.keepData) removePath(RUNTIME_DIR);
  const ports = state ? publishedPorts(TOPOLOGIES[state.topology], state.options) : [];
  let found = await leftovers(ports, scope);
  for (let attempt = 0; attempt < 10 && found.busyPorts.length > 0; attempt += 1) {
    await sleep(1000);
    found = await leftovers(ports, scope);
  }
  return {
    topology: state?.topology ?? null,
    botDaemon,
    stopped,
    archivedLogs,
    dataKept: options.keepData,
    leftovers: found,
    clean: isClean(found, { volumesKept: options.keepData }),
  };
}

export interface StatusReport {
  up: boolean;
  topology?: string;
  entry?: string;
  panel?: string;
  claimed?: boolean | null;
  connectPending?: boolean;
  bootedAt?: string | null;
  servers?: Record<string, { container: string; status: string; exitCode: number | null; oomKilled: boolean; ports: Record<string, number | null> }>;
  leftovers?: Leftovers;
}

export async function status(logger: Logger): Promise<StatusReport> {
  const state = readState();
  if (!state) return { up: false, leftovers: await leftovers() };
  const environment = new Environment(state, logger);
  const servers = Object.fromEntries(
    await Promise.all(
      Object.values(state.servers).map(async (server) => {
        const container = await containerState(server.container);
        return [
          server.name,
          {
            container: server.container,
            status: container.status,
            exitCode: container.exitCode,
            oomKilled: container.oomKilled,
            ports: { game: server.gamePort, rcon: server.rconPort, http: server.httpPort },
          },
        ] as const;
      }),
    ),
  );
  const claimed = await environment.instance.isClaimed().catch(() => null);
  return {
    up: true,
    topology: state.topology,
    entry: `127.0.0.1:${entryGamePort(state)}`,
    panel: state.instance.baseUrl,
    claimed,
    connectPending: state.connectPending,
    bootedAt: state.bootedAt,
    servers,
  };
}
