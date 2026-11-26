import { createHash } from "node:crypto";
import { cpSync, existsSync, readdirSync, readFileSync } from "node:fs";
import { join, relative } from "node:path";
import { z } from "zod";
import { HttpStatus, type HttpResult } from "../lib/http";
import type { Logger } from "../lib/log";
import { REPO_ROOT, SOURCE_DIR } from "../lib/paths";
import { productVersion } from "../lib/product";
import { readJson, removePath, writeJson } from "../lib/util";
import { DAY_MS, HISTORY_DAYS, planDataset, type MinecraftTarget, type PanelTarget, type SeedClock } from "../seed/dataset";
import type { ShotsBackend } from "./backend";
import { SHOTS_DATASETS } from "./paths";

export const VIRTUAL_NOW = Date.parse("2026-06-15T14:00:00.000Z");
const SEED_END = VIRTUAL_NOW - 10 * 60_000;
const BOOT_LEAD_MS = 2 * 60_000;
const DATASET_SOURCES = ["seed/dataset.ts", "shots/dataset.ts", "lib/instance.ts"].map((file) => join(SOURCE_DIR, file));
const PRODUCT_SOURCES = [
  "backend/src/main/java/gg/modl/backend/server/service/ServerProvisioningService.java",
  "backend/src/main/java/gg/modl/backend/knowledgebase/data/StarterArticles.java",
].map((file) => join(REPO_ROOT, file));
const MIGRATIONS = join(REPO_ROOT, "backend", "src", "main", "resources", "db", "migration", "sqlite");

const DATASET_META = z.object({
  key: z.string(),
  cookies: z.record(z.string(), z.string()),
  sessionKey: z.string().nullable(),
  apiKey: z.string(),
  builtAt: z.string(),
});

export type DatasetMeta = z.infer<typeof DATASET_META>;

export interface Dataset {
  dir: string;
  meta: DatasetMeta;
  rebuilt: boolean;
}

function filesUnder(dir: string): string[] {
  if (!existsSync(dir)) return [];
  return readdirSync(dir, { recursive: true, withFileTypes: true })
    .filter((entry) => entry.isFile())
    .map((entry) => join(entry.parentPath, entry.name))
    .sort();
}

export function datasetKey(): string {
  const hash = createHash("sha256").update(productVersion());
  for (const source of [...DATASET_SOURCES, ...PRODUCT_SOURCES]) hash.update(readFileSync(source));
  for (const file of filesUnder(MIGRATIONS)) hash.update(relative(MIGRATIONS, file)).update(readFileSync(file));
  return hash.digest("hex").slice(0, 16);
}

function requireSuccess(result: HttpResult, label: string): unknown {
  if (result.status < HttpStatus.ok || result.status >= 300) {
    throw new Error(`${label}: ${result.method} ${result.path} answered ${String(result.status)}: ${result.text.slice(0, 300)}`);
  }
  return result.json;
}

class StrictTargets implements MinecraftTarget, PanelTarget {
  private readonly backend: ShotsBackend;

  constructor(backend: ShotsBackend) {
    this.backend = backend;
  }

  async post(path: string, body: unknown, label: string): Promise<unknown> {
    return this.send(label, async () => this.backend.instance().apiWithCredentials(path, { method: "POST", json: body, auth: "api-key" }));
  }

  async patch(path: string, body: unknown, label: string): Promise<unknown> {
    return this.send(label, async () => this.backend.instance().apiWithCredentials(path, { method: "PATCH", json: body }));
  }

  async postPublic(path: string, body: unknown, label: string): Promise<unknown> {
    return this.send(label, async () => this.backend.instance().http.request(path, { method: "POST", json: body, auth: "none" }));
  }

  private async send(label: string, request: () => Promise<HttpResult>): Promise<unknown> {
    return requireSuccess(await this.backend.request(request), label);
  }
}

function fakeClock(backend: ShotsBackend): SeedClock {
  return { advanceTo: async (virtualMs) => backend.setClock(virtualMs) };
}

export function bootTime(): number {
  return VIRTUAL_NOW - BOOT_LEAD_MS;
}

async function build(backend: ShotsBackend, key: string, logger: Logger): Promise<Dataset> {
  const dir = join(SHOTS_DATASETS, key);
  const start = VIRTUAL_NOW - (HISTORY_DAYS + 1) * DAY_MS;
  logger.info(`building the screenshot dataset ${key} (a few minutes, once per seed or migration change)`);
  await backend.start({ kind: "fresh" }, start);
  const instance = backend.instance();
  await backend.withRateLimitRetry(async () => instance.ensureOwnerSession());
  await backend.withRateLimitRetry(async () => instance.completeOpenSteps());
  await backend.withRateLimitRetry(async () => instance.revealApiKey());
  const targets = new StrictTargets(backend);
  await planDataset({ minecraft: targets, panel: targets, end: SEED_END }).play(fakeClock(backend));
  await backend.withRateLimitRetry(async () => instance.ensureOwnerSession());
  const credentials = instance.http.credentials();
  const apiKey = instance.apiKey;
  if (apiKey === null) throw new Error("the dataset build has no API key");
  await backend.stop();
  removePath(dir);
  cpSync(backend.dataDir, join(dir, "data"), { recursive: true });
  const meta: DatasetMeta = { key, cookies: credentials.cookies, sessionKey: credentials.sessionKey, apiKey, builtAt: new Date().toISOString() };
  writeJson(join(dir, "meta.json"), meta);
  return { dir: join(dir, "data"), meta, rebuilt: true };
}

export async function ensureDataset(backend: ShotsBackend, logger: Logger, options: { rebuild: boolean }): Promise<Dataset> {
  const key = datasetKey();
  const dir = join(SHOTS_DATASETS, key);
  const meta = readJson(join(dir, "meta.json"), DATASET_META.nullable(), null);
  if (!options.rebuild && meta !== null && existsSync(join(dir, "data"))) return { dir: join(dir, "data"), meta, rebuilt: false };
  for (const stale of existsSync(SHOTS_DATASETS) ? readdirSync(SHOTS_DATASETS) : []) removePath(join(SHOTS_DATASETS, stale));
  return build(backend, key, logger);
}
