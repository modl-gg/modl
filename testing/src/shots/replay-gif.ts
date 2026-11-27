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
const WIDTH = 920;
const FPS = 25;
const WEBP_QUALITY = 75;
const PLAY_MS = 5500;
const END_MARGIN_SECONDS = 2;
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

async function captureViewerFrames(environment: Environment, replayId: string, framesDir: string, logger: Logger): Promise<number> {
  const browser = await chromium.launch();
  try {
    const context = await browser.newContext({ viewport: VIEWPORT, colorScheme: "dark" });
    const credentials = environment.instance.http.credentials();
    const origin = environment.instance.baseUrl;
    await context.addInitScript({ content: `try{localStorage.setItem("modl.sessionKey",${JSON.stringify(credentials.sessionKey ?? "")})}catch(e){}` });
    await context.addCookies(Object.entries(credentials.cookies).map(([name, value]) => ({ name, value, domain: new URL(origin).hostname, path: "/" })));
    const page = await context.newPage();
    await openReplay(page, origin, replayId);
    const dialog = page.getByRole("dialog").last();
    const dialogBox = await dialog.boundingBox();
    const slider = dialog.getByRole("slider", { name: localeLookup()("replayViewer.timeline") });
    const timeline = await slider.boundingBox();
    if (dialogBox === null || timeline === null) throw new Error("the replay viewer has no visible dialog or timeline");
    const seekTo = async (fraction: number): Promise<number> => {
      await page.mouse.click(timeline.x + timeline.width * fraction, timeline.y + timeline.height / 2);
      await sleep(500);
      return Number(await slider.getAttribute("aria-valuenow"));
    };
    const duration = Number(await slider.getAttribute("aria-valuemax"));
    const target = duration - PLAY_MS / 1000 - END_MARGIN_SECONDS;
    let fraction = target / duration;
    for (let attempt = 0; attempt < 3; attempt++) {
      const reached = await seekTo(fraction);
      logger.info(`seeked to ${String(reached)} s of ${String(duration)} s (target ${target.toFixed(1)} s)`);
      if (Math.abs(reached - target) <= 1) break;
      fraction *= target / Math.max(1, reached);
    }
    await page.mouse.move(dialogBox.x + dialogBox.width / 2, dialogBox.y - 5);
    await sleep(1500);
    const clockStart = Date.now();
    await page.clock.install({ time: clockStart });
    await page.clock.pauseAt(clockStart + 1000);
    await replayControl(page, "Play").click();
    const frameMs = 1000 / FPS;
    const frames = Math.round(PLAY_MS / frameMs);
    for (let frame = 0; frame < frames; frame++) {
      await page.clock.runFor(frameMs);
      await dialog.screenshot({ path: join(framesDir, `frame-${String(frame).padStart(4, "0")}.png`), animations: "allow", caret: "hide" });
    }
    await context.close();
    return frames;
  } finally {
    await browser.close();
  }
}

async function toGif(framesDir: string, gif: string): Promise<void> {
  const filter = `scale=${String(WIDTH)}:-2:flags=lanczos,split[a][b];[a]palettegen=stats_mode=full[p];[b][p]paletteuse=dither=bayer:bayer_scale=4:diff_mode=rectangle`;
  await runProcess("ffmpeg", ["-y", "-framerate", String(FPS), "-i", join(framesDir, "frame-%04d.png"), "-vf", filter, "-loop", "0", gif], { timeoutMs: 300_000 });
}

async function toWebp(framesDir: string, scaledDir: string, webp: string): Promise<void> {
  mkdirSync(scaledDir, { recursive: true });
  await runProcess("ffmpeg", ["-y", "-i", join(framesDir, "frame-%04d.png"), "-vf", `scale=${String(WIDTH)}:-2:flags=lanczos`, join(scaledDir, "frame-%04d.png")], { timeoutMs: 300_000 });
  const scaled = readdirSync(scaledDir).filter((name) => name.endsWith(".png")).sort().map((name) => join(scaledDir, name));
  await runProcess("img2webp", ["-loop", "0", "-d", String(Math.round(1000 / FPS)), "-lossy", "-q", String(WEBP_QUALITY), "-m", "6", ...scaled, "-o", webp], { timeoutMs: 600_000 });
}

export async function recordReplayAnimations(logger: Logger): Promise<readonly string[]> {
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
    const framesDir = join(workDir, "frames");
    mkdirSync(framesDir, { recursive: true });
    const frames = await captureViewerFrames(environment, replayId, framesDir, logger);
    logger.info(`${String(frames)} frames captured; encoding the GIF and the WebP`);
    const gif = join(workDir, "panel-replay.gif");
    const webp = join(workDir, "panel-replay.webp");
    await toGif(framesDir, gif);
    await toWebp(framesDir, join(workDir, "scaled"), webp);
    for (const file of [gif, webp]) if (!existsSync(file)) throw new Error(`no ${file} was written`);
    return [gif, webp];
  } finally {
    bots?.quitAll();
    await down({ keepData: false }, logger);
  }
}
