import { existsSync, mkdirSync, readdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { chromium } from "playwright-core";
import { BotPool, entryTarget } from "../lib/bots/pool";
import type { TestBot } from "../lib/bots/bot";
import { Environment } from "../lib/environment";
import { upOptions } from "../lib/inspect";
import { down, up } from "../lib/lifecycle";
import type { Logger } from "../lib/log";
import { runProcess } from "../lib/process";
import { sleep } from "../lib/util";
import { captureReplayOf, openReplay, replayControl } from "../video/scripts/replay-world";
import { recordEveryPlayer } from "../video/scripts/stage";
import { localeLookup } from "./flows";
import { REVIEW_DIR } from "./paths";

const FIGHTERS = ["PixelPanda", "RedstoneRex", "LunaBuilds"] as const;
const WORLD_SEED = "modl";
const FIGHT_MS = 35_000;
const ATTACK_RANGE = 8;
const ATTACK_TICK_MS = 350;
const VIEWPORT = { width: 1280, height: 720 } as const;
const GIF_WIDTH = 600;
const GIF_FPS = 8;
const GIF_COLORS = 96;
const PLAY_HOLD_MS = 5500;
const FIGHT_POSITION = 0.55;
const GEAR = ["minecraft:iron_sword", "minecraft:shield"];
const ARMOR: Record<string, string> = { head: "minecraft:iron_helmet", chest: "minecraft:iron_chestplate", legs: "minecraft:iron_leggings", feet: "minecraft:iron_boots" };

function naturalWorld(serverDir: string): void {
  const file = join(serverDir, "server.properties");
  const wanted: Record<string, string> = {
    "level-type": "minecraft\\:normal",
    "generator-settings": "{}",
    "level-seed": WORLD_SEED,
    pvp: "true",
    "spawn-protection": "0",
    difficulty: "peaceful",
    "spawn-monsters": "false",
  };
  const lines = readFileSync(file, "utf8").split("\n").filter((line) => !Object.keys(wanted).some((key) => line.startsWith(`${key}=`)));
  writeFileSync(file, [...lines.filter((line) => line !== ""), ...Object.entries(wanted).map(([key, value]) => `${key}=${value}`), ""].join("\n"));
  for (const entry of readdirSync(serverDir)) {
    if (/^world(_nether|_the_end)?$/.test(entry)) rmSync(join(serverDir, entry), { recursive: true, force: true });
  }
}

async function arm(environment: Environment, server: string, bot: TestBot): Promise<void> {
  const serverConsole = environment.console(server);
  for (const item of GEAR) await serverConsole.run(`give ${bot.username} ${item}`);
  for (const [slot, item] of Object.entries(ARMOR)) await serverConsole.run(`item replace entity ${bot.username} armor.${slot} with ${item}`);
  await serverConsole.run(`effect give ${bot.username} minecraft:resistance 300 2 true`);
  await serverConsole.run(`effect give ${bot.username} minecraft:regeneration 300 1 true`);
}

async function fight(bots: readonly TestBot[]): Promise<void> {
  const until = Date.now() + FIGHT_MS;
  await Promise.all(
    bots.map(async (bot) => {
      while (Date.now() < until) {
        const target = await bot.duel(ATTACK_RANGE);
        if (target === null) await bot.wander(1200);
        else await sleep(ATTACK_TICK_MS);
      }
    }),
  );
}

interface ViewerRecording {
  video: string;
  startSeconds: number;
  seconds: number;
  crop: { x: number; y: number; width: number; height: number };
}

async function recordViewer(environment: Environment, replayId: string, workDir: string): Promise<ViewerRecording> {
  const browser = await chromium.launch();
  try {
    const context = await browser.newContext({ viewport: VIEWPORT, colorScheme: "dark", recordVideo: { dir: workDir, size: VIEWPORT } });
    const credentials = environment.instance.http.credentials();
    const origin = environment.instance.baseUrl;
    await context.addInitScript({ content: `try{localStorage.setItem("modl.sessionKey",${JSON.stringify(credentials.sessionKey ?? "")})}catch(e){}` });
    await context.addCookies(Object.entries(credentials.cookies).map(([name, value]) => ({ name, value, domain: new URL(origin).hostname, path: "/" })));
    const page = await context.newPage();
    const opened = Date.now();
    await openReplay(page, origin, replayId);
    const dialog = await page.getByRole("dialog").last().boundingBox();
    const timeline = await page.getByRole("dialog").getByRole("slider", { name: localeLookup()("replayViewer.timeline") }).boundingBox();
    if (dialog === null || timeline === null) throw new Error("the replay viewer has no visible dialog or timeline");
    await page.mouse.click(timeline.x + timeline.width * FIGHT_POSITION, timeline.y + timeline.height / 2);
    await page.mouse.move(dialog.x + dialog.width / 2, dialog.y - 5);
    await sleep(1500);
    const startSeconds = (Date.now() - opened) / 1000;
    await replayControl(page, "Play").click();
    await sleep(PLAY_HOLD_MS);
    const seconds = (Date.now() - opened) / 1000 - startSeconds;
    const video = page.video();
    await context.close();
    if (video === null) throw new Error("the browser recorded no video");
    const crop = { x: Math.round(dialog.x), y: Math.round(dialog.y), width: Math.round(dialog.width) - (Math.round(dialog.width) % 2), height: Math.round(dialog.height) - (Math.round(dialog.height) % 2) };
    return { video: await video.path(), startSeconds, seconds, crop };
  } finally {
    await browser.close();
  }
}

async function toGif(recording: ViewerRecording, gif: string): Promise<void> {
  const { crop } = recording;
  const filter = [
    `crop=${String(crop.width)}:${String(crop.height)}:${String(crop.x)}:${String(crop.y)}`,
    `fps=${String(GIF_FPS)}`,
    `scale=${String(GIF_WIDTH)}:-1:flags=lanczos,split[a][b];[a]palettegen=max_colors=${String(GIF_COLORS)}:stats_mode=diff[p];[b][p]paletteuse=dither=none:diff_mode=rectangle`,
  ].join(",");
  await runProcess("ffmpeg", ["-y", "-ss", recording.startSeconds.toFixed(2), "-t", recording.seconds.toFixed(2), "-i", recording.video, "-vf", filter, "-loop", "0", gif], { timeoutMs: 300_000 });
}

export async function recordReplayGif(logger: Logger): Promise<string> {
  const workDir = join(REVIEW_DIR, "replay-gif");
  rmSync(workDir, { recursive: true, force: true });
  mkdirSync(workDir, { recursive: true });
  let bots: BotPool | null = null;
  try {
    await up(upOptions({ topology: "standalone", fresh: true }), logger);
    const environment = Environment.current(logger);
    const pool = new BotPool(() => entryTarget(environment.state), logger);
    bots = pool;
    const server = environment.state.coreServer;
    logger.info("regenerating the world as normal terrain");
    await environment.stopGracefully(server);
    naturalWorld(environment.server(server).dataDir);
    const since = await environment.log(server).mark();
    await environment.startExisting(server);
    await environment.waitReady(server, since);
    await environment.instance.ensureOwnerSession();
    await recordEveryPlayer(environment, server);
    const serverConsole = environment.console(server);
    await serverConsole.run("time set 1000");
    await serverConsole.run("weather clear 600");
    const fighters: TestBot[] = [];
    for (const name of FIGHTERS) fighters.push(await pool.join(name));
    for (const bot of fighters) await arm(environment, server, bot);
    const [first, ...others] = fighters;
    if (first === undefined) throw new Error("no fighters joined");
    for (const [index, bot] of others.entries()) await serverConsole.run(`execute at ${first.username} run tp ${bot.username} ~${String(index === 0 ? 3 : -2)} ~ ~${String(index === 0 ? 2 : 3)}`);
    logger.info(`the bots fight for ${String(FIGHT_MS / 1000)} s`);
    await fight(fighters);
    const replayId = await captureReplayOf(environment, server, first.username);
    logger.info(`replay ${replayId} captured; recording the viewer`);
    const recording = await recordViewer(environment, replayId, workDir);
    const gif = join(workDir, "panel-replay.gif");
    await toGif(recording, gif);
    if (!existsSync(gif)) throw new Error("ffmpeg wrote no GIF");
    return gif;
  } finally {
    bots?.quitAll();
    await down({ keepData: false }, logger);
  }
}
