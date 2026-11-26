import { join } from "node:path";
import type { Locator, Page } from "playwright-core";
import type { TestBot } from "../../lib/bots/bot";
import { BotPool, entryTarget } from "../../lib/bots/pool";
import { AiEndpoints } from "../../lib/ai-contract";
import { ConsoleCommands, ConsolePatterns, punishmentIssued, REPORT_TICKET_ID } from "../../lib/contract";
import { HttpStatus, jsonField } from "../../lib/http";
import { HARNESS_ROOT, SOURCE_DIR } from "../../lib/paths";
import { runProcess } from "../../lib/process";
import type { Environment } from "../../lib/environment";
import { HARNESS_OWNER_PASSWORD } from "../../lib/sign-in";
import { offlineUuid, sleep } from "../../lib/util";
import type { TeaserContext, TeaserVideo } from "../definition";
import { SetupWizard, type WizardOwner } from "../panel/setup-wizard";
import { ChatRenderer } from "../scenes/chat";
import { consoleLine, pickConsoleLines, type ConsoleLine } from "../scenes/console";
import { legacyTextHtml } from "../scenes/legacy-text";
import { LANDSCAPE, VERTICAL } from "../studio/profile";
import { DirectHands } from "../studio/pointer";
import type { PanelBrowser } from "../studio/studio";
import type { Footage, TeaserEdit } from "../teaser/edit";
import { FootageRoll, type FootageDirector } from "../teaser/footage";
import { MINECRAFT_SCORE } from "../teaser/score";
import { dismissNoticeIfShown, openRenderedPage, openRenderedSettings } from "./panel-navigation";
import { BOOT_LINE, LOOPBACK_PANEL_URL, panelHostBoot, recordEveryPlayer, pluginJars, PLATFORM_START, publishLoopbackPanel, setupCode } from "./stage";
import { captureReplayOf, openReplay, playReplay, replayControl, replayWorld, viewWithoutHud } from "./replay-world";
import { teaserEdit, type BanMoment, type MontageShot, type TeaserMaterial } from "./teaser-v3-edit";
import { VirtualAuthenticator } from "./virtual-authenticator";

const TARGET = "Steve";
const ALT = "Alex";
const CHATTER = "Kai";
const REPORTER = "Zuri";
const SEEDED_ALT_OWNER = "xXSlayerXx";
const BAN_REASON = "Cheating";
const STEP_TIMEOUT_MS = 30_000;
const SEED_TIMEOUT_MS = 300_000;
const AI_CONNECTION = { provider: "openai", apiKey: "sk-example-not-a-real-key-0000000000", model: "gpt-4.1-mini" } as const;
const PANEL_SETTLE_MS = 1200;
const WANDER_MS = 12_000;
const SCROLL_TO_PUNISHMENTS_LIST = `(() => {
  const heading = Array.from(document.querySelectorAll("h3")).find((node) => (node.textContent || "").startsWith("Punishments list"));
  if (!heading) throw new Error("the punishments list is not shown");
  heading.scrollIntoView({ block: "start" });
})()`;
const CHAT_LINES = ["anyone selling iron?", "lol you are all so bad at this", "just uninstall the game"] as const;

function owner(environment: Environment): WizardOwner {
  return { email: environment.instance.ownerEmail, password: HARNESS_OWNER_PASSWORD, serverName: "Example SMP" };
}

async function bootLines(environment: Environment, proxy: string): Promise<ConsoleLine[]> {
  return pickConsoleLines(await environment.log(proxy).lines(), [PLATFORM_START.velocity, ...panelHostBoot(ConsolePatterns.velocityReady)]);
}

async function recordWizard(context: TeaserContext, browser: PanelBrowser, roll: FootageRoll, origin: string): Promise<Footage> {
  const take = await browser.take("wizard");
  let director: FootageDirector | null = null;
  const wizard = new SetupWizard(take.page, new DirectHands(), null, {
    entered: async (screen) => {
      if (screen === "address") {
        director?.omitFromNow();
        return;
      }
      director?.keepFromNow();
      await director?.sampleText();
    },
    finishing: () => {
      director?.omitFromNow();
    },
  });
  await wizard.open(origin);
  const code = await setupCode(context.environment);
  const footage = await roll.live(take, take.page.getByRole("heading", { level: 1 }).first().locator("../.."), async (live) => {
    director = live;
    await wizard.claim(code, owner(context.environment));
    await wizard.complete(owner(context.environment), "every player", LOOPBACK_PANEL_URL);
    await take.page.waitForTimeout(PANEL_SETTLE_MS);
  });
  await take.page.close();
  return footage;
}

async function seedPanel(environment: Environment): Promise<void> {
  const apiKey = await environment.instance.revealApiKey();
  await runProcess("env", [`MODL_SEED_URL=${environment.state.instance.baseUrl}`, `MODL_SEED_API_KEY=${apiKey}`, "npx", "tsx", join(SOURCE_DIR, "seed", "seed.ts")], {
    cwd: HARNESS_ROOT,
    timeoutMs: SEED_TIMEOUT_MS,
  });
}

async function configureAi(environment: Environment): Promise<void> {
  const instance = environment.instance;
  const saved = await instance.api(AiEndpoints.connection(AI_CONNECTION.provider), { method: "PUT", json: { apiKey: AI_CONNECTION.apiKey, model: AI_CONNECTION.model, activate: true } });
  if (saved.status !== HttpStatus.ok) throw new Error(`saving the AI connection answered ${String(saved.status)}: ${saved.text}`);
  const moderation = await instance.api(AiEndpoints.moderation);
  const enabled = await instance.api(AiEndpoints.moderation, { method: "PATCH", json: { enableAIReview: true, expectedVersion: jsonField(moderation.json, "version") ?? 0 } });
  if (enabled.status !== HttpStatus.ok) throw new Error(`turning on AI review answered ${String(enabled.status)}: ${enabled.text}`);
}

async function fileChatReport(chatter: TestBot, reporter: TestBot): Promise<string> {
  for (const line of CHAT_LINES) {
    chatter.chat(line);
    await sleep(900);
  }
  const since = reporter.lastSeq;
  await reporter.runCommand(`/chatreport ${chatter.username}`);
  const message = await reporter.awaitMessage(REPORT_TICKET_ID, STEP_TIMEOUT_MS, since);
  const id = REPORT_TICKET_ID.exec(message.text)?.[1];
  if (id === undefined) throw new Error(`no ticket id in "${message.text}"`);
  return id;
}

async function worldFootage(browser: PanelBrowser, roll: FootageRoll, origin: string, replayId: string): Promise<Footage> {
  const page = await browser.page();
  await openReplay(page, origin, replayId);
  await playReplay(page);
  await viewWithoutHud(page);
  const footage = await roll.element("world", replayWorld(page));
  await page.close();
  return footage;
}

async function recordBan(context: TeaserContext, browser: PanelBrowser, roll: FootageRoll, origin: string, target: TestBot, version: string): Promise<BanMoment> {
  const environment = context.environment;
  const proxy = environment.state.coreServer;
  await target.wander(WANDER_MS);
  const world = await worldFootage(browser, roll, origin, await captureReplayOf(environment, proxy, target.username));
  const renderer = new ChatRenderer(version);
  target.chat("anyone up for a fight?");
  await sleep(1500);
  const chat = target.recentMessages(3).map((message) => renderer.html(message.json));
  const take = await browser.take("ban-dashboard");
  await openRenderedPage(take.page, `${origin}/panel`, take.page.getByText("Recent punishments").first());
  await dismissNoticeIfShown(take.page);
  const command = ConsoleCommands.ban(target.username, BAN_REASON);
  let issuedAt = 0;
  let output: ConsoleLine[] = [];
  const panel = await roll.live(take, take.page.getByText("Recent punishments").first(), async () => {
    await sleep(1500);
    const disconnected = target.awaitDisconnect(STEP_TIMEOUT_MS);
    const started = Date.now();
    const result = await environment.console(proxy).run(command, { expect: punishmentIssued("ban", target.username), timeoutMs: STEP_TIMEOUT_MS });
    issuedAt = (Date.now() - started) / 1000 + 1.5;
    output = result.lines.map((line) => consoleLine(line, { pattern: /./ }));
    await disconnected;
    await take.page.getByText(target.username, { exact: true }).first().waitFor({ timeout: STEP_TIMEOUT_MS });
    await sleep(2500);
  });
  await take.page.close();
  const kick = target.status().kickReasonStyled ?? "";
  return { world, command, output, chat, kick: legacyTextHtml(kick), panel, panelArrivalAt: issuedAt };
}

interface Shooter {
  readonly page: Page;
  readonly origin: string;
  readonly roll: FootageRoll;
}

async function still(shooter: Shooter, name: string, path: string, ready: Locator, focus: Locator | null): Promise<Footage> {
  await openRenderedPage(shooter.page, `${shooter.origin}${path}`, ready);
  return shooter.roll.still(shooter.page, name, focus);
}

async function settingsStill(shooter: Shooter, name: string, slug: string, heading: string, focus: Locator | null): Promise<Footage> {
  await openRenderedSettings(shooter.page, shooter.origin, slug, heading);
  return shooter.roll.still(shooter.page, name, focus);
}

async function auditStill(shooter: Shooter): Promise<Footage> {
  const page = shooter.page;
  await openRenderedPage(page, `${shooter.origin}/panel/audit`, page.getByRole("heading", { name: "Analytics & audit" }));
  await page.getByRole("tab", { name: "Punishments", exact: true }).click();
  const list = page.getByRole("heading", { name: /^Punishments list/ });
  await list.waitFor({ timeout: STEP_TIMEOUT_MS });
  const section = list.locator("../..");
  await section.getByRole("row").nth(1).waitFor({ timeout: STEP_TIMEOUT_MS });
  await page.evaluate(SCROLL_TO_PUNISHMENTS_LIST);
  await page.waitForTimeout(PANEL_SETTLE_MS);
  return shooter.roll.still(page, "audit", section);
}

async function replayFootage(browser: PanelBrowser, roll: FootageRoll, origin: string, replayId: string): Promise<Footage> {
  const take = await browser.take("replay");
  const page = take.page;
  await openReplay(page, origin, replayId);
  const footage = await roll.live(take, replayWorld(page), async () => {
    await replayControl(page, "Play").click();
    await sleep(2200);
    await replayControl(page, "Follow camera").click();
    await page.mouse.move(640, 300);
    await sleep(2600);
  });
  await page.close();
  return { ...footage, wholeFrame: true };
}

async function themeFootage(browser: PanelBrowser, roll: FootageRoll, origin: string): Promise<Footage> {
  const take = await browser.take("theme-switch");
  const page = take.page;
  const toggle = page.getByRole("button", { name: "Toggle dark mode", exact: true });
  await openRenderedPage(page, `${origin}/panel`, toggle);
  const footage = await roll.live(take, null, async () => {
    await sleep(900);
    await toggle.click();
    await sleep(1800);
  });
  await toggle.click();
  await page.close();
  return footage;
}

async function passkeyFootage(browser: PanelBrowser, roll: FootageRoll, origin: string, password: string): Promise<Footage> {
  const take = await browser.take("passkey");
  const page = take.page;
  const authenticator = await VirtualAuthenticator.attach(page);
  await openRenderedSettings(page, origin, "security", "Security");
  await page.getByRole("button", { name: "Add passkey", exact: true }).click();
  const naming = page.getByRole("dialog").filter({ hasText: "Name your passkey" });
  const proof = page.getByRole("dialog").filter({ hasText: "Confirm it is you" });
  await Promise.race([naming.waitFor({ timeout: STEP_TIMEOUT_MS }), proof.waitFor({ timeout: STEP_TIMEOUT_MS })]);
  if (await proof.isVisible()) {
    await proof.getByRole("textbox", { name: "Current password" }).fill(password);
    await proof.getByRole("button", { name: "Continue", exact: true }).click();
    await naming.waitFor({ timeout: STEP_TIMEOUT_MS });
  }
  await naming.getByRole("button", { name: "Save", exact: true }).click();
  await naming.waitFor({ state: "detached", timeout: STEP_TIMEOUT_MS });
  await authenticator.remember();
  await page.context().clearCookies();
  await page.goto(`${origin}/panel/auth`);
  await authenticator.restore(page);
  const passkey = page.getByRole("button", { name: "Sign in with a passkey", exact: true });
  await passkey.waitFor({ timeout: STEP_TIMEOUT_MS });
  const footage = await roll.live(take, passkey, async () => {
    await sleep(700);
    await passkey.click();
    await page.getByRole("heading", { name: "Dashboard" }).waitFor({ timeout: STEP_TIMEOUT_MS });
    await sleep(1500);
  });
  await page.close();
  return footage;
}

interface MontageStudio {
  readonly shooter: Shooter;
  readonly browser: PanelBrowser;
  readonly ids: { readonly ticket: string; readonly replay: string };
  readonly password: string;
}

interface MontageSpec {
  readonly caption: string;
  readonly beats: number;
  readonly pushIn: boolean;
  capture(studio: MontageStudio): Promise<Footage>;
}

function memberRow(page: Page, name: string): Locator {
  return page.getByRole("listitem").filter({ has: page.getByText(name, { exact: true }) }).filter({ has: page.getByText(/^\[?Connected\]?$/) });
}

const MONTAGE: readonly MontageSpec[] = [
  {
    caption: "Player profiles",
    beats: 2,
    pushIn: true,
    capture: async ({ shooter }) => still(shooter, "profile", `/panel/player/${offlineUuid(TARGET)}`, shooter.page.getByRole("heading", { level: 1, name: TARGET }), shooter.page.getByText(BAN_REASON).first().locator("../..")),
  },
  {
    caption: "Alt accounts",
    beats: 2,
    pushIn: true,
    capture: async ({ shooter }) => {
      const page = shooter.page;
      await openRenderedPage(page, `${shooter.origin}/panel/lookup`, page.getByPlaceholder("Search by username..."));
      await page.getByPlaceholder("Search by username...").fill(SEEDED_ALT_OWNER);
      await page.getByText(SEEDED_ALT_OWNER, { exact: true }).first().click();
      await page.getByRole("heading", { level: 1, name: SEEDED_ALT_OWNER }).waitFor({ timeout: STEP_TIMEOUT_MS });
      await page.getByRole("tab", { name: "Linked", exact: true }).click();
      await page.getByRole("tabpanel").first().waitFor({ timeout: STEP_TIMEOUT_MS });
      await page.waitForTimeout(PANEL_SETTLE_MS * 2);
      return shooter.roll.still(page, "linked", page.getByRole("tabpanel").first());
    },
  },
  { caption: "Replays", beats: 4, pushIn: false, capture: async ({ browser, shooter, ids }) => replayFootage(browser, shooter.roll, shooter.origin, ids.replay) },
  {
    caption: "Chat reports",
    beats: 2,
    pushIn: true,
    capture: async ({ shooter, ids }) => {
      const log = shooter.page.getByText(/^Chat log \(/).first();
      return still(shooter, "chat-log", `/panel/tickets/${ids.ticket.replace(/^#/, "ID-")}`, log, log.locator("../.."));
    },
  },
  { caption: "Tickets", beats: 2, pushIn: true, capture: async ({ shooter }) => still(shooter, "tickets", "/panel/tickets", shooter.page.getByRole("heading", { name: "Tickets" }), shooter.page.getByRole("table")) },
  { caption: "Audit log", beats: 2, pushIn: true, capture: async ({ shooter }) => auditStill(shooter) },
  { caption: "AI moderation", beats: 2, pushIn: true, capture: async ({ shooter }) => settingsStill(shooter, "ai", "ai-moderation", "AI moderation", shooter.page.getByRole("region", { name: "AI provider" })) },
  { caption: "Light and dark", beats: 4, pushIn: true, capture: async ({ browser, shooter }) => themeFootage(browser, shooter.roll, shooter.origin) },
  {
    caption: "Minecraft servers",
    beats: 2,
    pushIn: true,
    capture: async ({ shooter }) => {
      await openRenderedSettings(shooter.page, shooter.origin, "minecraft-servers", "Minecraft servers");
      const row = memberRow(shooter.page, "survival");
      await row.waitFor({ timeout: STEP_TIMEOUT_MS });
      return shooter.roll.still(shooter.page, "servers", row);
    },
  },
  { caption: "Passkeys", beats: 4, pushIn: true, capture: async ({ browser, shooter, password }) => passkeyFootage(browser, shooter.roll, shooter.origin, password) },
  { caption: "Public site", beats: 2, pushIn: true, capture: async ({ shooter }) => still(shooter, "homepage", "/", shooter.page.locator('[data-modl="hero"]'), shooter.page.locator('[data-modl-page="home"]')) },
  { caption: "Knowledgebase", beats: 2, pushIn: true, capture: async ({ shooter }) => still(shooter, "knowledgebase", "/knowledgebase", shooter.page.locator('[data-modl="page"]'), shooter.page.locator("main").first()) },
  { caption: "Roles", beats: 2, pushIn: true, capture: async ({ shooter }) => settingsStill(shooter, "roles", "roles", "Roles", shooter.page.locator("main")) },
];

async function montage(context: TeaserContext, browser: PanelBrowser, roll: FootageRoll, origin: string, ids: { ticket: string; replay: string }): Promise<MontageShot[]> {
  const page = await browser.page();
  const studio: MontageStudio = { shooter: { page, origin, roll }, browser, ids, password: owner(context.environment).password };
  const shots: MontageShot[] = [];
  for (const spec of MONTAGE) shots.push({ caption: spec.caption, beats: spec.beats, pushIn: spec.pushIn, speed: 1, footage: await spec.capture(studio) });
  await page.close();
  return shots;
}

async function record(context: TeaserContext): Promise<TeaserEdit> {
  const environment = context.environment;
  const proxy = environment.state.coreServer;
  const roll = new FootageRoll(context.workDir);
  const boot = await bootLines(environment, proxy);
  const jars = pluginJars(environment.server(proxy).dataDir);
  const origin = publishLoopbackPanel(context);
  const browser = await context.studio.panelBrowser();
  const wizard = await recordWizard(context, browser, roll, origin);
  await environment.connectServers();
  await environment.instance.ensureOwnerSession();
  await seedPanel(environment);
  await configureAi(environment);
  for (const member of environment.serverNames().filter((name) => environment.server(name).role === "member")) await recordEveryPlayer(environment, member);
  const target = entryTarget(environment.state);
  const bots = new BotPool(() => target, context.logger);
  try {
    const steve = await bots.join(TARGET);
    await bots.join(ALT);
    const kai = await bots.join(CHATTER);
    const zuri = await bots.join(REPORTER);
    const ticket = await fileChatReport(kai, zuri);
    await kai.wander(WANDER_MS);
    const replay = await captureReplayOf(environment, proxy, kai.username);
    const ban = await recordBan(context, browser, roll, origin, steve, target.version);
    const shots = await montage(context, browser, roll, origin, { ticket, replay });
    const material: TeaserMaterial = { proxy, jars, boot, marked: [BOOT_LINE.setupCode, BOOT_LINE.panelListening], wizard, ban, montage: shots };
    return teaserEdit(context.clock, material);
  } finally {
    bots.quitAll();
  }
}

export const teaserV3: TeaserVideo = {
  id: "teaser-v3",
  title: "modl v3",
  topology: "network",
  presentation: "teaser",
  score: MINECRAFT_SCORE,
  cuts: [LANDSCAPE, VERTICAL],
  record,
};
