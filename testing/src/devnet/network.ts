import { readdirSync } from "node:fs";
import { join } from "node:path";
import { ARTIFACTS, ensureArtifacts } from "../lib/artifacts";
import { BotDaemonClient } from "../lib/bots/daemon-client";
import { readYamlValue } from "../lib/config-files";
import { ServerConsole, type ConsoleResult } from "../lib/console";
import {
  ConsolePatterns,
  DataDirectories,
  EMBEDDED_STARTUP_PROGRESS,
  Endpoints,
  PANEL_STALL_TIMEOUT_MS,
  readyPattern,
  SETUP_CODE_FILE_CODE,
  SetupCodeFiles,
  SetupPhases,
  stopCommand,
} from "../lib/contract";
import { compose, containerState, dockerAvailability, runDocker, stopContainer } from "../lib/docker";
import { InstanceClient, InstanceError } from "../lib/instance";
import type { Logger } from "../lib/log";
import { ContainerLog, LOG_START, type LogMark } from "../lib/logs";
import { PAPER_BUILDS } from "../lib/paper";
import { nearestFreePort, portInUse } from "../lib/ports";
import { productVersion, requireEmbeddedArchive, requirePluginJar } from "../lib/product";
import { replaceServerKey, ServerKeyScopes } from "../lib/server-keys";
import type { InstanceState } from "../lib/state";
import { BEHIND_PROXY_MODE } from "../lib/topologies/modl-config";
import { connectionFile, loadPluginResources } from "../lib/topologies/server-files";
import { errorMessage, readTextFile, removePath, sha256OfFile, sleep } from "../lib/util";
import { writeDevCompose } from "./compose";
import { DEV_ARTIFACTS, installPlugins, panelUrlInside, panelUrlOnHost, seedServer, writeBotGateKey, writeMemberKey } from "./files";
import {
  BACKEND_NAMES,
  DEV_BOT_DAEMON,
  DEV_COMPOSE_FILE,
  DEV_DIR,
  DEV_PROJECT,
  DEV_SERVER_NAMES,
  DEV_SERVERS,
  devContainerName,
  devServerDir,
  devServerState,
  LOG_VIEWER_SERVICE,
  PANEL_LOG_SERVICE,
  PREFERRED_PORTS,
  RESERVED_PORTS,
  type BackendName,
  type DevServerName,
} from "./layout";
import { newDevState, readDevState, saveDevState, type DevPorts, type DevState } from "./state";

const CORE_START_LIMIT_MS = 1_800_000;
const CORE_START_IDLE_MS = PANEL_STALL_TIMEOUT_MS + 60_000;
const SERVER_START_TIMEOUT_MS = 600_000;
const MEMBER_CONNECT_TIMEOUT_MS = 120_000;
const RELOAD_CONNECT_TIMEOUT_MS = 60_000;
const PANEL_HEALTH_TIMEOUT_MS = 300_000;
const STOP_TIMEOUT_MS = 90_000;
const COMPOSE_TIMEOUT_MS = 600_000;
const BOT_GATE_KEY_NAME = "botgate";

const MODL_PLUGIN_JAR = /^modl-.+\.jar$/;

export type ContainerCondition = "running" | "stopped" | "missing";

export interface ServerOverview {
  name: DevServerName;
  container: ContainerCondition;
  platform: string;
  modl: string;
}

export interface SetupOverview {
  phase: string;
  code: string | null;
  setupUrl: string;
}

export interface DevOverview {
  joinAddress: string;
  botGateAddress: string;
  panelUrl: string;
  logsUrl: string;
  panelHealthy: boolean;
  setup: SetupOverview | null;
  servers: ServerOverview[];
  installed: string | null;
  distDiffers: boolean;
  notes: string[];
}

const PLATFORMS: Record<DevServerName, string> = {
  proxy: `Velocity ${ARTIFACTS.velocityLatest.version}, online mode, modl embedded (panel)`,
  botgate: `Velocity ${ARTIFACTS.velocityLatest.version}, offline mode, modl remote`,
  latest: `Paper ${ARTIFACTS.paperLatest.version}, Java ${String(ARTIFACTS.paperLatest.java)}, modl behind-proxy`,
  legacy: `Paper ${ARTIFACTS.paperLegacy.version}, Java ${String(ARTIFACTS.paperLegacy.java)}, modl behind-proxy`,
};

const SETUP_PHASE_LABELS: Record<string, string> = {
  [SetupPhases.unclaimed]: "unclaimed",
  [SetupPhases.ownerSignIn]: "owner sign-in pending",
  [SetupPhases.ready]: "ready",
};

interface StatusPattern {
  pattern: RegExp;
  label: string;
}

const CORE_STATUS: readonly StatusPattern[] = [
  { pattern: ConsolePatterns.pluginBooted, label: "running" },
  { pattern: ConsolePatterns.pluginFailed, label: "not running (see logs)" },
  { pattern: ConsolePatterns.remoteKeyRejected, label: "key rejected" },
  { pattern: ConsolePatterns.remoteUnavailable, label: "cannot reach the panel" },
];

const MEMBER_STATUS: readonly StatusPattern[] = [
  { pattern: ConsolePatterns.memberNetworkConnected, label: "connected to the modl network" },
  { pattern: /\[network\] This server has no server key yet/, label: "idle, waiting for a server key (run connect after the panel setup)" },
  { pattern: ConsolePatterns.memberKeyRejected, label: "server key rejected" },
  { pattern: ConsolePatterns.memberNetworkLost, label: "reconnecting to the modl network" },
  { pattern: ConsolePatterns.pluginFailed, label: "not running (see logs)" },
];

function latestStatus(lines: readonly string[], patterns: readonly StatusPattern[], fallback: string): string {
  for (const line of [...lines].reverse()) {
    const hit = patterns.find((entry) => entry.pattern.test(line));
    if (hit) return hit.label;
  }
  return fallback;
}

function installedPluginJar(): string {
  const pluginsDir = join(devServerDir("proxy"), "plugins");
  const jar = readdirSync(pluginsDir).find((file) => MODL_PLUGIN_JAR.test(file));
  if (!jar) throw new Error(`no modl plugin jar in ${pluginsDir}; run npm run dev -- rebuild`);
  return join(pluginsDir, jar);
}

export class DevNetwork {
  readonly state: DevState;
  private readonly logger: Logger;
  private instanceClient: InstanceClient | null = null;

  private constructor(state: DevState, logger: Logger) {
    this.state = state;
    this.logger = logger;
  }

  static open(logger: Logger): DevNetwork {
    return new DevNetwork(readDevState() ?? newDevState(), logger);
  }

  static existing(logger: Logger): DevNetwork {
    const state = readDevState();
    if (!state) throw new Error("The dev network has not been booted yet. Start it with: npm run dev -- up");
    return new DevNetwork(state, logger);
  }

  private save(): void {
    saveDevState(this.state);
  }

  console(name: DevServerName): ServerConsole {
    return new ServerConsole(devServerState(name), "");
  }

  log(name: DevServerName): ContainerLog {
    return new ContainerLog(name, devContainerName(name));
  }

  get instance(): InstanceClient {
    if (this.instanceClient) return this.instanceClient;
    const session = this.state.session;
    const instance: InstanceState = {
      baseUrl: `http://127.0.0.1:${String(this.state.ports.panel)}`,
      noticeServer: "proxy",
      ownerEmail: this.state.ownerEmail ?? "",
      presetAdmin: false,
      cookies: session.cookies,
      sessionKey: session.sessionKey,
      apiKey: null,
      lastLoginCodeAt: session.lastLoginCodeAt,
    };
    this.instanceClient = new InstanceClient(
      instance,
      this.log("proxy"),
      {
        readFile: async (path) => Promise.resolve(readTextFile(join(devServerDir("proxy"), path))),
        setupCodeFile: () => SetupCodeFiles.plugin,
        dataDirectory: () => DataDirectories.plugin,
        restartForNewSetupCode: async () => Promise.reject(new Error("the dev network leaves the panel setup to you; restart the proxy for a new setup code")),
      },
      (updated) => {
        this.state.session = { cookies: updated.cookies, sessionKey: updated.sessionKey, lastLoginCodeAt: updated.lastLoginCodeAt };
        this.save();
      },
      this.logger,
    );
    return this.instanceClient;
  }

  private async compose(args: string[]): Promise<void> {
    await compose(DEV_PROJECT, DEV_COMPOSE_FILE, args, { timeoutMs: COMPOSE_TIMEOUT_MS });
  }

  async condition(name: string): Promise<ContainerCondition> {
    const state = await containerState(devContainerName(name));
    if (!state.exists) return "missing";
    return state.status === "running" ? "running" : "stopped";
  }

  private async runningServers(): Promise<DevServerName[]> {
    const conditions = await Promise.all(DEV_SERVER_NAMES.map(async (name) => ({ name, condition: await this.condition(name) })));
    return conditions.filter((entry) => entry.condition === "running").map((entry) => entry.name);
  }

  private async choosePorts(): Promise<void> {
    const panelSeeded = this.state.seeded.includes("proxy");
    if (panelSeeded && (await portInUse(this.state.ports.panel))) {
      throw new Error(`port ${String(this.state.ports.panel)} (the panel of the dev network) is in use by something else; free it and run up again`);
    }
    const chosen = new Set<number>();
    const pick = async (key: keyof DevPorts): Promise<number> => {
      const reserved = new Set([...RESERVED_PORTS, ...chosen]);
      reserved.delete(PREFERRED_PORTS[key]);
      const port = await nearestFreePort(PREFERRED_PORTS[key], reserved);
      chosen.add(port);
      return port;
    };
    const panel = panelSeeded ? this.state.ports.panel : await pick("panel");
    chosen.add(panel);
    this.state.ports = { panel, join: await pick("join"), botGate: await pick("botGate"), logs: await pick("logs") };
    this.save();
  }

  private async prepare(): Promise<void> {
    await ensureArtifacts(DEV_ARTIFACTS, this.logger);
    await PAPER_BUILDS.paperLatest.prepare(this.logger);
    await PAPER_BUILDS.paperLegacy.prepare(this.logger);
    const unseeded = DEV_SERVER_NAMES.filter((name) => !this.state.seeded.includes(name));
    if (unseeded.length === 0 && this.state.installed !== null) return;
    const pluginJar = requirePluginJar();
    requireEmbeddedArchive();
    const resources = await loadPluginResources(pluginJar);
    for (const name of unseeded) {
      await seedServer(name, { resources, forwardingSecret: this.state.forwardingSecret, panelPort: this.state.ports.panel });
      this.state.seeded.push(name);
      this.save();
    }
    if (this.state.installed === null) await this.install(pluginJar);
  }

  private async install(pluginJar: string): Promise<void> {
    for (const name of DEV_SERVER_NAMES) await installPlugins(name, pluginJar);
    this.state.installed = {
      modlVersion: productVersion(),
      pluginSha256: await sha256OfFile(pluginJar),
      embeddedSha256: await sha256OfFile(requireEmbeddedArchive()),
      installedAt: new Date().toISOString(),
    };
    this.save();
  }

  private memberHasKey(name: BackendName): boolean {
    const file = connectionFile(devServerDir(name));
    const key = readYamlValue(file, "remote.api-key");
    return readYamlValue(file, "mode") === BEHIND_PROXY_MODE
      && readYamlValue(file, "remote.url") === panelUrlInside(this.state.ports.panel)
      && typeof key === "string" && key !== "";
  }

  private botGateHasKey(): boolean {
    const file = connectionFile(devServerDir("botgate"));
    const key = readYamlValue(file, "remote.api-key");
    return readYamlValue(file, "mode") === "remote" && typeof key === "string" && key !== "";
  }

  private needsKeys(): boolean {
    return BACKEND_NAMES.some((name) => !this.memberHasKey(name)) || !this.botGateHasKey();
  }

  private async marks(names: readonly DevServerName[]): Promise<Map<DevServerName, LogMark>> {
    const entries = await Promise.all(names.map(async (name) => [name, await this.log(name).mark()] as const));
    return new Map(entries);
  }

  private async awaitPanel(): Promise<void> {
    const deadline = Date.now() + PANEL_HEALTH_TIMEOUT_MS;
    while (Date.now() < deadline) {
      if (await this.instance.isHealthy()) return;
      if ((await this.condition("proxy")) !== "running") throw new Error("the proxy stopped before the panel became healthy; see npm run dev -- logs proxy");
      await sleep(1000);
    }
    throw new Error(`the panel did not become healthy within ${String(PANEL_HEALTH_TIMEOUT_MS / 1000)}s`);
  }

  private async awaitReady(name: DevServerName, since: LogMark): Promise<void> {
    const log = this.log(name);
    const server = DEV_SERVERS[name];
    await log.waitFor(readyPattern(server.kind), { since, timeoutMs: SERVER_START_TIMEOUT_MS, requireRunning: true });
    switch (name) {
      case "proxy":
        await log.waitFor(ConsolePatterns.pluginBooted, {
          since,
          timeoutMs: CORE_START_LIMIT_MS,
          progress: { pattern: EMBEDDED_STARTUP_PROGRESS, idleMs: CORE_START_IDLE_MS },
          requireRunning: true,
          failOn: ConsolePatterns.pluginFailed,
        });
        await this.awaitPanel();
        return;
      case "botgate":
        await log.waitFor(ConsolePatterns.pluginBooted, { since, timeoutMs: SERVER_START_TIMEOUT_MS, requireRunning: true });
        return;
      case "latest":
      case "legacy":
        if (!this.memberHasKey(name)) return;
        await log.waitFor(ConsolePatterns.memberNetworkConnected, {
          since,
          timeoutMs: MEMBER_CONNECT_TIMEOUT_MS,
          requireRunning: true,
          failOn: ConsolePatterns.memberKeyRejected,
        });
        return;
    }
  }

  private startableServers(): DevServerName[] {
    return DEV_SERVER_NAMES.filter((name) => name !== "botgate" || this.botGateHasKey());
  }

  private async start(names: readonly DevServerName[]): Promise<void> {
    if (names.length === 0) return;
    const since = await this.marks(names);
    await this.compose(["up", "-d", ...names]);
    await Promise.all(names.map(async (name) => this.awaitReady(name, since.get(name) ?? LOG_START)));
  }

  private async startInOrder(names: readonly DevServerName[]): Promise<void> {
    if (names.includes("proxy")) await this.start(["proxy"]);
    await this.start(names.filter((name) => name !== "proxy"));
  }

  private async startSidecars(): Promise<void> {
    await this.compose(["up", "-d", PANEL_LOG_SERVICE, LOG_VIEWER_SERVICE]);
  }

  async up(): Promise<DevOverview> {
    const docker = await dockerAvailability();
    if (!docker.ok) throw new Error(`Docker is not available: ${docker.error}`);
    const running = await this.runningServers();
    if (running.length === 0) await this.choosePorts();
    await this.prepare();
    writeDevCompose(this.state.ports);
    this.save();
    await this.compose(["up", "-d", "--remove-orphans", PANEL_LOG_SERVICE, LOG_VIEWER_SERVICE]);
    await this.startInOrder(this.startableServers().filter((name) => !running.includes(name)));
    await this.connectIfSignedIn();
    return this.overview();
  }

  private async connectIfSignedIn(): Promise<void> {
    if (!this.needsKeys() || !this.hasSession()) return;
    try {
      const { phase } = await this.instance.setupStatus();
      if (phase !== SetupPhases.ready || (await this.instance.profile()) === null) return;
      await this.connect({});
    } catch (error) {
      this.logger.warn(`connecting the servers with the saved owner session failed: ${errorMessage(error)}`);
    }
  }

  private hasSession(): boolean {
    return this.state.session.sessionKey !== null;
  }

  async stopServer(name: DevServerName): Promise<void> {
    if ((await this.condition(name)) !== "running") return;
    await this.console(name)
      .send(stopCommand(DEV_SERVERS[name].kind))
      .catch((error: unknown) => this.logger.warn(`${name}: the stop command failed: ${errorMessage(error)}`));
    const deadline = Date.now() + STOP_TIMEOUT_MS;
    while (Date.now() < deadline) {
      if ((await this.condition(name)) !== "running") return;
      await sleep(500);
    }
    this.logger.warn(`${name} did not stop within ${String(STOP_TIMEOUT_MS / 1000)}s after the stop command; stopping the container`);
    await stopContainer(devContainerName(name), 30);
  }

  private async stopAll(): Promise<void> {
    await Promise.all((["botgate", ...BACKEND_NAMES] as const).map(async (name) => this.stopServer(name)));
    await this.stopServer("proxy");
  }

  async down(): Promise<void> {
    await new BotDaemonClient(DEV_BOT_DAEMON).stop();
    await this.stopAll();
    await this.compose(["down", "--remove-orphans"]);
  }

  async restart(name: DevServerName | null): Promise<DevOverview> {
    if (name === null) {
      await this.stopAll();
      await this.startInOrder(this.startableServers());
      await this.startSidecars();
      return this.overview();
    }
    if ((await this.condition(name)) === "missing") throw new Error(`${name} has no container yet; run npm run dev -- up`);
    await this.stopServer(name);
    await this.start([name]);
    return this.overview();
  }

  async rebuild(): Promise<DevOverview> {
    const pluginJar = requirePluginJar();
    requireEmbeddedArchive();
    const running = await this.runningServers();
    await this.stopAll();
    await ensureArtifacts(DEV_ARTIFACTS, this.logger);
    await this.install(pluginJar);
    if (running.length === 0) return this.overview();
    writeDevCompose(this.state.ports);
    await this.startInOrder(this.startableServers());
    await this.startSidecars();
    return this.overview();
  }

  async reset(): Promise<void> {
    if (readDevState() !== null || (await this.runningServers()).length > 0) await this.down().catch((error: unknown) => {
      this.logger.warn(`stopping the dev network before the reset failed: ${errorMessage(error)}`);
    });
    await runDocker(["network", "rm", DEV_PROJECT], { allowFailure: true });
    removePath(DEV_DIR);
  }

  async command(name: DevServerName, command: string): Promise<ConsoleResult> {
    if ((await this.condition(name)) !== "running") throw new Error(`${name} is not running`);
    return this.console(name).run(command);
  }

  async connect(options: { email?: string | undefined; code?: string | undefined }): Promise<string[]> {
    if (options.email !== undefined && options.email !== this.state.ownerEmail) {
      this.state.ownerEmail = options.email;
      this.state.session = { cookies: {}, sessionKey: null, lastLoginCodeAt: this.state.session.lastLoginCodeAt };
      this.instanceClient = null;
      this.save();
    }
    const { phase } = await this.instance.setupStatus();
    if (phase !== SetupPhases.ready) {
      throw new Error(`finish the panel setup first (setup: ${SETUP_PHASE_LABELS[phase] ?? phase}), then run npm run dev -- connect --email <owner email>`);
    }
    await this.ownerSession(options.code);
    const resources = await loadPluginResources(installedPluginJar());
    const done: string[] = [];
    for (const name of BACKEND_NAMES) {
      if (this.memberHasKey(name)) continue;
      const key = await replaceServerKey(this.instance, name, ServerKeyScopes.behindProxy);
      writeMemberKey(name, this.state.ports.panel, key.secret, resources);
      await this.reloadMember(name);
      done.push(`${name}: server key "${name}" (behind a proxy) created and connected`);
    }
    if (!this.botGateHasKey()) {
      const key = await replaceServerKey(this.instance, BOT_GATE_KEY_NAME, ServerKeyScopes.full);
      writeBotGateKey(this.state.ports.panel, key.secret, resources);
      writeDevCompose(this.state.ports);
      await this.start(["botgate"]);
      done.push(`botgate: server key "${BOT_GATE_KEY_NAME}" (proxy) created and started`);
    }
    return done;
  }

  private async ownerSession(code: string | undefined): Promise<void> {
    if ((await this.instance.profile()) !== null) return;
    const email = this.state.ownerEmail;
    if (email === null || email === "") throw new Error("connect needs the owner email once: npm run dev -- connect --email <owner email>");
    if (code === undefined) {
      try {
        await this.instance.ensureOwnerSession();
      } catch (error) {
        throw new Error(`${errorMessage(error)}. When the panel sends email (SMTP set), run npm run dev -- connect --code <the code from the email>`, { cause: error });
      }
      return;
    }
    const verified = await this.instance.api(Endpoints.verifyEmailCode, { method: "POST", json: { email, code }, auth: "none" });
    if (verified.status !== 200 || (await this.instance.profile()) === null) throw new InstanceError("signing in with the emailed code failed", verified);
  }

  private async reloadMember(name: BackendName): Promise<void> {
    const since = await this.log(name).mark();
    await this.console(name).send("modlserver reload");
    try {
      await this.log(name).waitFor(ConsolePatterns.memberNetworkConnected, { since, timeoutMs: RELOAD_CONNECT_TIMEOUT_MS, requireRunning: true });
    } catch (error) {
      this.logger.info(`${name} did not connect after /modlserver reload (${errorMessage(error)}); restarting it`);
      await this.stopServer(name);
      await this.start([name]);
    }
  }

  private setupCodeFromFile(): string | null {
    const text = readTextFile(join(devServerDir("proxy"), SetupCodeFiles.plugin));
    return text === null ? null : (SETUP_CODE_FILE_CODE.exec(text)?.[1] ?? null);
  }

  private async setupOverview(healthy: boolean): Promise<SetupOverview | null> {
    if (!healthy) return null;
    const { phase } = await this.instance.setupStatus();
    const code = phase === SetupPhases.ready ? null : (this.setupCodeFromFile() ?? (await this.instance.latestSetupCode())?.code ?? null);
    return { phase: SETUP_PHASE_LABELS[phase] ?? phase, code, setupUrl: `${panelUrlOnHost(this.state.ports.panel)}/setup` };
  }

  private async serverOverview(name: DevServerName): Promise<ServerOverview> {
    const container = await this.condition(name);
    const lines = container === "missing" ? [] : await this.log(name).text();
    const modl = this.modlStatus(name, container, lines);
    return { name, container, platform: PLATFORMS[name], modl };
  }

  private modlStatus(name: DevServerName, container: ContainerCondition, lines: readonly string[]): string {
    if (name === "botgate" && !this.botGateHasKey()) return "not started: needs a server key (run connect after the panel setup)";
    if (container !== "running") return "-";
    return DEV_SERVERS[name].role === "proxy" ? latestStatus(lines, CORE_STATUS, "starting") : latestStatus(lines, MEMBER_STATUS, "starting");
  }

  async overview(): Promise<DevOverview> {
    const healthy = (await this.condition("proxy")) === "running" && (await this.instance.isHealthy());
    const setup = await this.setupOverview(healthy).catch(() => null);
    const servers = await Promise.all(DEV_SERVER_NAMES.map(async (name) => this.serverOverview(name)));
    const installed = this.state.installed;
    const distDiffers = installed !== null && (await this.distPluginSha256()) !== installed.pluginSha256;
    const notes: string[] = [];
    if (setup !== null && setup.phase !== SETUP_PHASE_LABELS[SetupPhases.ready]) notes.push("After the panel setup, run npm run dev -- connect --email <owner email> to give the servers their keys.");
    if (setup?.phase === SETUP_PHASE_LABELS[SetupPhases.ready] && this.needsKeys()) notes.push("Some servers have no server key yet: npm run dev -- connect --email <owner email>.");
    if (distDiffers) notes.push("selfhost/dist has a different build than the one installed: npm run dev -- rebuild.");
    return {
      joinAddress: `localhost:${String(this.state.ports.join)}`,
      botGateAddress: `localhost:${String(this.state.ports.botGate)}`,
      panelUrl: panelUrlOnHost(this.state.ports.panel),
      logsUrl: `http://localhost:${String(this.state.ports.logs)}`,
      panelHealthy: healthy,
      setup,
      servers,
      installed: installed === null ? null : `modl ${installed.modlVersion}, installed ${installed.installedAt}`,
      distDiffers,
      notes,
    };
  }

  private async distPluginSha256(): Promise<string | null> {
    try {
      return await sha256OfFile(requirePluginJar());
    } catch {
      return null;
    }
  }
}
