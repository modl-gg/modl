import { execFileSync } from "node:child_process";
import { join } from "node:path";
import { createLogger } from "../lib/log";
import { REPO_ROOT } from "../lib/paths";
import { planDataset, realClock, seedPlayers, type MinecraftTarget } from "./dataset";

const BASE_URL = process.env["MODL_SEED_URL"] ?? "http://localhost:18080";
const DEV_DATABASE = join(REPO_ROOT, "dev", ".data", "database", "modl.db");
const log = createLogger("seed");

function apiKey(): string {
  const fromEnv = process.env["MODL_SEED_API_KEY"];
  if (fromEnv) return fromEnv;
  return execFileSync("sqlite3", ["-readonly", DEV_DATABASE, "select api_key from modl_instance;"], { encoding: "utf8" }).trim();
}

class LenientTarget implements MinecraftTarget {
  private failures = 0;
  private readonly counts = new Map<string, number>();
  private readonly key: string;

  constructor(key: string) {
    this.key = key;
  }

  async post(path: string, body: unknown, label: string): Promise<unknown> {
    const response = await fetch(`${BASE_URL}${path}`, {
      method: "POST",
      headers: { "Content-Type": "application/json", "X-API-Key": this.key },
      body: JSON.stringify(body),
    });
    const text = await response.text();
    if (!response.ok) {
      this.failures += 1;
      log.warn(`${label} failed (${String(response.status)}): ${text.slice(0, 200)}`);
      return null;
    }
    this.counts.set(label, (this.counts.get(label) ?? 0) + 1);
    if (text === "") return null;
    const parsed: unknown = JSON.parse(text);
    return parsed;
  }

  summary(): void {
    for (const [label, count] of this.counts) log.info(`${label}: ${String(count)}`);
    if (this.failures === 0) {
      log.info("Done.");
      return;
    }
    log.warn(`Done with ${String(this.failures)} failed requests.`);
    process.exitCode = 1;
  }
}

async function main(): Promise<void> {
  const target = new LenientTarget(apiKey());
  log.info(`Seeding ${BASE_URL} with ${String(seedPlayers().length)} players...`);
  await planDataset({ minecraft: target, panel: null, end: Date.now() }).play(realClock);
  target.summary();
}

await main();
