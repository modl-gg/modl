import { JoinRefusedError, type TestBot, type WindowSlot } from "../lib/bots/bot";
import {
  ConsoleCommands,
  ConsolePatterns,
  Endpoints,
  PlayerCommands,
  RECORD_EVERY_PLAYER,
  REPLAY_CAPTURE_FAILED,
  replayCaptured,
} from "../lib/contract";
import { HttpStatus, jsonArray, jsonField, jsonString } from "../lib/http";
import type { Profile, SessionOutcome } from "../lib/instance";
import { lineOf } from "../lib/lines";
import { listProxiedServers, type ListedProxiedServer, type ProxiedServerState } from "../lib/server-keys";
import { settings } from "../lib/settings";
import { normalizeUuid, offlineUuid, sleep } from "../lib/util";
import { parseReplay, printable, type ParsedReplay } from "../lib/replay-file";
import type { UpOptions } from "../lib/state";
import type { ScenarioContext } from "./framework";

const NO_RELOAD = "Folia has no /reload (Bukkit.reload throws UnsupportedOperationException), so modl only restarts with the server";

export function reloadUnsupported(options: Pick<UpOptions, "line">): string | null {
  return lineOf(options).paper.reloadable ? null : NO_RELOAD;
}

export function serverReloads(t: ScenarioContext): boolean {
  if (reloadUnsupported(t.env.state.options) === null) return true;
  t.note(NO_RELOAD);
  return false;
}

export async function ownerSession(t: ScenarioContext): Promise<SessionOutcome> {
  const outcome = await t.instance.ensureOwnerSession();
  t.evidence("owner session", { path: outcome.path, email: outcome.profile.email, role: outcome.profile.role });
  t.check("owner session belongs to the owner email", outcome.profile.email.toLowerCase() === t.instance.ownerEmail.toLowerCase(), outcome.profile);
  return outcome;
}

export function isLinkedTo(profile: Profile | null, player: string): boolean {
  return profile !== null && normalizeUuid(profile.minecraftUuid) === normalizeUuid(offlineUuid(player));
}

export async function linkOwnerAccount(t: ScenarioContext, bot: TestBot): Promise<Profile> {
  const link = await t.instance.createMinecraftLinkCode();
  t.evidence("link code", { command: link.command, expiresInSeconds: link.expiresInSeconds });
  const replies = await bot.runCommand(PlayerCommands.link(link.code), { timeoutMs: 8000 });
  t.evidence("/modl link replies", replies.map((message) => message.text));
  const profile = await t.instance.profile();
  t.require(profile !== null && isLinkedTo(profile, bot.username), `the owner profile is not linked to ${bot.username} after /modl link (profile: ${JSON.stringify(profile)})`);
  return profile;
}

export async function linkedOwnerBot(t: ScenarioContext): Promise<TestBot> {
  await ownerSession(t);
  const name = settings().ownerPlayer;
  const bot = await t.bot(name);
  if (isLinkedTo(await t.instance.profile(), name)) return bot;
  await linkOwnerAccount(t, bot);
  t.quitBot(name);
  await sleep(2500);
  return t.bot(name);
}

export async function expectJoinRefused(t: ScenarioContext, username: string, timeoutMs = 30_000): Promise<string> {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const bot = t.newBot(username);
    try {
      await bot.join();
      bot.quit();
      await sleep(2000);
    } catch (error) {
      if (error instanceof JoinRefusedError) return error.reason;
      throw error;
    }
  }
  return t.fail(`${username} was still allowed to join after ${timeoutMs}ms`);
}

export interface CapturedReplay {
  id: string;
  replayUrl: string;
  fileSize: number;
  file: Buffer;
  replay: ParsedReplay;
}

export async function captureReplay(t: ScenarioContext, bot: TestBot, options: { wanderMs?: number } = {}): Promise<CapturedReplay> {
  await bot.wander(options.wanderMs ?? 10_000);
  return captureReplayOf(t, bot.username);
}

export async function captureReplayOf(t: ScenarioContext, player: string): Promise<CapturedReplay> {
  const outcome = new RegExp(`${replayCaptured(player).source}|${REPLAY_CAPTURE_FAILED.source}`, "i");
  const result = await t.consoleExpect(ConsoleCommands.replayCapture(player), outcome, { timeoutMs: 90_000 });
  t.require(replayCaptured(player).test(result.matched ?? ""), `replay capture failed: ${String(result.matched)}`);
  const linkLine = await t.env.log(t.coreServer).waitFor(ConsolePatterns.replayLink, { since: result.since, timeoutMs: 15_000 });
  const id = ConsolePatterns.replayLink.exec(linkLine.text)?.[1];
  t.require(id, `no replay id in ${linkLine.text}`);
  const anonymous = await t.instance.anonymousSession().request(Endpoints.panelReplay(id), { auth: "none" });
  t.check("replay metadata needs a staff session", anonymous.status === HttpStatus.unauthorized, { status: anonymous.status });
  const metadata = await t.instance.apiWithCredentials(Endpoints.panelReplay(id));
  t.evidence("panel replay metadata", { status: metadata.status, body: metadata.json });
  t.require(metadata.status === HttpStatus.ok, `GET ${Endpoints.panelReplay(id)} returned ${String(metadata.status)}`);
  const replayUrl = jsonString(metadata.json, "replayUrl");
  t.require(replayUrl, "replay metadata has no replayUrl");
  t.check("the replay file is served by the panel route", replayUrl === Endpoints.panelReplayFile(id), replayUrl);
  const anonymousFile = await t.instance.anonymousSession().bytes(replayUrl);
  t.check("the replay file needs a staff session", anonymousFile.status === HttpStatus.unauthorized, { status: anonymousFile.status });
  const download = await t.instance.bytesWithCredentials(replayUrl);
  t.require(download.status === HttpStatus.ok && download.body.length > 0, `the replay file answered ${String(download.status)}`);
  t.check("the replay file is never cached", download.cacheControl.includes("no-store"), download.cacheControl);
  const replay = parseReplay(download.body);
  t.evidence("replay file", {
    url: replayUrl,
    bytes: download.body.length,
    version: replay.header.minecraftVersion,
    dimension: printable(replay.header.dimension),
    durationMs: replay.header.durationMs,
    sections: replay.sections.size,
    palette: replay.palette.length,
    keyframes: replay.keyframes.length,
    events: replay.events.length,
  });
  t.check("replay file is a complete version 5 replay", replay.end !== null, { frames: replay.frameCount });
  return { id, replayUrl, fileSize: Number(jsonField(metadata.json, "fileSize") ?? 0), file: download.body, replay };
}

const SETTINGS_APPLY_TIMEOUT_MS = 30_000;

export async function recordEveryPlayer(t: ScenarioContext, server: string): Promise<void> {
  await t.instance.updateReplaySettings(RECORD_EVERY_PLAYER);
  const features = await awaitFeatures(t, server, (reported) => jsonField(reported, "replaysActive") === true);
  t.require(jsonField(features, "replaysActive") === true, `${server} did not report replays active: ${JSON.stringify(features)}`);
}

export async function awaitFeatures(t: ScenarioContext, server: string, wanted: (features: unknown) => boolean): Promise<unknown> {
  const deadline = Date.now() + SETTINGS_APPLY_TIMEOUT_MS;
  let features: unknown = null;
  while (Date.now() < deadline) {
    const result = await t.instance.apiWithCredentials(Endpoints.connectedServers);
    t.require(result.status === HttpStatus.ok, `the connected servers list answered ${String(result.status)}`);
    const entry = jsonArray(result.json, "servers").find((candidate) => jsonString(candidate, "name")?.toLowerCase() === server.toLowerCase()
      && jsonField(candidate, "features") !== undefined);
    features = entry === undefined ? null : jsonField(entry, "features");
    if (features !== null && wanted(features)) return features;
    await t.sleep(1000);
  }
  return features;
}

export async function proxyLocation(t: ScenarioContext, player: string): Promise<string | null> {
  const result = await t.env.console("proxy").run(ConsoleCommands.velocityPlayerList, { settleMs: 1200, timeoutMs: 6000 });
  for (const line of result.lines) {
    const match = /\[([\w-]+)\] \(\d+\): (.*)$/.exec(line);
    if (match?.[2]?.split(/,\s*/).includes(player)) return match[1] ?? null;
  }
  return null;
}

export interface HubMember {
  name: string;
  keyName: string | null;
  hubConnected: boolean;
}

export async function hubMembers(t: ScenarioContext): Promise<HubMember[]> {
  const result = await t.instance.apiWithCredentials(Endpoints.connectedServers);
  t.require(result.status === HttpStatus.ok, `the connected servers list answered ${result.status}: ${result.text}`);
  return jsonArray(result.json, "servers")
    .filter((server) => jsonString(server, "role") === "SERVER_ROLE_MEMBER")
    .map((server) => ({
      name: jsonString(server, "name") ?? "",
      keyName: jsonString(server, "keyName"),
      hubConnected: jsonField(server, "hubConnected") === true,
    }));
}

export async function awaitMembersOnHub(t: ScenarioContext, members: readonly string[], timeoutMs = 90_000): Promise<HubMember[]> {
  const deadline = Date.now() + timeoutMs;
  let linked: HubMember[] = [];
  while (Date.now() < deadline) {
    linked = (await hubMembers(t)).filter((member) => member.hubConnected);
    if (members.every((name) => linked.some((member) => member.name === name || member.keyName === name))) return linked;
    await sleep(1000);
  }
  return t.fail(`servers ${members.join(", ")} are not all linked to the network hub (linked: ${linked.map((member) => member.name).join(", ") || "none"})`);
}

export async function awaitProxiedServer(t: ScenarioContext, name: string, state: ProxiedServerState, timeoutMs = 60_000): Promise<ListedProxiedServer> {
  const deadline = Date.now() + timeoutMs;
  let listed: ListedProxiedServer[] = [];
  while (Date.now() < deadline) {
    listed = await listProxiedServers(t.instance);
    const match = listed.find((server) => server.name === name && server.state === state);
    if (match) return match;
    await sleep(1000);
  }
  t.evidence("proxy servers", listed);
  return t.fail(`the panel does not list the proxy server ${name} as ${state}`);
}

export function memberNames(t: ScenarioContext): string[] {
  return t.env.serverNames().filter((name) => t.env.server(name).role === "member");
}

export async function awaitHotbarSlot(t: ScenarioContext, bot: TestBot, pattern: RegExp, timeoutMs = 15_000): Promise<WindowSlot> {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const slot = bot.findHotbarSlot(pattern);
    if (slot) return slot;
    await sleep(250);
  }
  t.evidence(`${bot.username} hotbar`, { hotbar: bot.hotbar().map((slot) => slot.customName ?? slot.displayName), openWindow: bot.readWindow()?.title ?? null });
  return t.fail(`${bot.username} has no hotbar item matching ${pattern.toString()} after ${timeoutMs}ms`);
}

export async function awaitVisibility(bot: TestBot, other: string, visible: boolean, timeoutMs = 10_000): Promise<boolean> {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (bot.canSee(other) === visible) return true;
    await sleep(250);
  }
  return bot.canSee(other) === visible;
}

export interface MovementProbe {
  moved: number;
  from: { x: number; y: number; z: number } | null;
  to: { x: number; y: number; z: number } | null;
}

export async function probeMovement(bot: TestBot, walkMs = 2000): Promise<MovementProbe> {
  await sleep(500);
  const from = bot.status().position;
  await bot.wander(walkMs);
  await sleep(500);
  const to = bot.status().position;
  const moved = from && to ? Math.hypot(to.x - from.x, to.z - from.z) : Number.POSITIVE_INFINITY;
  return { moved, from, to };
}

const REPLAY_STEP_MARKER = /Capture Replay/i;
const REPLAY_STEP_SKIP = /^\s*Skip\s*$/i;

export async function passOptionalReplayStep(bot: TestBot): Promise<void> {
  if (bot.findSlot(REPLAY_STEP_MARKER) === null) {
    return;
  }
  const skip = bot.findSlot(REPLAY_STEP_SKIP);
  if (skip === null) {
    throw new Error("the replay step has no Skip button");
  }
  await bot.clickAndAwaitWindow(skip.slot);
}
