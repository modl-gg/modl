import { join, posix } from "node:path";
import { consoleFor, type ServerConsole } from "./console";
import { artifactPath, jarsUnder } from "./artifacts";
import { buildLeakIndex, type LeakIndex } from "./console-leaks";
import { scanProblems, type ConsoleProblem } from "./console-policy";
import { ConsolePatterns, DataDirectories, EMBEDDED_STARTUP_PROGRESS, PANEL_STALL_TIMEOUT_MS, readyPattern, SetupCodeFiles, stopCommand } from "./contract";
import { compose, containerLogs, containerState, killContainer, readContainerFile, startContainer, stopContainer } from "./docker";
import { InstanceClient } from "./instance";
import { lineOf } from "./lines";
import type { Logger } from "./log";
import { parseEntries, type ConsoleEntry } from "./log-entries";
import { ContainerLog, LOG_START, type LogMark } from "./logs";
import { REPORTS_DIR } from "./paths";
import { requireEmbeddedArchive } from "./product";
import { releaseFixture } from "./release-fixtures";
import { settings } from "./settings";
import { isMinecraftKind, requireServer, requireState, saveState, type EnvironmentState, type MinecraftKind, type ServerState } from "./state";
import { CONTAINER_DATA_DIR } from "./topologies/compose";
import { TOPOLOGIES } from "./topologies/index";
import { pluginLibraryDir } from "./topologies/server-files";
import type { BootContext, BootStep, ModlReadiness } from "./topologies/types";
import { errorMessage, readTextFile, sleep, writeText } from "./util";

export interface StopReport {
  server: string;
  wasRunning: boolean;
  graceful: boolean;
  exitCode: number | null;
}

export interface LifecycleReport {
  server: string;
  stop?: StopReport;
  readySeconds: number;
}

const WORLD_SETUP_COMMANDS = [
  "gamerule doDaylightCycle false",
  "gamerule advance_time false",
  "gamerule doWeatherCycle false",
  "gamerule advance_weather false",
  "time set noon",
  "weather clear",
];

const RESTART_CODE_TIMEOUT_MS = 30_000;
const PANEL_SPRING_START_MARGIN_MS = 60_000;
const CORE_START_IDLE_MS = PANEL_STALL_TIMEOUT_MS + PANEL_SPRING_START_MARGIN_MS;
const CORE_START_LIMIT_MS = 1_800_000;
const PANEL_POLL_MS = 1000;
const PANEL_HOST_LOG_TAIL = 20;

const STOP_PHASES: readonly (readonly ServerState["role"][])[] = [
  ["member", "remote-paper", "standalone"],
  ["proxy"],
  ["backend"],
];

function minecraftKind(server: ServerState): MinecraftKind {
  if (!isMinecraftKind(server.kind)) throw new Error(`${server.name} is a ${server.kind} container, not a Minecraft server`);
  return server.kind;
}

export function secondsSince(started: number): number {
  return Math.round((Date.now() - started) / 100) / 10;
}

export class Environment {
  private instanceClient: InstanceClient | null = null;
  private leaks: Promise<LeakIndex> | null = null;
  private connectFailure: Error | null = null;
  readonly state: EnvironmentState;
  private readonly logger: Logger;

  constructor(state: EnvironmentState, logger: Logger) {
    this.state = state;
    this.logger = logger;
  }

  static current(logger: Logger): Environment {
    return new Environment(requireState(), logger);
  }

  server(name: string): ServerState {
    return requireServer(this.state, name);
  }

  serverNames(): string[] {
    return Object.keys(this.state.servers);
  }

  consoleServerNames(): string[] {
    return this.serverNames().filter((name) => this.server(name).hasConsole);
  }

  console(name = this.state.coreServer): ServerConsole {
    return consoleFor(this.state, this.server(name));
  }

  log(name: string): ContainerLog {
    return new ContainerLog(name, this.server(name).container);
  }

  private noticeHostIsBackend(): boolean {
    return this.server(this.state.instance.noticeServer).role === "backend";
  }

  get instance(): InstanceClient {
    this.instanceClient ??= new InstanceClient(
      this.state.instance,
      this.log(this.state.instance.noticeServer),
      {
        readFile: async (path) => this.readServerFile(this.state.instance.noticeServer, path),
        setupCodeFile: () => (this.noticeHostIsBackend() ? SetupCodeFiles.standalone : SetupCodeFiles.plugin),
        dataDirectory: () => (this.noticeHostIsBackend() ? DataDirectories.standalone : DataDirectories.plugin),
        restartForNewSetupCode: async () => this.restartForNewSetupCode(),
      },
      (instance) => {
        this.state.instance = instance;
        this.save();
      },
      this.logger,
    );
    return this.instanceClient;
  }

  save(): void {
    saveState(this.state);
  }

  async connectServers(): Promise<BootStep[]> {
    if (!this.state.connectPending) return [];
    if (this.connectFailure) throw this.connectFailure;
    try {
      const steps = await TOPOLOGIES[this.state.topology].connect(this.bootContext());
      await this.prepareWorlds();
      this.state.connectPending = false;
      this.save();
      return steps;
    } catch (error) {
      this.connectFailure = new Error(`connecting the servers that wait for the API key failed: ${errorMessage(error)}`);
      throw this.connectFailure;
    }
  }

  async restartForNewSetupCode(): Promise<void> {
    const host = this.state.instance.noticeServer;
    const log = this.log(host);
    const since = await log.mark();
    await this.restart(host);
    await log.waitFor(ConsolePatterns.setupCode, { since, timeoutMs: RESTART_CODE_TIMEOUT_MS });
  }

  problemsExpectedUntilConnected(): readonly RegExp[] {
    return this.state.connectPending ? TOPOLOGIES[this.state.topology].expectedBootProblems : [];
  }

  async readServerFile(name: string, path: string): Promise<string | null> {
    const server = this.server(name);
    const containerPath = posix.resolve(CONTAINER_DATA_DIR, path);
    const dataRelative = posix.relative(CONTAINER_DATA_DIR, containerPath);
    const insideData = dataRelative !== "" && dataRelative !== ".." && !dataRelative.startsWith("../");
    const onHostMount = server.dataMount !== "docker-volume" && insideData;
    return onHostMount ? readTextFile(join(server.dataDir, ...dataRelative.split("/"))) : readContainerFile(server.container, containerPath);
  }

  async leakIndex(): Promise<LeakIndex> {
    this.leaks ??= buildLeakIndex(this.state.pluginJar, this.payloadArchive(), [...this.serverDistributionJars(), ...this.pluginRuntimeLibraries()]).catch(
      (error: unknown) => {
        this.leaks = null;
        throw error;
      },
    );
    return this.leaks;
  }

  private payloadArchive(): string {
    return this.state.options.updateFixture ? releaseFixture("base").embedded : requireEmbeddedArchive();
  }

  private serverDistributionJars(): string[] {
    const artifacts = TOPOLOGIES[this.state.topology].artifacts(this.state.options).map(artifactPath);
    return [...artifacts, ...lineOf(this.state.options).paper.patchedJars()];
  }

  private pluginRuntimeLibraries(): string[] {
    return this.consoleServerNames().flatMap((name) => jarsUnder(pluginLibraryDir(this.server(name).dataDir)));
  }

  async markAll(): Promise<Record<string, LogMark>> {
    const marks = await Promise.all(this.serverNames().map(async (name) => [name, await this.log(name).mark()] as const));
    return Object.fromEntries(marks);
  }

  async entries(name: string, since: LogMark = LOG_START): Promise<ConsoleEntry[]> {
    return parseEntries(await this.log(name).lines(since));
  }

  async problems(since: Record<string, LogMark> = {}, ignore: readonly RegExp[] = []): Promise<ConsoleProblem[]> {
    const perServer = await Promise.all(
      this.serverNames().map(async (name) => scanProblems(name, await this.entries(name, since[name] ?? LOG_START), ignore)),
    );
    return perServer.flat();
  }

  async composeUp(services: string[], options: { build?: boolean } = {}): Promise<void> {
    const args = ["up", "-d", "--force-recreate", "--remove-orphans", ...(options.build ? ["--build"] : []), ...services];
    await compose(this.state.project, this.state.composeFile, args, { timeoutMs: options.build ? 1_800_000 : 600_000 });
  }

  async waitForMinecraft(name: string, since: LogMark, readiness: ModlReadiness): Promise<number> {
    const started = Date.now();
    const server = this.server(name);
    const log = this.log(name);
    const ready = readyPattern(minecraftKind(server));
    const failOn = readiness === "none" ? undefined : ConsolePatterns.pluginFailed;
    await log.waitFor(ready, { since, timeoutMs: settings().bootTimeoutMs, requireRunning: true, failOn });
    if (readiness === "core") await this.waitForCore(name, since);
    if (readiness === "member") {
      await log.waitFor(ConsolePatterns.memberNetworkConnected, {
        since,
        timeoutMs: 120_000,
        requireRunning: true,
        failOn: ConsolePatterns.memberKeyRejected,
      });
    }
    if (server.rconPort !== null) await this.console(name).waitForRcon(30_000);
    return secondsSince(started);
  }

  private async waitForCore(name: string, since: LogMark): Promise<void> {
    await this.log(name).waitFor(ConsolePatterns.pluginBooted, {
      since,
      timeoutMs: CORE_START_LIMIT_MS,
      progress: { pattern: EMBEDDED_STARTUP_PROGRESS, idleMs: CORE_START_IDLE_MS },
      requireRunning: true,
      failOn: ConsolePatterns.pluginFailed,
    });
  }

  async waitForReload(name: string, since: LogMark): Promise<number> {
    const started = Date.now();
    await this.waitForCore(name, since);
    await this.waitForPanel();
    return secondsSince(started);
  }

  async waitForPanel(): Promise<number> {
    const started = Date.now();
    const deadline = started + settings().bootTimeoutMs;
    const host = requireServer(this.state, this.state.dataServer);
    while (Date.now() < deadline) {
      if (await this.instance.isHealthy()) return secondsSince(started);
      const state = await containerState(host.container);
      if (state.status === "exited" || state.status === "dead") {
        const tail = (await containerLogs(host.container)).trimEnd().split("\n").slice(-PANEL_HOST_LOG_TAIL).join("\n");
        throw new Error(`${host.name} exited (code ${String(state.exitCode)}${state.oomKilled ? ", OOM killed" : ""}) before the panel became healthy:\n${tail}`);
      }
      await sleep(PANEL_POLL_MS);
    }
    throw new Error(`the panel at ${this.instance.baseUrl} did not report healthy within ${String(settings().bootTimeoutMs)}ms`);
  }

  async waitReady(name: string, since: LogMark): Promise<number> {
    const started = Date.now();
    const server = this.server(name);
    switch (server.role) {
      case "standalone":
      case "proxy":
        await this.waitForMinecraft(name, since, "core");
        await this.waitForPanel();
        break;
      case "remote-paper":
        await this.waitForMinecraft(name, since, "core");
        break;
      case "member":
        await this.waitForMinecraft(name, since, "member");
        break;
      case "backend":
        await this.waitForPanel();
        break;
    }
    return secondsSince(started);
  }

  private async awaitStatus(server: ServerState, accept: (status: string) => boolean, timeoutMs: number): Promise<boolean> {
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
      if (accept((await containerState(server.container)).status)) return true;
      await sleep(500);
    }
    return false;
  }

  async stopGracefully(name: string): Promise<StopReport> {
    const server = this.server(name);
    const before = await containerState(server.container);
    if (before.status !== "running") return { server: name, wasRunning: false, graceful: true, exitCode: before.exitCode };
    if (server.hasConsole) {
      await this.console(name)
        .send(stopCommand(minecraftKind(server)))
        .catch((error: unknown) => this.logger.warn(`${name}: stop command failed: ${errorMessage(error)}`));
      if (await this.awaitStatus(server, (status) => status !== "running", 90_000)) {
        const after = await containerState(server.container);
        return { server: name, wasRunning: true, graceful: after.exitCode === 0, exitCode: after.exitCode };
      }
      this.logger.warn(`${name} did not stop within 90s after the stop command; sending SIGTERM`);
    }
    await stopContainer(server.container, 30);
    const after = await containerState(server.container);
    const graceful = !server.hasConsole && (after.exitCode === 0 || after.exitCode === 143);
    return { server: name, wasRunning: true, graceful, exitCode: after.exitCode };
  }

  async startExisting(name: string): Promise<void> {
    await startContainer(this.server(name).container);
  }

  async restart(name: string): Promise<LifecycleReport> {
    const stop = await this.stopGracefully(name);
    const since = await this.log(name).mark();
    await this.startExisting(name);
    return { server: name, stop, readySeconds: await this.waitReady(name, since) };
  }

  async kill(name: string): Promise<{ server: string; exitCode: number | null }> {
    const server = this.server(name);
    await killContainer(server.container);
    await this.awaitStatus(server, (status) => status !== "running", 30_000);
    return { server: name, exitCode: (await containerState(server.container)).exitCode };
  }

  async start(name: string): Promise<LifecycleReport> {
    const since = await this.log(name).mark();
    await this.startExisting(name);
    return { server: name, readySeconds: await this.waitReady(name, since) };
  }

  async stopAll(): Promise<StopReport[]> {
    const reports: StopReport[] = [];
    for (const phase of STOP_PHASES) {
      const names = this.serverNames().filter((name) => phase.includes(this.server(name).role));
      reports.push(...(await Promise.all(names.map(async (name) => this.stopGracefully(name)))));
    }
    return reports;
  }

  async archiveLogs(): Promise<string> {
    const directory = join(REPORTS_DIR, "logs", `${this.state.topology}-${new Date().toISOString().replace(/[:.]/g, "-")}`);
    for (const name of this.serverNames()) {
      writeText(join(directory, `${name}.log`), await containerLogs(this.server(name).container));
    }
    return directory;
  }

  async prepareWorlds(): Promise<void> {
    for (const name of this.serverNames().filter((server) => this.server(server).rconPort !== null)) {
      for (const command of WORLD_SETUP_COMMANDS) {
        await this.console(name)
          .rcon(command)
          .catch((error: unknown) => this.logger.debug(`${name}: ${command} failed: ${errorMessage(error)}`));
      }
    }
  }

  bootContext(): BootContext {
    return {
      state: this.state,
      logger: this.logger,
      instance: this.instance,
      console: (name) => this.console(name),
      log: (name) => this.log(name),
      mark: async (name) => this.log(name).mark(),
      composeUp: async (services, options) => this.composeUp(services, options),
      stopGracefully: async (name) => {
        await this.stopGracefully(name);
      },
      startExisting: async (name) => this.startExisting(name),
      waitForMinecraft: async (name, since, readiness) => this.waitForMinecraft(name, since, readiness),
      waitForPanel: async () => this.waitForPanel(),
      save: () => this.save(),
    };
  }
}
