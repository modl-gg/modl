import { spawn } from "node:child_process";
import { closeSync, mkdirSync, openSync, rmSync, statSync, utimesSync } from "node:fs";
import { join } from "node:path";
import { requestSignal } from "../cancellation";
import { BOT_DAEMON_FILE, BOT_DAEMON_LOG, BOT_DAEMON_SPAWN_LOCK, HARNESS_ROOT, RUNTIME_DIR, SOURCE_DIR } from "../paths";
import { z } from "zod";
import { errnoCode, readJson, sleep } from "../util";

export const BOT_DAEMON_TOKEN_HEADER = "x-modl-botd-token";

const SPAWN_LOCK_STALE_MS = 30_000;

export interface BotDaemonHome {
  directory: string;
  file: string;
  log: string;
  spawnLock: string;
  script: string;
}

export const HARNESS_BOT_DAEMON: BotDaemonHome = {
  directory: RUNTIME_DIR,
  file: BOT_DAEMON_FILE,
  log: BOT_DAEMON_LOG,
  spawnLock: BOT_DAEMON_SPAWN_LOCK,
  script: join(SOURCE_DIR, "botd.ts"),
};

export interface DaemonInfo {
  pid: number;
  port: number;
  token: string;
  startedAt: string;
}

const daemonReplySchema = z.object({
  ok: z.boolean(),
  result: z.unknown().optional(),
  error: z.string().optional(),
});

const daemonHealthSchema = z.object({ pid: z.unknown() });

const recordedDaemonSchema = z.object({
  pid: z.number(),
  port: z.number(),
  token: z.string(),
  startedAt: z.string().optional(),
});

async function call(daemon: DaemonInfo, method: string, route: string, body?: unknown, timeoutMs = 120_000): Promise<unknown> {
  const response = await fetch(`http://127.0.0.1:${daemon.port}${route}`, {
    method,
    headers: { "content-type": "application/json", [BOT_DAEMON_TOKEN_HEADER]: daemon.token },
    body: body === undefined ? null : JSON.stringify(body),
    signal: requestSignal(timeoutMs),
  });
  const reply = daemonReplySchema.parse(await response.json());
  if (!reply.ok) throw new Error(reply.error ?? `bot daemon ${route} failed`);
  return reply.result;
}

function alive(pid: number): boolean {
  try {
    process.kill(pid, 0);
    return true;
  } catch {
    return false;
  }
}

export class BotDaemonClient {
  private readonly home: BotDaemonHome;

  constructor(home: BotDaemonHome) {
    this.home = home;
  }

  private recordedDaemon(): DaemonInfo | null {
    const info = readJson(this.home.file, recordedDaemonSchema.nullable(), null);
    if (!info) return null;
    return { pid: info.pid, port: info.port, token: info.token, startedAt: info.startedAt ?? "" };
  }

  private async runningDaemon(): Promise<DaemonInfo | null> {
    const daemon = this.recordedDaemon();
    return daemon && (await answersAs(daemon)) ? daemon : null;
  }

  private lockAge(): number | null {
    try {
      return Date.now() - statSync(this.home.spawnLock).mtimeMs;
    } catch {
      return null;
    }
  }

  private acquireSpawnLock(): boolean {
    for (let attempt = 0; attempt < 3; attempt += 1) {
      try {
        closeSync(openSync(this.home.spawnLock, "wx"));
        return true;
      } catch (error) {
        if (errnoCode(error) !== "EEXIST") throw error;
        const age = this.lockAge();
        if (age !== null && age < SPAWN_LOCK_STALE_MS) return false;
        rmSync(this.home.spawnLock, { force: true });
      }
    }
    return false;
  }

  private spawnDaemon(): void {
    rmSync(this.home.file, { force: true });
    const log = openSync(this.home.log, "a");
    const child = spawn(process.execPath, ["--import", "tsx", this.home.script], {
      cwd: HARNESS_ROOT,
      detached: true,
      stdio: ["ignore", log, log],
    });
    child.unref();
    closeSync(log);
  }

  private refreshSpawnLock(): void {
    const now = new Date();
    utimesSync(this.home.spawnLock, now, now);
  }

  private async awaitDaemon(heartbeat: () => void = () => undefined): Promise<DaemonInfo> {
    for (let attempt = 0; attempt < 100; attempt += 1) {
      heartbeat();
      await sleep(150);
      const started = await this.runningDaemon();
      if (started) return started;
    }
    throw new Error(`bot daemon did not start (see ${this.home.log})`);
  }

  private async ensureDaemon(): Promise<DaemonInfo> {
    const running = await this.runningDaemon();
    if (running) return running;
    mkdirSync(this.home.directory, { recursive: true });
    if (!this.acquireSpawnLock()) return this.awaitDaemon();
    try {
      const started = await this.runningDaemon();
      if (started) return started;
      this.spawnDaemon();
      return await this.awaitDaemon(() => this.refreshSpawnLock());
    } finally {
      rmSync(this.home.spawnLock, { force: true });
    }
  }

  async request(method: string, route: string, body?: unknown, timeoutMs?: number): Promise<unknown> {
    return call(await this.ensureDaemon(), method, route, body, timeoutMs);
  }

  async status(): Promise<{ running: boolean; pid?: number; bots?: unknown }> {
    const daemon = await this.runningDaemon();
    if (!daemon) return { running: false };
    return { running: true, pid: daemon.pid, bots: await call(daemon, "GET", "/bots") };
  }

  async stop(): Promise<{ wasRunning: boolean; stopped: boolean }> {
    const daemon = await this.runningDaemon();
    if (!daemon) {
      rmSync(this.home.file, { force: true });
      return { wasRunning: false, stopped: true };
    }
    await call(daemon, "POST", "/shutdown", {}, 3000).catch(() => undefined);
    let stopped = await awaitExit(daemon.pid, 4000);
    if (!stopped) {
      process.kill(daemon.pid, "SIGTERM");
      stopped = await awaitExit(daemon.pid, 4000);
    }
    rmSync(this.home.file, { force: true });
    return { wasRunning: true, stopped };
  }

  async running(): Promise<boolean> {
    return (await this.runningDaemon()) !== null;
  }
}

async function answersAs(daemon: DaemonInfo): Promise<boolean> {
  try {
    const health = daemonHealthSchema.parse(await call(daemon, "GET", "/health", undefined, 3000));
    return health.pid === daemon.pid;
  } catch {
    return false;
  }
}

async function awaitExit(pid: number, timeoutMs: number): Promise<boolean> {
  for (let waited = 0; waited < timeoutMs && alive(pid); waited += 100) await sleep(100);
  return !alive(pid);
}

const harnessDaemon = new BotDaemonClient(HARNESS_BOT_DAEMON);

export async function botRequest(method: string, route: string, body?: unknown, timeoutMs?: number): Promise<unknown> {
  return harnessDaemon.request(method, route, body, timeoutMs);
}

export async function botDaemonStatus(): Promise<{ running: boolean; pid?: number; bots?: unknown }> {
  return harnessDaemon.status();
}

export async function stopBotDaemon(): Promise<{ wasRunning: boolean; stopped: boolean }> {
  return harnessDaemon.stop();
}

export async function botDaemonRunning(): Promise<boolean> {
  return harnessDaemon.running();
}
