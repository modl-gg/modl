import { createHash } from "node:crypto";
import { cpSync, existsSync, mkdirSync, readdirSync, statSync } from "node:fs";
import { basename, dirname, join } from "node:path";
import { yamlText } from "../lib/config-files";
import { DataDirectories, SetupCodeFiles } from "../lib/contract";
import { compose, containerLogs, containerState, runDocker } from "../lib/docker";
import { HttpStatus, jsonField, type HttpResult, type SessionCredentials } from "../lib/http";
import { InstanceClient, InstanceError } from "../lib/instance";
import { PANEL_JAVA_VERSION, temurinJdk } from "../lib/java-runtime";
import type { Logger } from "../lib/log";
import { ContainerLog } from "../lib/logs";
import { CONTAINER_ASSETS_DIR, REPO_ROOT } from "../lib/paths";
import { nearestFreePort } from "../lib/ports";
import { serverJarPath } from "../lib/product";
import { hostUser } from "../lib/settings";
import type { InstanceState } from "../lib/state";
import { backendJvmOptions, identityMounts, writeIdentityFiles } from "../lib/topologies/compose";
import { readTextFile, removePath, sleep, writeText } from "../lib/util";
import { REVIEW_DIR, SHOTS_CLOCK_DIR, SHOTS_COMPOSE_FILE, SHOTS_DATA, SHOTS_IDENTITY } from "./paths";

const PROJECT = "modl-shots";
const SERVICE = "backend";
const CONTAINER = "modl-shots-backend";
const PREFERRED_PORT = 18130;
const CONTAINER_DATA = "/data";
const CLOCK_FILE = "offset";
const CLOCK_CACHE_SECONDS = 1;
const CLOCK_SETTLE_MS = 1100;
const HEALTH_TIMEOUT_MS = 180_000;
const BUILD_TIMEOUT_MS = 600_000;
const HEAP_MB = 1024;
const RATE_LIMIT_WINDOW_MS = 61_000;
const RATE_LIMIT_RETRIES = 3;
const RETRY_MARGIN_MS = 1000;
const PANEL_SOURCE = join(REPO_ROOT, "panel", "client", "src");

export const SHOTS_OWNER_EMAIL = "owner@modl.test";
export const SHOTS_SERVER_NAME = "Emberfall Network";

const CLOCK_DOCKERFILE = [
  `FROM ${temurinJdk(PANEL_JAVA_VERSION)}`,
  "RUN apt-get update && apt-get install -y --no-install-recommends faketime && rm -rf /var/lib/apt/lists/* \\",
  ' && ln -s "$(find /usr/lib -name libfaketime.so.1 | head -n 1)" /usr/local/lib/libfaketime.so.1',
  "",
].join("\n");

function clockImage(): string {
  return `modl-testing-clock:${createHash("sha256").update(CLOCK_DOCKERFILE).digest("hex").slice(0, 12)}`;
}

async function ensureClockImage(logger: Logger): Promise<string> {
  const image = clockImage();
  const present = await runDocker(["image", "inspect", image], { allowFailure: true });
  if (present.code === 0) return image;
  logger.info(`building ${image} (the JDK with libfaketime, once)`);
  await runDocker(["build", "-t", image, "-"], { input: CLOCK_DOCKERFILE, timeoutMs: BUILD_TIMEOUT_MS });
  return image;
}

function offsetText(offsetMs: number): string {
  const seconds = Math.round(offsetMs / 1000);
  return seconds >= 0 ? `+${String(seconds)}` : String(seconds);
}

const WARM_UP_LIMIT_MS = 90_000;
const WARM_REQUEST_MS = 150;
const NETWORK_RETRIES = 3;
const NETWORK_RETRY_DELAY_MS = 1000;

async function sendWithNetworkRetry(send: () => Promise<HttpResult>): Promise<HttpResult> {
  for (let attempt = 0; ; attempt += 1) {
    try {
      return await send();
    } catch (error) {
      if (attempt >= NETWORK_RETRIES || !(error instanceof TypeError)) throw error;
      await sleep(NETWORK_RETRY_DELAY_MS);
    }
  }
}

function retryAfterMs(result: HttpResult): number {
  const seconds = Number(jsonField(result.json, "retryAfterSeconds"));
  return Number.isFinite(seconds) && seconds > 0 ? seconds * 1000 : RATE_LIMIT_WINDOW_MS;
}

function newestModification(dir: string): number {
  let newest = 0;
  for (const entry of readdirSync(dir, { recursive: true, withFileTypes: true })) {
    if (entry.isFile()) newest = Math.max(newest, statSync(join(entry.parentPath, entry.name)).mtimeMs);
  }
  return newest;
}

export type DataSource = { kind: "fresh" } | { kind: "snapshot"; dir: string };

export class ShotsBackend {
  readonly port: number;
  readonly log = new ContainerLog(SERVICE, CONTAINER);
  private readonly logger: Logger;
  private client: InstanceClient | null = null;
  private offsetMs = 0;
  private credentials: SessionCredentials = { cookies: {}, sessionKey: null };
  private apiKey: string | null = null;

  private constructor(port: number, logger: Logger) {
    this.port = port;
    this.logger = logger;
  }

  static async open(logger: Logger): Promise<ShotsBackend> {
    if ((await containerState(CONTAINER)).status === "running") {
      throw new Error(`a screenshot panel is already running (${CONTAINER}, for example npm run serve); stop it first`);
    }
    await compose(PROJECT, SHOTS_COMPOSE_FILE, ["down", "--volumes", "--timeout", "30"], { allowFailure: true, timeoutMs: 120_000 });
    return new ShotsBackend(await nearestFreePort(PREFERRED_PORT, new Set()), logger);
  }

  get baseUrl(): string {
    return `http://127.0.0.1:${String(this.port)}`;
  }

  get dataDir(): string {
    return SHOTS_DATA;
  }

  async start(source: DataSource, virtualNow: number): Promise<void> {
    const image = await ensureClockImage(this.logger);
    const jar = serverJarPath();
    if (!existsSync(jar)) throw new Error(`${jar} is missing; run ./gradlew dist in the repository`);
    if (newestModification(PANEL_SOURCE) > statSync(jar).mtimeMs) throw new Error(`${jar} is older than the panel source; run ./gradlew dist so the screenshots show the current panel`);
    removePath(SHOTS_DATA);
    if (source.kind === "snapshot") cpSync(source.dir, SHOTS_DATA, { recursive: true });
    else mkdirSync(SHOTS_DATA, { recursive: true });
    writeIdentityFiles(SHOTS_IDENTITY);
    this.writeClock(virtualNow);
    writeText(SHOTS_COMPOSE_FILE, this.composeFile(image, jar));
    this.client = null;
    await compose(PROJECT, SHOTS_COMPOSE_FILE, ["up", "-d", "--force-recreate"], { timeoutMs: 180_000 });
    await this.instance().waitHealthy(HEALTH_TIMEOUT_MS);
  }

  async setClock(virtualNow: number): Promise<void> {
    this.writeClock(virtualNow);
    await sleep(CLOCK_SETTLE_MS);
  }

  async skipRateLimitWindow(waitMs = RATE_LIMIT_WINDOW_MS): Promise<void> {
    await this.setClock(Date.now() + this.offsetMs + waitMs + RETRY_MARGIN_MS);
  }

  async warmUp(paths: readonly string[]): Promise<void> {
    const deadline = Date.now() + WARM_UP_LIMIT_MS;
    while (Date.now() < deadline) {
      const started = Date.now();
      for (const path of paths) await this.request(async () => this.instance().api(path));
      if ((Date.now() - started) / paths.length < WARM_REQUEST_MS) return;
    }
  }

  async request(send: () => Promise<HttpResult>): Promise<HttpResult> {
    let result = await sendWithNetworkRetry(send);
    for (let attempt = 0; attempt < RATE_LIMIT_RETRIES && result.status === HttpStatus.tooManyRequests; attempt += 1) {
      await this.skipRateLimitWindow(retryAfterMs(result));
      result = await sendWithNetworkRetry(send);
    }
    return result;
  }

  async withRateLimitRetry<T>(run: () => Promise<T>): Promise<T> {
    for (let attempt = 0; ; attempt += 1) {
      try {
        return await run();
      } catch (error) {
        if (attempt >= RATE_LIMIT_RETRIES || !(error instanceof InstanceError) || error.result?.status !== HttpStatus.tooManyRequests) throw error;
        await this.skipRateLimitWindow(retryAfterMs(error.result));
      }
    }
  }

  async stop(): Promise<void> {
    const logs = await containerLogs(CONTAINER).catch(() => "");
    if (logs !== "") writeText(join(REVIEW_DIR, "backend.log"), logs);
    await compose(PROJECT, SHOTS_COMPOSE_FILE, ["down", "--volumes", "--timeout", "60"], { allowFailure: true, timeoutMs: 180_000 });
  }

  useSession(credentials: SessionCredentials, apiKey: string | null): void {
    this.credentials = credentials;
    this.apiKey = apiKey;
    this.client = null;
  }

  instance(): InstanceClient {
    this.client ??= new InstanceClient(
      this.instanceState(),
      this.log,
      {
        readFile: async (path) => Promise.resolve(readTextFile(join(SHOTS_DATA, path))),
        setupCodeFile: () => SetupCodeFiles.standalone,
        dataDirectory: () => DataDirectories.standalone,
        restartForNewSetupCode: async () => this.restart(),
      },
      (updated) => {
        this.credentials = { cookies: updated.cookies, sessionKey: updated.sessionKey };
        this.apiKey = updated.apiKey;
      },
      this.logger,
    );
    return this.client;
  }

  private instanceState(): InstanceState {
    return {
      baseUrl: this.baseUrl,
      noticeServer: SERVICE,
      ownerEmail: SHOTS_OWNER_EMAIL,
      presetAdmin: false,
      cookies: { ...this.credentials.cookies },
      sessionKey: this.credentials.sessionKey,
      apiKey: this.apiKey,
      lastLoginCodeAt: null,
    };
  }

  private async restart(): Promise<void> {
    await compose(PROJECT, SHOTS_COMPOSE_FILE, ["restart", SERVICE], { timeoutMs: 180_000 });
    await this.instance().waitHealthy(HEALTH_TIMEOUT_MS);
  }

  private writeClock(virtualNow: number): void {
    this.offsetMs = virtualNow - Date.now();
    writeText(join(SHOTS_CLOCK_DIR, CLOCK_FILE), offsetText(this.offsetMs));
  }

  private composeFile(image: string, jar: string): string {
    return yamlText({
      name: PROJECT,
      services: {
        [SERVICE]: {
          image,
          container_name: CONTAINER,
          user: hostUser(),
          working_dir: CONTAINER_DATA,
          entrypoint: ["/bin/sh", "/modl-testing/run.sh"],
          environment: {
            SERVER_JAR: `/server/${basename(jar)}`,
            JVM_OPTS: backendJvmOptions(HEAP_MB).join(" "),
            SERVER_ARGS: "",
            TZ: "UTC",
            LD_PRELOAD: "/usr/local/lib/libfaketime.so.1",
            FAKETIME_TIMESTAMP_FILE: `/clock/${CLOCK_FILE}`,
            FAKETIME_CACHE_DURATION: String(CLOCK_CACHE_SECONDS),
            FAKETIME_DONT_FAKE_MONOTONIC: "1",
            MODL_DATA_DIR: CONTAINER_DATA,
            MODL_PORT: "8080",
            MODL_BIND_ADDRESS: "0.0.0.0",
            MODL_SERVER_NAME: SHOTS_SERVER_NAME,
            MODL_UPDATE_CHECK: "false",
          },
          volumes: [
            `${SHOTS_DATA}:${CONTAINER_DATA}`,
            `${CONTAINER_ASSETS_DIR}:/modl-testing:ro`,
            `${dirname(jar)}:/server:ro`,
            `${SHOTS_CLOCK_DIR}:/clock:ro`,
            ...identityMounts(SHOTS_IDENTITY),
          ],
          ports: [`127.0.0.1:${String(this.port)}:8080`],
          stop_grace_period: "60s",
          restart: "no",
        },
      },
    });
  }
}
