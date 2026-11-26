import { existsSync, mkdirSync, readdirSync, statSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { ReplayEquipmentSlot, type ReplayEntitySnapshot } from "@proto/modl/v1/replay_format_pb";
import type { TestBot } from "../lib/bots/bot";
import {
  ConsoleCommands,
  DataDirectories,
  Endpoints,
  PlayerCommands,
  REPLAY_FILE_MAGIC,
  ReplayErrors,
  ReplayRecordingMessages,
  ReplayShareExpiries,
  RECORD_EVERY_PLAYER,
  SETUP_REPLAY_CHOICE,
} from "../lib/contract";
import { apiErrorCode, HttpStatus, jsonArray, jsonField, jsonString } from "../lib/http";
import { REPORTS_DIR } from "../lib/paths";
import { blockAtEnd, printable, stringAt, type ParsedReplay } from "../lib/replay-file";
import { offlineUuid } from "../lib/util";
import { defineScenario, type ScenarioContext } from "./framework";
import { replayScene, type ReplayScene } from "./replay-scene";
import { awaitFeatures, captureReplayOf, memberNames, proxyLocation, recordEveryPlayer, type CapturedReplay } from "./support";

const CHAT_LINE = "replay harness hello";
const SHARE_VIEW_FLUSH_TIMEOUT_MS = 90_000;
const RCON_ATTEMPTS = 3;
const PLACED = { x: 20, z: 2 };
const SCENE_ENTITY_TYPES = ["minecraft:zombie", "minecraft:creeper", "minecraft:pig", "minecraft:sheep", "minecraft:item", "minecraft:player"];
const ON_DEMAND_MEMBER = "survival";
const ON_DEMAND_TOPOLOGIES: readonly string[] = ["standalone", "network"];
const RECORDING_REPLY_TIMEOUT_MS = 20_000;
const HUB_ROUTE_ATTEMPTS = 10;
const ROUTE_NOT_READY = "is not connected to modl.";
const ON_DEMAND_ONLY = { ...RECORD_EVERY_PLAYER, recordEveryPlayer: false, recordOnAnticheatFlag: false };
const NOT_AVAILABLE_TEXT = "Replay recording is not available";
const NOT_AVAILABLE = new RegExp(NOT_AVAILABLE_TEXT);

export const replay = defineScenario({
  name: "replay",
  description: "a recorded player sees the reference scene, another player, mobs, items, block changes and chat; the captured file holds them with names and properties; a share link opens it logged out until it is revoked and no storage URL serves it; on standalone and network the panel switches replays off and on live, the recording is started on demand with /replay record (auto-record and record-on-flag off) and ended with /replay stop",
  topologies: ["standalone", "network", "network-bungee", "remote"],
  async run(t: ScenarioContext) {
    const onDemand = ON_DEMAND_TOPOLOGIES.includes(t.topology);
    const server = onDemand ? onDemandServer(t) : sceneServer(t);
    await t.step(`the panel turns replays on for every player and ${server} reports them active`, async () => {
      await recordEveryPlayer(t, server);
    }, { timeoutMs: 60_000 });
    try {
      await recordScene(t, server, onDemand);
    } finally {
      await t.step("replays are off again as the setup wizard left them", async () => {
        await t.instance.updateReplaySettings({ ...RECORD_EVERY_PLAYER, ...SETUP_REPLAY_CHOICE });
      });
    }
  },
});

async function recordScene(t: ScenarioContext, server: string, onDemand: boolean): Promise<void> {
  const scene = replayScene(t.env.state.options.line);
  const rcon = async (command: string): Promise<string> => rconWithRetry(t, server, command);
  const target = t.playerName("Rec");
  const walker = t.playerName("Walk");

  await t.step("the scene player joins and the reference scene is built", async () => {
    const armored = await joinOn(t, "Armored", server);
    await rcon(`tp Armored 7.5 ${String(scene.baseY + 1)} 25.5`);
    await t.sleep(3000);
    for (const command of scene.commands) await rcon(command);
    for (const command of scene.playerCommands) await rcon(command);
    for (const item of scene.outfit) await rcon(`give Armored ${item}`);
    await armored.wear(scene.outfit);
  }, { timeoutMs: 180_000 });

  await t.step("the recorded player joins and looks at the scene", async () => {
    await joinOn(t, target, server);
    await t.sleep(3000);
    await rcon(`tp ${target} 7.5 ${String(scene.baseY)} 34.5 180 20`);
    await rcon(`give ${target} diamond 3`);
    await t.sleep(3000);
  });

  if (onDemand) {
    await t.step("the panel switches replays off and back on for on-demand recording without a restart", async () => {
      await t.instance.updateReplaySettings({ ...ON_DEMAND_ONLY, enabled: false });
      const refused = await awaitRecordingAnswer(t, ConsoleCommands.replayRecord(target), NOT_AVAILABLE);
      t.check("with replays off a recording is refused", refused.includes(NOT_AVAILABLE_TEXT), refused);
      const off = await awaitFeatures(t, server, (features) => jsonField(features, "replaysActive") !== true);
      t.check("the server reports replays inactive", jsonField(off, "replaysActive") !== true, off);
      await t.instance.updateReplaySettings(ON_DEMAND_ONLY);
      const on = await awaitFeatures(t, server, (features) => jsonField(features, "replaysActive") === true);
      t.check("the server reports replays active and supported", jsonField(on, "replaysSupported") === true, on);
      t.check("the server lists no anticheat hook while the integration is off", jsonField(on, "anticheatIntegrationActive") !== true, on);
    }, { timeoutMs: 120_000 });

    await t.step("staff start recording the player from the console and the status names the request", async () => {
      const before = await recordingCommand(t, ConsoleCommands.replayStatus(target), ReplayRecordingMessages.notRecording(target));
      t.check("nothing records the player before the request", ReplayRecordingMessages.notRecording(target).test(before), before);
      const started = await recordingCommand(t, ConsoleCommands.replayRecord(target), ReplayRecordingMessages.started(target));
      t.check("the console confirms the recording", ReplayRecordingMessages.started(target).test(started), started);
      const status = await t.consoleExpect(ConsoleCommands.replayStatus(target), ReplayRecordingMessages.memory,
        { timeoutMs: RECORDING_REPLY_TIMEOUT_MS });
      const recording = status.lines.map((line) => ReplayRecordingMessages.recording(target).exec(line)).find((match) => match !== null);
      t.check("the status shows the player recorded on request", recording?.[1] === ReplayRecordingMessages.staffOrigin, status.lines);
      t.check("the status shows the replay memory in use", status.matched !== null, status.matched);
    }, { timeoutMs: 120_000 });
  }

  await t.step("another player walks into view, chats, and the world changes", async () => {
    const second = await joinOn(t, walker, server);
    await rcon(`tp ${walker} 14.5 ${String(scene.baseY)} 32.5 180 0`);
    await t.sleep(1500);
    second.chat(CHAT_LINE);
    await second.wander(3000);
    await rcon(scene.legacy
      ? `summon Zombie 30.5 ${String(scene.baseY)} 20.5 {NoAI:1,PersistenceRequired:1}`
      : `summon zombie 30.5 ${String(scene.baseY)} 20.5 {NoAI:1b,PersistenceRequired:1b}`);
    await rcon(scene.item(18.5, scene.baseY, 30.5, "emerald"));
    await rcon(`setblock ${String(PLACED.x)} ${String(scene.baseY)} ${String(PLACED.z)} stone`);
    await t.sleep(1000);
    await rcon(`setblock ${String(PLACED.x)} ${String(scene.baseY)} ${String(PLACED.z)} air`);
    await t.sleep(2500);
  });

  const captured = await t.step("the replay is captured and holds the scene", async () => {
    const result = await captureReplayOf(t, target);
    keepFixture(t, result, "overworld");
    assertScene(t, scene, result.replay, target, walker);
    return result;
  }, { timeoutMs: 180_000 });
  t.evidence("overworld replay", { id: captured.id, bytes: captured.file.length });

  await t.step("a share link opens the replay logged out until it is revoked", async () => {
    await checkShareLink(t, captured, target);
  }, { timeoutMs: 150_000 });

  if (ON_DEMAND_TOPOLOGIES.includes(t.topology)) {
    await t.step("no storage URL serves a replay file", async () => {
      const key = newestStoredReplayKey(t);
      t.require(key !== null, "no stored replay file was found on the panel host");
      const result = await t.instance.anonymousSession().bytes(Endpoints.storageObject(key));
      t.check("the storage URL of the replay answers 404", result.status === HttpStatus.notFound, { key, status: result.status });
    });
  }

  if (!scene.legacy) {
    await t.step("a nether run is recorded with the nether height", async () => {
      await rcon(`gamemode spectator ${target}`);
      await rcon(`execute in minecraft:the_nether run tp ${target} 0.5 100 0.5`);
      await t.sleep(8000);
      const result = await captureReplayOf(t, target);
      keepFixture(t, result, "nether");
      const dimensions = [result.replay.header.dimension, ...result.replay.events.flatMap(({ event }) =>
        event.event.case === "dimensionChange" ? [event.event.value.dimension] : [])];
      const nether = dimensions.find((dimension) => dimension?.key === "minecraft:the_nether");
      t.check("the nether is recorded as a world of its own", nether !== undefined, printable(dimensions));
      t.check("the nether starts at y 0 and is 256 blocks high", nether?.minY === 0 && nether.height === 256, printable(nether));
    }, { timeoutMs: 180_000 });
  }

  if (onDemand) {
    await t.step("staff stop the recording they started and the player is no longer recorded", async () => {
      const stopped = await recordingCommand(t, ConsoleCommands.replayStop(target), ReplayRecordingMessages.stopped(target));
      t.check("the console confirms the recording ended", ReplayRecordingMessages.stopped(target).test(stopped), stopped);
      const after = await recordingCommand(t, ConsoleCommands.replayStatus(target), ReplayRecordingMessages.notRecording(target));
      t.check("the status shows the player is no longer recorded", ReplayRecordingMessages.notRecording(target).test(after), after);
    }, { timeoutMs: 120_000 });
  }
}

async function recordingCommand(t: ScenarioContext, command: string, success: RegExp): Promise<string> {
  for (let attempt = 1; ; attempt++) {
    const result = await t.consoleExpect(command, ReplayRecordingMessages.answer, { timeoutMs: RECORDING_REPLY_TIMEOUT_MS });
    const line = result.matched ?? "";
    if (success.test(line) || !line.includes(ROUTE_NOT_READY) || attempt >= HUB_ROUTE_ATTEMPTS) return line;
    await t.sleep(1000);
  }
}


async function awaitRecordingAnswer(t: ScenarioContext, command: string, expected: RegExp): Promise<string> {
  const deadline = Date.now() + RECORDING_REPLY_TIMEOUT_MS;
  let line = "";
  while (Date.now() < deadline) {
    line = await recordingCommand(t, command, expected);
    if (expected.test(line)) return line;
    await t.sleep(1000);
  }
  return line;
}

async function awaitShareViews(t: ScenarioContext, replayId: string, shareId: string): Promise<number> {
  const deadline = Date.now() + SHARE_VIEW_FLUSH_TIMEOUT_MS;
  let views = 0;
  while (Date.now() < deadline) {
    const listed = await t.instance.apiWithCredentials(Endpoints.replayShares(replayId));
    const share = jsonArray(listed.json, "shares").find((candidate) => jsonString(candidate, "id") === shareId);
    views = Number(jsonField(share, "viewCount") ?? 0);
    if (views > 0) return views;
    await t.sleep(2000);
  }
  return views;
}

async function checkShareLink(t: ScenarioContext, captured: CapturedReplay, target: string): Promise<void> {
  const created = await t.instance.apiWithCredentials(Endpoints.replayShares(captured.id),
    { method: "POST", json: { expiry: ReplayShareExpiries.sevenDays } });
  t.require(created.status === HttpStatus.ok, `creating a share link answered ${String(created.status)}: ${created.text}`);
  const token = jsonString(created.json, "token");
  const shareId = jsonString(jsonField(created.json, "share"), "id");
  t.require(token !== null && shareId !== null, "the share link answer has no token or id");
  t.check("the link opens the public replay page", jsonString(created.json, "url")?.endsWith(`/replay/${token}`) === true, jsonString(created.json, "url"));

  const visitor = t.instance.anonymousSession();
  const metadata = await visitor.request(Endpoints.sharedReplay(token), { auth: "none" });
  t.require(metadata.status === HttpStatus.ok, `the shared replay answered ${String(metadata.status)} logged out`);
  t.check("the shared replay names the player and nothing internal", jsonString(metadata.json, "targetName") === target
    && jsonField(metadata.json, "replayId") === undefined && jsonField(metadata.json, "targetUuid") === undefined, metadata.json);
  const fileUrl = jsonString(metadata.json, "fileUrl");
  t.require(fileUrl !== null, "the shared replay has no file URL");
  const file = await visitor.bytes(fileUrl);
  t.check("the shared file is the captured replay", file.status === HttpStatus.ok && file.body.equals(captured.file), { status: file.status, bytes: file.body.length });
  t.check("the shared file is never cached", file.cacheControl.includes("no-store"), file.cacheControl);
  const head = await visitor.bytes(fileUrl, { range: `bytes=0-${String(REPLAY_FILE_MAGIC.length - 1)}` });
  t.check("the shared file answers ranges", head.status === HttpStatus.partialContent && head.body.toString("latin1") === REPLAY_FILE_MAGIC,
    { status: head.status, range: head.contentRange });

  const reopened = await visitor.request(Endpoints.sharedReplay(token), { auth: "none" });
  t.require(reopened.status === HttpStatus.ok, `reopening the shared replay answered ${String(reopened.status)}`);
  const views = await awaitShareViews(t, captured.id, shareId);
  t.check("opening the link counts one view per visitor, downloads count none", views === 1, views);

  const revoked = await t.instance.apiWithCredentials(Endpoints.replayShare(captured.id, shareId), { method: "DELETE" });
  t.require(revoked.status === HttpStatus.ok, `revoking the link answered ${String(revoked.status)}`);
  const gone = await visitor.request(Endpoints.sharedReplay(token), { auth: "none" });
  t.check("a revoked link answers 404 with one code", gone.status === HttpStatus.notFound && apiErrorCode(gone) === ReplayErrors.shareNotFound,
    { status: gone.status, code: apiErrorCode(gone) });
  const goneFile = await visitor.bytes(fileUrl);
  t.check("a revoked link serves no file", goneFile.status === HttpStatus.notFound, goneFile.status);
}

function newestStoredReplayKey(t: ScenarioContext): string | null {
  const folder = join(t.env.server(t.coreServer).dataDir, DataDirectories.plugin, "storage", "modl_instance", "replays");
  if (!existsSync(folder)) return null;
  const files = readdirSync(folder).filter((name) => name.endsWith(".modlreplay"))
    .map((name) => ({ name, modified: statSync(join(folder, name)).mtimeMs }))
    .sort((first, second) => second.modified - first.modified);
  const newest = files[0];
  return newest === undefined ? null : `modl_instance/replays/${newest.name}`;
}

async function joinOn(t: ScenarioContext, name: string, server: string): Promise<TestBot> {
  const bot = await t.bot(name);
  if (t.topology !== "network" || (await proxyLocation(t, name)) === server) return bot;
  const switches = bot.serverSwitches;
  bot.chat(PlayerCommands.switchServer(server));
  await bot.awaitServerSwitch(switches);
  t.require((await proxyLocation(t, name)) === server, `the proxy did not place ${name} on ${server}`);
  return bot;
}

function onDemandServer(t: ScenarioContext): string {
  if (t.topology !== "network") return t.coreServer;
  t.require(memberNames(t).includes(ON_DEMAND_MEMBER), `the network has no ${ON_DEMAND_MEMBER} member`);
  return ON_DEMAND_MEMBER;
}

async function rconWithRetry(t: ScenarioContext, server: string, command: string): Promise<string> {
  for (let attempt = 1; ; attempt++) {
    try {
      return await t.env.console(server).rcon(command);
    } catch (error) {
      if (attempt >= RCON_ATTEMPTS) throw error;
      await t.sleep(300);
    }
  }
}

function sceneServer(t: ScenarioContext): string {
  const members = memberNames(t);
  return members[0] ?? t.coreServer;
}

function keepFixture(t: ScenarioContext, result: CapturedReplay, label: string): void {
  const directory = join(REPORTS_DIR, "replays");
  mkdirSync(directory, { recursive: true });
  const file = join(directory, `${t.env.state.options.line}--${t.topology}--${label}.modlreplay`);
  writeFileSync(file, result.file);
  t.evidence(`${label} file file`, { file });
}

function assertScene(t: ScenarioContext, scene: ReplayScene, file: ParsedReplay, target: string, walker: string): void {
  const header = file.header;
  const world = header.dimension?.world ?? 0;
  t.check("the header names the recorded player", header.targetName === target && header.targetUuid === offlineUuid(target), printable(header));
  t.check("the header names the overworld with its height", header.dimension?.key === "minecraft:overworld"
    && header.dimension.minY === scene.minY && header.dimension.height === scene.height, printable(header.dimension));
  t.check("the header carries the Minecraft and protocol versions", header.minecraftVersion.length > 0 && header.protocolVersion > 0, printable(header));

  const mismatches: unknown[] = [];
  for (const expected of scene.blocks) {
    const actual = blockAtEnd(file, world, expected.x, scene.baseY + expected.dy, expected.z);
    const propertiesMatch = Object.entries(expected.properties).every(([key, value]) => actual.properties[key] === value);
    if (actual.name !== expected.name || !propertiesMatch) mismatches.push({ at: [expected.x, scene.baseY + expected.dy, expected.z], expected, actual });
  }
  t.check("every scene block has its name and properties", mismatches.length === 0, mismatches);

  const entities = sceneEntities(file);
  const types = new Set(entities.map((entity) => stringAt(file, entity.type)));
  t.check("the scene mobs, items and players are recorded", SCENE_ENTITY_TYPES.every((type) => types.has(type)), [...types]);
  const armored = entities.find((entity) => entity.uuid === offlineUuid("Armored"));
  const helmet = armored?.equipment.find((slot) => slot.slot === ReplayEquipmentSlot.HEAD && slot.item !== undefined);
  t.check("the armored player wears the diamond helmet", helmet?.item !== undefined && stringAt(file, helmet.item.item) === "minecraft:diamond_helmet",
    armored?.equipment.map((slot) => ({ slot: slot.slot, item: slot.item ? stringAt(file, slot.item.item) : null })));

  const players = new Set(playerNames(file));
  t.check("the player list holds every player with a name", ["Armored", target, walker].every((name) => players.has(name)), [...players]);

  const walkerUuid = offlineUuid(walker);
  const walkerSpawn = file.events.find(({ event }) => event.event.case === "entitySpawn" && event.event.value.entity?.uuid === walkerUuid);
  t.check("the second player spawns after the recording started", walkerSpawn !== undefined && walkerSpawn.timeMs > 0, walkerSpawn?.timeMs);
  const chat = file.events.find(({ event }) => event.event.case === "chat" && event.event.value.componentJson.includes(CHAT_LINE));
  t.check("the chat line is recorded after the second player spawned", chat !== undefined && walkerSpawn !== undefined && chat.timeMs >= walkerSpawn.timeMs, chat?.timeMs);

  const zombieSpawn = file.events.find(({ event }) => event.event.case === "entitySpawn" && event.event.value.entity !== undefined
    && stringAt(file, event.event.value.entity.type) === "minecraft:zombie");
  t.check("the summoned zombie spawns during the recording", zombieSpawn !== undefined, null);
  const emerald = file.events.some(({ event }) => {
    const content = event.event;
    if (content.case === "entityMetadata") return content.value.metadata?.item !== undefined && stringAt(file, content.value.metadata.item.item) === "minecraft:emerald";
    if (content.case === "entitySpawn") return content.value.entity?.metadata?.item !== undefined && stringAt(file, content.value.entity.metadata.item.item) === "minecraft:emerald";
    return false;
  });
  t.check("the dropped emerald is recorded with its item", emerald, null);

  const changes = file.events.filter(({ event }) => event.event.case === "blockChange"
    && event.event.value.x === PLACED.x && event.event.value.y === scene.baseY && event.event.value.z === PLACED.z)
    .map(({ timeMs, event }) => ({ timeMs, name: event.event.case === "blockChange" ? file.palette[event.event.value.state]?.name : undefined }));
  const placedAt = changes.findIndex((change) => change.name === "minecraft:stone");
  const brokenAt = changes.findIndex((change, index) => index > placedAt && change.name === "minecraft:air");
  t.check("the placed block appears and is then broken, in order", placedAt >= 0 && brokenAt > placedAt, changes);

  const times = file.events.map(({ timeMs }) => timeMs);
  t.check("events are in time order", times.every((time, index) => index === 0 || time >= (times[index - 1] ?? 0)), null);
  const inventory = file.keyframes.at(-1)?.target?.inventory.map((slot) => slot.item ? stringAt(file, slot.item.item) : "") ?? [];
  const inventoryEvents = file.events.flatMap(({ event }) => event.event.case === "targetInventory"
    ? event.event.value.slots.map((slot) => slot.item ? stringAt(file, slot.item.item) : "") : []);
  t.check("the recorded player's inventory holds the diamonds", [...inventory, ...inventoryEvents].includes("minecraft:diamond"), { inventory, inventoryEvents });
}

function sceneEntities(file: ParsedReplay): ReplayEntitySnapshot[] {
  const fromKeyframes = file.keyframes.flatMap((keyframe) => keyframe.entities);
  const fromSpawns = file.events.flatMap(({ event }) => event.event.case === "entitySpawn" && event.event.value.entity ? [event.event.value.entity] : []);
  return [...fromKeyframes, ...fromSpawns];
}

function playerNames(file: ParsedReplay): string[] {
  const fromKeyframes = file.keyframes.flatMap((keyframe) => keyframe.players.map((player) => player.name));
  const fromEvents = file.events.flatMap(({ event }) => event.event.case === "playerInfo" && event.event.value.player ? [event.event.value.player.name] : []);
  return [...fromKeyframes, ...fromEvents];
}
