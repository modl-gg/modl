import { createHash } from "node:crypto";
import { jsonField, jsonString } from "../lib/http";

export const HOUR_MS = 3_600_000;
export const DAY_MS = 24 * HOUR_MS;
export const HISTORY_DAYS = 30;

const SERVERS = ["lobby", "survival", "skyblock", "bedwars"] as const;

const NAMES = [
  "Notch_Fan99", "xXSlayerXx", "CraftyMiner", "PixelPanda", "RedstoneRex", "LunaBuilds", "TheDiamondKid", "Blockhead42",
  "SkyWalker_MC", "Creeperz", "EnderQueen", "FrostByte", "MossyCobble", "NetherNinja", "OakLeaf", "PiglinPal",
  "QuartzQuest", "RavenClaw7", "ShulkerBox", "TNTnoob", "UnderdogMC", "VoidRunner", "WitherWitch", "Yeti_Gamer",
  "ZombieZed", "AceOfSpades", "BlazeRod", "CopperCraft", "DuskDragon", "EmeraldEli", "FlintSteel", "GlowSquidd",
  "HoneyBee_", "IronGolem01", "JungleJim", "KelpKing", "LapisLad", "MagmaCube", "NovaStar", "Obsidian_Ox",
] as const;

const CHAT_LINES = [
  "anyone want to trade diamonds?", "gg that was close", "where is spawn", "lol", "can someone help me with my base",
  "this server is sick", "who stole my chest", "ez", "brb", "how do i claim land", "join my team", "lag again??",
  "nice build", "gl everyone", "you are trash at this game", "free stuff at my base 100%", "stop spawn killing",
  "what version is this", "any staff online?", "report this guy he is flying",
] as const;

const REASONS = [
  "Spamming chat", "Toxic behaviour toward other players", "Using a fly hack", "X-ray texture pack",
  "Inappropriate username", "Advertising another server", "Griefing a protected build", "Exploiting a duplication bug",
] as const;

const NOTES = ["Watch this player, multiple reports this week.", "Appealed before, was polite.", "Possible alt of a banned account.", "Helpful in chat, consider for staff."] as const;

const TICKET_KINDS = [
  { type: "player", subject: "Player is flying", description: "Saw them flying over the lava lake near spawn." },
  { type: "chat", subject: "Toxic chat", description: "Insulting everyone in global chat." },
  { type: "bug", subject: "Chests duplicate items", description: "Breaking a double chest while someone opens it duplicates the items." },
  { type: "support", subject: "Lost my items", description: "Died to a lag spike and lost my whole inventory." },
  { type: "application", subject: "Moderator application", description: "I have been playing for two years and want to help." },
] as const;

export const EXAMPLE_PLAYER = { uuid: "dfc1d5fe-106e-47b2-93b0-f2a9b0dc1f86", name: "byteful" } as const;

export interface SeedPlayer {
  uuid: string;
  name: string;
  ips: string[];
}

export interface MinecraftTarget {
  post(path: string, body: unknown, label: string): Promise<unknown>;
}

export interface PanelTarget {
  patch(path: string, body: unknown, label: string): Promise<unknown>;
  postPublic(path: string, body: unknown, label: string): Promise<unknown>;
}

async function acknowledge(minecraft: MinecraftTarget, response: unknown, playerUuid: string): Promise<void> {
  const punishmentId = idOf(response);
  if (punishmentId === null) return;
  await minecraft.post("/v1/minecraft/punishments/acknowledge", { punishmentId, playerUuid, success: true }, "acknowledged punishments");
}

export interface SeedClock {
  advanceTo(virtualMs: number): Promise<void>;
}

export const realClock: SeedClock = {
  advanceTo: async () => Promise.resolve(),
};

interface SeedEvent {
  at: number;
  order: number;
  run: () => Promise<void>;
}

function uuidFor(name: string): string {
  const hex = createHash("sha256").update(`modl-seed:${name}`).digest("hex");
  const variant = ((Number.parseInt(hex.charAt(16), 16) & 0x3) | 0x8).toString(16);
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-4${hex.slice(13, 16)}-${variant}${hex.slice(17, 20)}-${hex.slice(20, 32)}`;
}

function skinHashFor(name: string): string {
  return createHash("sha256").update(`modl-seed-skin:${name}`).digest("hex");
}

function pick<T>(items: readonly T[], index: number): T {
  const item = items[index % items.length];
  if (item === undefined) throw new Error("empty list");
  return item;
}

function publicIp(seed: number): string {
  return `${String(81 + (seed % 90))}.${String((seed * 37) % 250 + 1)}.${String((seed * 91) % 250 + 1)}.${String((seed * 13) % 250 + 1)}`;
}

export function seedPlayers(): SeedPlayer[] {
  const players = NAMES.map((name, index) => {
    const ips = [publicIp(index + 1)];
    if (index % 3 === 0) ips.push(publicIp(index + 101));
    if (index % 8 === 1) ips.push(publicIp(2));
    return { uuid: uuidFor(name), name, ips };
  });
  return [{ uuid: EXAMPLE_PLAYER.uuid, name: EXAMPLE_PLAYER.name, ips: [publicIp(7), publicIp(240)] }, ...players];
}

function idOf(response: unknown): string | null {
  return jsonString(response, "id") ?? jsonString(response, "ticketId") ?? jsonString(response, "punishmentId") ?? jsonString(jsonField(response, "data"), "id");
}

export class SeedTimeline {
  private readonly events: SeedEvent[] = [];
  private readonly end: number;

  constructor(end: number) {
    this.end = end;
  }

  daysAgo(days: number, hour = 12): number {
    const midnight = this.end - (this.end % DAY_MS) - days * DAY_MS;
    return Math.min(midnight + hour * HOUR_MS, this.end - HOUR_MS);
  }

  at(at: number, run: () => Promise<void>): void {
    this.events.push({ at, order: this.events.length, run });
  }

  async play(clock: SeedClock): Promise<void> {
    const ordered = [...this.events].sort((left, right) => left.at - right.at || left.order - right.order);
    let current: number | null = null;
    for (const event of ordered) {
      if (event.at !== current) {
        await clock.advanceTo(event.at);
        current = event.at;
      }
      await event.run();
    }
    await clock.advanceTo(this.end);
  }
}

export interface DatasetTargets {
  minecraft: MinecraftTarget;
  panel: PanelTarget | null;
  end: number;
}

export function planDataset(targets: DatasetTargets): SeedTimeline {
  const timeline = new SeedTimeline(targets.end);
  const players = seedPlayers();
  const [example, ...others] = players;
  if (!example) throw new Error("the example player is missing");
  planLogins(timeline, targets.minecraft, others);
  planExamplePlayer(timeline, targets, example, others);
  planChat(timeline, targets.minecraft, players);
  planPunishments(timeline, targets.minecraft, others);
  planNotes(timeline, targets.minecraft, others);
  planTickets(timeline, targets, others);
  return timeline;
}

function planLogins(timeline: SeedTimeline, minecraft: MinecraftTarget, players: readonly SeedPlayer[]): void {
  for (const [index, player] of players.entries()) {
    const firstDay = HISTORY_DAYS - (index % 10);
    for (const [visit, ip] of player.ips.entries()) {
      timeline.at(timeline.daysAgo(firstDay - visit * 3, 9 + (index % 10)), async () => {
        await minecraft.post("/v1/minecraft/players/login", {
          minecraftUUID: player.uuid,
          username: player.name,
          ip,
          skinHash: skinHashFor(player.name),
          serverName: pick(SERVERS, index),
        }, "player logins");
      });
    }
    if (index % 4 !== 0) {
      timeline.at(timeline.daysAgo(1 + (index % 5), 20), async () => {
        await minecraft.post("/v1/minecraft/players/disconnect", { minecraftUuid: player.uuid, sessionDurationMs: (index + 1) * 600_000 }, "disconnects");
      });
    }
  }
}

function planExamplePlayer(timeline: SeedTimeline, targets: DatasetTargets, example: SeedPlayer, others: readonly SeedPlayer[]): void {
  const { minecraft, panel } = targets;
  const visits = [{ day: 29, ip: 0 }, { day: 12, ip: 1 }, { day: 3, ip: 0 }];
  for (const visit of visits) {
    timeline.at(timeline.daysAgo(visit.day, 18), async () => {
      await minecraft.post("/v1/minecraft/players/login", {
        minecraftUUID: example.uuid,
        username: example.name,
        ip: pick(example.ips, visit.ip),
        serverName: "survival",
      }, "player logins");
    });
  }
  timeline.at(timeline.daysAgo(21, 19), async () => {
    await acknowledge(minecraft, await minecraft.post("/v1/minecraft/punishments/dynamic", {
      targetUuid: example.uuid,
      issuerName: "Console",
      type_ordinal: 6,
      reason: "Spamming chat",
      severity: "lenient",
      status: "first",
      console: true,
    }, "severity punishments"), example.uuid);
  });
  timeline.at(timeline.daysAgo(9, 15), async () => {
    await minecraft.post(`/v1/minecraft/players/${example.uuid}/notes`, {
      text: "Second chat offence this month, escalate next time.",
      issuerName: "Console",
      console: true,
      kind: "WARNING",
    }, "player notes");
  });
  let banId: string | null = null;
  timeline.at(timeline.daysAgo(9, 16), async () => {
    banId = idOf(await minecraft.post("/v1/minecraft/punishments/dynamic", {
      targetUuid: example.uuid,
      issuerName: "Console",
      type_ordinal: 2,
      reason: "Toxic behaviour toward other players",
      duration: 14 * DAY_MS,
      console: true,
    }, "manual punishments"));
    if (banId !== null) await minecraft.post("/v1/minecraft/punishments/acknowledge", { punishmentId: banId, playerUuid: example.uuid, success: true }, "acknowledged punishments");
  });
  if (panel) {
    timeline.at(timeline.daysAgo(8, 11), async () => {
      if (banId === null) return;
      await panel.postPublic("/v1/public/appeals", {
        punishmentId: banId,
        playerUuid: example.uuid,
        email: "byteful@modl.test",
        reason: "I was frustrated after losing a fight and said things I should not have. It will not happen again.",
      }, "appeals");
    });
  }
  const reporter = pick(others, 5);
  let reportId: string | null = null;
  timeline.at(timeline.daysAgo(2, 21), async () => {
    reportId = idOf(await minecraft.post("/v1/minecraft/tickets", {
      creatorUuid: reporter.uuid,
      creatorName: reporter.name,
      type: "chat",
      subject: `${example.name} is insulting players`,
      description: "Keeps insulting people in global chat after being asked to stop.",
      reportedPlayerUuid: example.uuid,
      reportedPlayerName: example.name,
      chatMessages: [`${example.name}: you are trash at this game`, `${example.name}: uninstall`, `${reporter.name}: please stop`],
      priority: "high",
      createdServer: "survival",
    }, "tickets"));
  });
  if (panel) {
    timeline.at(timeline.daysAgo(1, 10), async () => {
      if (reportId === null) return;
      await panel.patch(`/v1/panel/tickets/${encodeURIComponent(reportId)}`, {
        newReply: { content: "Thanks for the report. We are reviewing the chat log now." },
      }, "staff replies");
    });
  }
}

function planChat(timeline: SeedTimeline, minecraft: MinecraftTarget, players: readonly SeedPlayer[]): void {
  const at = timeline.daysAgo(0, 0);
  timeline.at(at, async () => {
    const entries = players.flatMap((player, index) => Array.from({ length: 6 }, (_, line) => ({
      uuid: player.uuid,
      username: player.name,
      message: pick(CHAT_LINES, index * 3 + line),
      timestamp: at - (index * 6 + line) * 97_000,
      server: pick(SERVERS, index + line),
    })));
    for (let start = 0; start < entries.length; start += 50) {
      await minecraft.post("/v1/minecraft/players/chat-log", { entries: entries.slice(start, start + 50) }, "chat log batches");
    }
  });
}

function planPunishments(timeline: SeedTimeline, minecraft: MinecraftTarget, players: readonly SeedPlayer[]): void {
  const severities = ["lenient", "regular", "aggravated"] as const;
  const offenses = ["first", "medium", "habitual"] as const;
  for (const [index, player] of players.entries()) {
    if (index % 5 === 4) continue;
    const manual = index % 3;
    const manualDuration = manual === 0 ? null : pick([DAY_MS, 7 * DAY_MS, 30 * DAY_MS, 0], index);
    timeline.at(timeline.daysAgo(20 - (index % 19), 2 + (index % 6)), async () => {
      const created = await minecraft.post("/v1/minecraft/punishments/dynamic", {
        targetUuid: player.uuid,
        issuerName: "Console",
        type_ordinal: manual,
        reason: pick(REASONS, index),
        duration: manualDuration,
        console: true,
      }, "manual punishments");
      if (manual !== 0) await acknowledge(minecraft, created, player.uuid);
    });
    if (index % 2 === 0) {
      timeline.at(timeline.daysAgo(15 - (index % 14), 17), async () => {
        await acknowledge(minecraft, await minecraft.post("/v1/minecraft/punishments/dynamic", {
          targetUuid: player.uuid,
          issuerName: "Console",
          type_ordinal: 6 + ((index / 2) % 12),
          reason: pick(REASONS, index + 3),
          severity: pick(severities, index),
          status: pick(offenses, index),
          console: true,
        }, "severity punishments"), player.uuid);
      });
    }
  }
}

function planNotes(timeline: SeedTimeline, minecraft: MinecraftTarget, players: readonly SeedPlayer[]): void {
  for (const [index, player] of players.entries()) {
    if (index % 4 !== 1) continue;
    timeline.at(timeline.daysAgo(10 - (index % 9), 13), async () => {
      await minecraft.post(`/v1/minecraft/players/${player.uuid}/notes`, {
        text: pick(NOTES, index),
        issuerName: "Console",
        console: true,
        kind: index % 8 === 1 ? "WARNING" : "NOTE",
      }, "player notes");
    });
  }
}

function planTickets(timeline: SeedTimeline, targets: DatasetTargets, players: readonly SeedPlayer[]): void {
  for (let index = 0; index < 24; index += 1) {
    const creator = pick(players, index * 3);
    const reported = pick(players, index * 3 + 7);
    const kind = pick(TICKET_KINDS, index);
    const reports = kind.type === "player" || kind.type === "chat";
    let ticketId: string | null = null;
    timeline.at(timeline.daysAgo(14 - Math.floor((index * 14) / 24), 10 + (index % 11)), async () => {
      ticketId = idOf(await targets.minecraft.post("/v1/minecraft/tickets", {
        creatorUuid: creator.uuid,
        creatorName: creator.name,
        type: kind.type,
        subject: kind.subject,
        description: kind.description,
        ...(reports ? { reportedPlayerUuid: reported.uuid, reportedPlayerName: reported.name } : {}),
        ...(kind.type === "chat" ? { chatMessages: [`${reported.name}: you are trash at this game`, `${reported.name}: uninstall`] } : {}),
        priority: pick(["low", "normal", "high"], index),
        createdServer: pick(SERVERS, index),
      }, "tickets"));
    });
    const panel = targets.panel;
    if (panel && index % 3 === 0) {
      timeline.at(timeline.daysAgo(13 - Math.floor((index * 14) / 24), 9), async () => {
        if (ticketId === null) return;
        await panel.patch(`/v1/panel/tickets/${encodeURIComponent(ticketId)}`, {
          newReply: { content: "Thanks, we are looking into it." },
        }, "staff replies");
      });
    }
  }
}
