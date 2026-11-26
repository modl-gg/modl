import { spawn } from "node:child_process";
import { mkdirSync, rmSync } from "node:fs";
import { join } from "node:path";
import { REPORTS_DIR } from "../lib/paths";
import { runFlows } from "./flows";
import { recordReplayGif } from "./replay-gif";
import { jsonString, type HttpResult } from "../lib/http";
import { findInJson } from "./json";
import { createLogger, type Logger } from "../lib/log";
import { errorMessage } from "../lib/util";
import { SHOTS_OWNER_EMAIL, ShotsBackend } from "./backend";
import { capturePanel, captureSetup, type Shot } from "./capture";
import { bootTime, datasetKey, ensureDataset } from "./dataset";
import { REVIEW_CURRENT, REVIEW_FEEDBACK } from "./paths";
import { acceptAllAsBaseline, buildReview, serveReview } from "./review";
import { PANEL_SCREENS, SETUP_SCREEN_IDS, type ScreenContext } from "./screens";
import { EXAMPLE_PLAYER } from "../seed/dataset";

const REVIEW_PORT = 18300;
const PANEL_WARM_UP = [
  "/v1/public/settings",
  "/v1/panel/auth/me",
  "/v1/panel/auth/permissions",
  "/v1/panel/setup",
  "/v1/panel/roles",
  "/v1/panel/tickets?limit=50",
  `/v1/panel/players/${EXAMPLE_PLAYER.uuid}`,
];

interface RunFlags {
  only: string | null;
  rebuildDataset: boolean;
  baseline: boolean;
  check: boolean;
  keepUp: boolean;
  skipSetup: boolean;
}

function flagValue(args: readonly string[], name: string): string | null {
  const index = args.indexOf(name);
  if (index < 0) return null;
  const value = args[index + 1];
  if (value === undefined || value.startsWith("--")) throw new Error(`${name} needs a value`);
  return value;
}

function parseRunFlags(args: readonly string[]): RunFlags {
  const known = new Set(["--only", "--rebuild-dataset", "--baseline", "--check", "--keep-up", "--skip-setup"]);
  for (const arg of args) {
    if (arg.startsWith("--") && !known.has(arg)) throw new Error(`unknown option ${arg}`);
  }
  return {
    only: flagValue(args, "--only"),
    rebuildDataset: args.includes("--rebuild-dataset"),
    baseline: args.includes("--baseline"),
    check: args.includes("--check"),
    keepUp: args.includes("--keep-up"),
    skipSetup: args.includes("--skip-setup"),
  };
}

async function panelGet(backend: ShotsBackend, path: string): Promise<HttpResult> {
  return backend.request(async () => backend.instance().api(path));
}

function publicPathOf(ticket: HttpResult): string | null {
  const url = jsonString(ticket.json, "publicUrl") ?? "";
  if (url === "") return null;
  const parsed = new URL(url);
  return `${parsed.pathname}${parsed.search}`;
}

async function screenContext(backend: ShotsBackend): Promise<ScreenContext> {
  const tickets = await panelGet(backend, "/v1/panel/tickets?limit=50");
  const report = findInJson(tickets.json, (candidate) => (jsonString(candidate, "subject") ?? "").includes(EXAMPLE_PLAYER.name));
  const appeal = findInJson(tickets.json, (candidate) => (jsonString(candidate, "type") ?? "").toLowerCase().includes("appeal"));
  const reportTicketId = jsonString(report, "id");
  const appealId = jsonString(appeal, "id");
  const publicSource = appealId ?? reportTicketId;
  const publicTicketPath = publicSource === null ? null : publicPathOf(await panelGet(backend, `/v1/panel/tickets/${encodeURIComponent(publicSource)}`));
  return { reportTicketId, publicTicketPath };
}

function wantsSetup(flags: RunFlags): boolean {
  if (flags.skipSetup) return false;
  return flags.only === null || SETUP_SCREEN_IDS.some((screen) => screen.includes(flags.only ?? ""));
}

function wantsPanel(flags: RunFlags): boolean {
  return flags.only === null || PANEL_SCREENS.some((screen) => screen.id.includes(flags.only ?? ""));
}

async function capture(backend: ShotsBackend, flags: RunFlags, logger: Logger): Promise<{ shots: Shot[]; datasetRebuilt: boolean }> {
  const options = { outDir: REVIEW_CURRENT, only: flags.only };
  const shots: Shot[] = [];
  if (wantsSetup(flags)) {
    await backend.start({ kind: "fresh" }, bootTime());
    shots.push(...(await captureSetup(backend, options, logger)));
    await backend.stop();
  }
  let datasetRebuilt = false;
  if (wantsPanel(flags)) {
    const dataset = await ensureDataset(backend, logger, { rebuild: flags.rebuildDataset });
    datasetRebuilt = dataset.rebuilt;
    backend.useSession({ cookies: dataset.meta.cookies, sessionKey: dataset.meta.sessionKey }, dataset.meta.apiKey);
    await backend.start({ kind: "snapshot", dir: dataset.dir }, bootTime());
    await backend.warmUp(PANEL_WARM_UP);
    const context = await screenContext(backend);
    shots.push(...(await capturePanel({ backend, credentials: { cookies: dataset.meta.cookies, sessionKey: dataset.meta.sessionKey }, screens: PANEL_SCREENS, context }, options, logger)));
  }
  return { shots, datasetRebuilt };
}

async function run(args: readonly string[], logger: Logger): Promise<number> {
  const flags = parseRunFlags(args);
  const started = Date.now();
  const backend = await ShotsBackend.open(logger);
  const complete = flags.only === null && !flags.skipSetup;
  if (complete) rmSync(REVIEW_CURRENT, { recursive: true, force: true });
  mkdirSync(REVIEW_CURRENT, { recursive: true });
  let result: { shots: Shot[]; datasetRebuilt: boolean };
  try {
    result = await capture(backend, flags, logger);
  } finally {
    if (flags.keepUp) logger.info(`the screenshot panel stays up at ${backend.baseUrl}`);
    else await backend.stop();
  }
  const seconds = Math.round((Date.now() - started) / 1000);
  if (flags.check) {
    const failing = result.shots.filter((shot) => shot.problems.length > 0);
    for (const shot of failing) logger.warn(`${shot.screen}.${shot.variant.id}: ${shot.problems.join("; ")}`);
    logger.info(`${String(result.shots.length)} screenshots in ${String(seconds)} s, ${String(failing.length)} with problems`);
    return failing.length > 0 ? 1 : 0;
  }
  if (flags.baseline) {
    logger.info(`${String(acceptAllAsBaseline())} screenshots are the new baseline (${String(seconds)} s)`);
    return 0;
  }
  const state = await buildReview(result.shots, { datasetRebuilt: result.datasetRebuilt, complete });
  const counts = { changed: 0, new: 0, removed: 0 };
  for (const entry of state.entries) counts[entry.status] += 1;
  logger.info(`${String(state.captured)} screenshots in ${String(seconds)} s: ${String(state.unchanged)} unchanged, ${String(counts.changed)} changed, ${String(counts.new)} new, ${String(counts.removed)} removed`);
  for (const problem of state.problems) logger.warn(`${problem.id}: ${problem.problems.join("; ")}`);
  if (state.entries.length > 0) logger.info(`review them with: npm run review (feedback lands in ${REVIEW_FEEDBACK})`);
  return state.problems.length > 0 ? 1 : 0;
}

async function flows(args: readonly string[], logger: Logger): Promise<number> {
  const only = flagValue(args, "--only");
  const outDir = join(REPORTS_DIR, "flows", new Date().toISOString().replace(/[:.]/g, "-"));
  mkdirSync(outDir, { recursive: true });
  const backend = await ShotsBackend.open(logger);
  try {
    const dataset = await ensureDataset(backend, logger, { rebuild: false });
    const owner = { cookies: dataset.meta.cookies, sessionKey: dataset.meta.sessionKey };
    backend.useSession(owner, dataset.meta.apiKey);
    await backend.start({ kind: "snapshot", dir: dataset.dir }, bootTime());
    await backend.warmUp(PANEL_WARM_UP);
    const results = await runFlows(backend, owner, outDir, only, logger);
    const failed = results.filter((result) => !result.ok).length;
    logger.info(`${String(results.length - failed)} of ${String(results.length)} flows passed; report: ${join(outDir, "flows.md")}`);
    return failed === 0 ? 0 : 1;
  } finally {
    await backend.stop();
  }
}

async function serve(logger: Logger): Promise<void> {
  const backend = await ShotsBackend.open(logger);
  try {
    const dataset = await ensureDataset(backend, logger, { rebuild: false });
    backend.useSession({ cookies: dataset.meta.cookies, sessionKey: dataset.meta.sessionKey }, dataset.meta.apiKey);
    await backend.start({ kind: "snapshot", dir: dataset.dir }, bootTime());
    logger.info(`the seeded panel is up at ${backend.baseUrl} (owner ${SHOTS_OWNER_EMAIL}; sign-in codes print in docker logs modl-shots-backend); Ctrl-C stops it`);
    await new Promise<void>((resolve) => {
      const keepAlive = setInterval(() => undefined, 60_000);
      const stop = () => {
        clearInterval(keepAlive);
        resolve();
      };
      process.once("SIGINT", stop);
      process.once("SIGTERM", stop);
    });
  } finally {
    await backend.stop();
  }
}

async function review(args: readonly string[], logger: Logger): Promise<void> {
  const port = Number(flagValue(args, "--port") ?? REVIEW_PORT);
  const url = await serveReview(port);
  logger.info(`review page: ${url} (Ctrl-C to stop)`);
  if (!args.includes("--no-open") && process.platform === "darwin") spawn("open", [url], { stdio: "ignore", detached: true }).unref();
}

async function main(): Promise<void> {
  const logger = createLogger("shots");
  const [command = "run", ...args] = process.argv.slice(2);
  try {
    if (command === "run") process.exitCode = await run(args, logger);
    else if (command === "review") await review(args, logger);
    else if (command === "serve") await serve(logger);
    else if (command === "dataset-key") process.stdout.write(`${datasetKey()}\n`);
    else if (command === "flows") process.exitCode = await flows(args, logger);
    else if (command === "replay-gif") logger.info(`replay GIF: ${await recordReplayGif(logger)}`);
    else throw new Error(`unknown command ${command}; use run, review, flows, serve, dataset-key, or replay-gif`);
  } catch (error) {
    logger.error(errorMessage(error));
    process.exitCode = 1;
  }
}

await main();
