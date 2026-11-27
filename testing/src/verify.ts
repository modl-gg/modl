import { execFileSync, spawn } from "node:child_process";
import { createHash } from "node:crypto";
import { existsSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";
import { createLogger } from "./lib/log";
import { HARNESS_ROOT, REPO_ROOT } from "./lib/paths";
import { writeJson } from "./lib/util";

const VERIFY_RECORD = join(HARNESS_ROOT, ".verify", "last.json");
const log = createLogger("verify");

interface Gate {
  name: string;
  command: string;
  args: string[];
  cwd: string;
}

function gitBytes(args: string[]): Buffer {
  return execFileSync("git", ["-c", "core.quotePath=false", ...args], { cwd: REPO_ROOT, maxBuffer: 512 * 1024 * 1024, timeout: 60_000 });
}

function gitPaths(args: string[]): string[] {
  return gitBytes([...args, "-z"]).toString("utf8").split("\0").filter((entry) => entry !== "");
}

export function changedFiles(): string[] {
  return [...new Set([...gitPaths(["diff", "--name-only", "--no-renames", "HEAD"]), ...gitPaths(["ls-files", "--others", "--exclude-standard"])])].sort();
}

export function diffFingerprint(): string {
  const hash = createHash("sha256").update(gitBytes(["diff", "HEAD", "--binary"]));
  for (const file of gitPaths(["ls-files", "--others", "--exclude-standard"]).sort()) {
    const path = join(REPO_ROOT, file);
    hash.update(file);
    if (existsSync(path) && statSync(path).isFile()) hash.update(readFileSync(path));
  }
  return hash.digest("hex");
}

const DOCS_ONLY_FILE = join(HARNESS_ROOT, "docs-only-paths.txt");

function globToRegExp(glob: string): RegExp {
  const escaped = glob.replace(/[.+^${}()|[\]\\]/g, "\\$&").replace(/\*/g, ".*").replace(/\?/g, ".");
  return new RegExp(`^${escaped}$`);
}

function docsOnlyMatcher(): (file: string) => boolean {
  const lines = readFileSync(DOCS_ONLY_FILE, "utf8").split("\n").map((line) => line.trim()).filter((line) => line !== "");
  const include = lines.filter((line) => !line.startsWith("!")).map(globToRegExp);
  const exclude = lines.filter((line) => line.startsWith("!")).map((line) => globToRegExp(line.slice(1)));
  return (file) => include.some((pattern) => pattern.test(file)) && !exclude.some((pattern) => pattern.test(file));
}

interface Areas {
  panel: boolean;
  wiki: boolean;
  panelSource: boolean;
  backend: boolean;
  minecraft: boolean;
  libs: Set<string>;
  buildLogic: boolean;
  proto: boolean;
  harness: boolean;
  runtime: boolean;
}

function areasOf(files: readonly string[]): Areas {
  const areas: Areas = { panel: false, wiki: false, panelSource: false, backend: false, minecraft: false, libs: new Set(), buildLogic: false, proto: false, harness: false, runtime: false };
  const docsOnly = docsOnlyMatcher();
  for (const file of files) {
    if (docsOnly(file)) continue;
    if (file.startsWith("wiki/")) areas.wiki = true;
    if (file.startsWith("panel/") || ["package.json", "package-lock.json", "tsconfig.strict.json", "eslint.strict.mjs"].includes(file)) areas.panel = true;
    if (["tsconfig.strict.json", "eslint.strict.mjs"].includes(file)) areas.harness = true;
    if (["LICENSE", "THIRD_PARTY_NOTICES.md"].includes(file) || file.startsWith("licenses/")) areas.buildLogic = true;
    if (file.startsWith("panel/client/src/")) areas.panelSource = true;
    if (file.startsWith("backend/")) areas.backend = true;
    if (file.startsWith("minecraft/")) areas.minecraft = true;
    const lib = /^libs\/([^/]+)\//.exec(file)?.[1];
    if (lib !== undefined) areas.libs.add(lib);
    if (file.startsWith("build-logic/") || file.startsWith("gradle/") || ["build.gradle.kts", "settings.gradle.kts", "gradle.properties"].includes(file)) areas.buildLogic = true;
    if (file.startsWith("proto/")) areas.proto = true;
    if (file.startsWith("testing/")) areas.harness = true;
  }
  areas.runtime = areas.backend || areas.minecraft || areas.libs.size > 0 || areas.proto || areas.buildLogic;
  return areas;
}

function gradle(name: string, ...args: string[]): Gate {
  return { name, command: "./gradlew", args, cwd: REPO_ROOT };
}

function npm(name: string, cwd: string, ...args: string[]): Gate {
  return { name, command: "npm", args, cwd };
}

function gatesFor(areas: Areas, e2e: boolean): Gate[] {
  const gates: Gate[] = [];
  if (areas.proto) gates.push(npm("proto lint", join(REPO_ROOT, "proto"), "run", "lint"));
  if (areas.panel || areas.proto) gates.push(npm("panel check", REPO_ROOT, "run", "check"), npm("panel build", REPO_ROOT, "run", "build"));
  else if (areas.wiki) gates.push(npm("panel check", REPO_ROOT, "run", "check"));
  if (areas.buildLogic) gates.push(gradle("build-logic tests", "-p", "build-logic", "test"), gradle("full Java build", "build"));
  else {
    const libs = areas.libs.size > 0;
    if (areas.backend || areas.proto || libs) gates.push(gradle("backend build", ":backend:build", "-Pmodl.skipPanel=true"));
    if (areas.minecraft || areas.proto || libs) gates.push(gradle("minecraft build", "-p", "minecraft", "build"));
    for (const lib of [...areas.libs].sort()) gates.push(gradle(`libs/${lib} build`, "-p", `libs/${lib}`, "build"));
  }
  if (areas.harness || areas.proto) gates.push(npm("harness check", HARNESS_ROOT, "run", "check"));
  const distNeeded = areas.panelSource || (e2e && (areas.runtime || areas.panel));
  if (distNeeded) gates.push(gradle("release artifacts", "dist"));
  if (areas.panelSource) gates.push(npm("screenshots", HARNESS_ROOT, "run", "shots"));
  if (e2e && (areas.runtime || areas.panel)) gates.push(npm("quick scenario matrix", HARNESS_ROOT, "run", "cli", "--", "matrix", "--tier", "quick"));
  return gates;
}

async function runGate(gate: Gate): Promise<{ ok: boolean; seconds: number }> {
  const started = Date.now();
  log.info(`> ${gate.name}: ${gate.command} ${gate.args.join(" ")}`);
  const code = await new Promise<number>((resolve) => {
    const child = spawn(gate.command, gate.args, { cwd: gate.cwd, stdio: "inherit" });
    child.on("close", (exit) => {
      resolve(exit ?? 1);
    });
  });
  const seconds = Math.round((Date.now() - started) / 1000);
  log.info(`${code === 0 ? "passed" : "FAILED"}: ${gate.name} (${String(seconds)} s)`);
  return { ok: code === 0, seconds };
}

async function main(): Promise<void> {
  const args = process.argv.slice(2);
  if (args.includes("--fingerprint")) {
    process.stdout.write(`${diffFingerprint()}\n`);
    return;
  }
  const e2e = args.includes("--e2e");
  const dryRun = args.includes("--dry-run");
  const files = changedFiles();
  const areas = areasOf(files);
  const gates = gatesFor(areas, e2e);
  if (gates.length === 0) log.info(files.length === 0 ? "no uncommitted changes" : "only documentation changed; nothing to run");
  else log.info(`${String(files.length)} changed files; gates: ${gates.map((gate) => gate.name).join(", ")}`);
  if (!e2e && areas.runtime) log.info("plugin or backend code changed: add --e2e to rebuild dist and run the quick scenario matrix");
  if (dryRun) return;
  const fingerprint = diffFingerprint();
  const results: { gate: string; ok: boolean; seconds: number }[] = [];
  for (const gate of gates) {
    const result = await runGate(gate);
    results.push({ gate: gate.name, ...result });
    if (!result.ok) break;
  }
  const ok = results.every((result) => result.ok);
  const unchanged = (diffFingerprint()) === fingerprint;
  if (ok && unchanged) writeJson(VERIFY_RECORD, { fingerprint, verifiedAt: new Date().toISOString(), e2e, gates: results });
  if (ok && !unchanged) log.warn("files changed while verify ran; run it again");
  process.exitCode = ok ? 0 : 1;
}

await main();
