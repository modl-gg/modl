import { TestBot, type BotTarget } from "../lib/bots/bot";
import type { Logger } from "../lib/log";
import { PAPER_BUILDS } from "../lib/paper";
import { settings } from "../lib/settings";
import { errorMessage, sleep } from "../lib/util";
import { z } from "zod";
import { BACKEND_NAMES, type BackendName } from "./layout";

export const BOT_BEHAVIORS = ["idle", "wander", "chat", "pvp"] as const;
export type BotBehavior = (typeof BOT_BEHAVIORS)[number];

export function isBotBehavior(value: string): value is BotBehavior {
  return (BOT_BEHAVIORS as readonly string[]).includes(value);
}

const DEV_BOT_PHASES = ["joining", "online", "reconnecting", "refused", "removed"] as const;
export type DevBotPhase = (typeof DEV_BOT_PHASES)[number];

export const devBotStatusSchema = z.object({
  name: z.string(),
  server: z.enum(BACKEND_NAMES),
  behavior: z.enum(BOT_BEHAVIORS),
  phase: z.enum(DEV_BOT_PHASES),
  position: z.object({ x: z.number(), y: z.number(), z: z.number() }).nullable(),
  health: z.number().nullable(),
  lastProblem: z.string().nullable(),
});

export type DevBotStatus = z.infer<typeof devBotStatusSchema>;

const RECONNECT_DELAY_MS = 20_000;
const MAX_FAILED_JOINS = 15;
const BANNED = /\bbanned\b/i;
const PVP_RANGE = 16;
const PVP_TICK_MS = 400;
const SWITCH_TIMEOUT_MS = 30_000;

const CHAT_LINES = [
  "hey everyone",
  "anyone want to trade?",
  "where is spawn again",
  "gg",
  "lag?",
  "who wants to build something",
  "brb",
  "nice base",
  "anyone seen my stuff",
  "this server is cool",
];

function randomBetween(minMs: number, maxMs: number): number {
  return minMs + Math.floor(Math.random() * (maxMs - minMs));
}

function pick<T>(values: readonly T[]): T | undefined {
  return values[Math.floor(Math.random() * values.length)];
}

export function botTarget(port: number): BotTarget {
  return {
    host: "127.0.0.1",
    port,
    version: settings().botVersionOverride ?? PAPER_BUILDS.paperLatest.minecraftVersion,
    joinTimeoutMs: settings().botJoinTimeoutMs,
  };
}

class DevBot {
  readonly name: string;
  readonly server: BackendName;
  readonly behavior: BotBehavior;
  private readonly target: () => BotTarget;
  private readonly logger: Logger;
  private bot: TestBot | null = null;
  private phase: DevBotPhase = "joining";
  private lastProblem: string | null = null;
  private failedJoins = 0;

  constructor(name: string, server: BackendName, behavior: BotBehavior, target: () => BotTarget, logger: Logger) {
    this.name = name;
    this.server = server;
    this.behavior = behavior;
    this.target = target;
    this.logger = logger;
  }

  isRemoved(): boolean {
    return this.phase === "removed";
  }

  async join(): Promise<void> {
    const bot = new TestBot(this.name, this.target(), this.logger);
    this.bot = bot;
    await bot.join();
    if (this.server !== "latest") {
      const switches = bot.serverSwitches;
      await bot.runCommand(`/server ${this.server}`, { timeoutMs: 3000 });
      await bot.awaitServerSwitch(switches, SWITCH_TIMEOUT_MS);
    }
    this.phase = "online";
    this.lastProblem = null;
  }

  run(): void {
    void this.loop();
  }

  private async loop(): Promise<void> {
    while (!this.isRemoved()) {
      const bot = this.bot;
      if (bot?.connected === true) {
        await this.tick(bot).catch((error: unknown) => {
          this.lastProblem = errorMessage(error);
        });
        continue;
      }
      this.lastProblem = this.droppedReason() ?? this.lastProblem;
      if (BANNED.test(this.lastProblem ?? "")) {
        this.phase = "refused";
        return;
      }
      this.phase = "reconnecting";
      await sleep(RECONNECT_DELAY_MS);
      if (this.isRemoved()) return;
      try {
        await this.join();
        this.failedJoins = 0;
      } catch (error) {
        this.lastProblem = errorMessage(error);
        this.failedJoins += 1;
        if (this.failedJoins >= MAX_FAILED_JOINS || BANNED.test(this.lastProblem)) {
          this.phase = "refused";
          return;
        }
      }
    }
  }

  private async tick(bot: TestBot): Promise<void> {
    switch (this.behavior) {
      case "idle":
        await bot.lookAround();
        await sleep(randomBetween(4000, 9000));
        return;
      case "wander":
        await bot.wander(randomBetween(3000, 7000));
        await sleep(randomBetween(1000, 4000));
        return;
      case "chat":
        await sleep(randomBetween(25_000, 50_000));
        if (bot.connected) bot.chat(pick(CHAT_LINES) ?? "hi");
        return;
      case "pvp": {
        const target = await bot.attackNearestPlayer(PVP_RANGE);
        if (target === null) await bot.wander(2000);
        else await sleep(PVP_TICK_MS);
        return;
      }
    }
  }

  remove(): void {
    this.phase = "removed";
    this.bot?.quit();
  }

  private droppedReason(): string | null {
    const status = this.bot?.status();
    return status?.kickReason ?? status?.disconnectReason ?? null;
  }

  status(): DevBotStatus {
    const connected = this.bot?.connected === true;
    const status = connected ? (this.bot?.status() ?? null) : null;
    const dropped = this.phase === "online" && !connected;
    return {
      name: this.name,
      server: this.server,
      behavior: this.behavior,
      phase: dropped ? "reconnecting" : this.phase,
      position: status?.position ?? null,
      health: status?.health ?? null,
      lastProblem: dropped ? this.droppedReason() : this.lastProblem,
    };
  }
}

export interface AddBots {
  count: number;
  server: BackendName;
  behavior: BotBehavior;
  prefix: string;
}

const NAME_RULE = /^[A-Za-z0-9_]{3,16}$/;
const JOIN_CONCURRENCY = 4;

export class DevBotPool {
  private readonly bots = new Map<string, DevBot>();
  private readonly target: () => BotTarget;
  private readonly logger: Logger;

  constructor(target: () => BotTarget, logger: Logger) {
    this.target = target;
    this.logger = logger;
  }

  private nextNames(prefix: string, count: number): string[] {
    const names: string[] = [];
    for (let index = 1; names.length < count; index += 1) {
      const name = `${prefix}${String(index).padStart(2, "0")}`;
      if (!NAME_RULE.test(name)) throw new Error(`bot name ${name} does not follow the Minecraft name rule; use a shorter --prefix`);
      if (!this.bots.has(name)) names.push(name);
    }
    return names;
  }

  async add(request: AddBots): Promise<DevBotStatus[]> {
    const names = this.nextNames(request.prefix, request.count);
    const bots = names.map((name) => new DevBot(name, request.server, request.behavior, this.target, this.logger));
    for (const bot of bots) this.bots.set(bot.name, bot);
    const queue = [...bots];
    const worker = async (): Promise<void> => {
      for (let bot = queue.shift(); bot !== undefined; bot = queue.shift()) {
        await bot.join().catch((error: unknown) => {
          this.logger.warn(`bot ${bot.name} could not join: ${errorMessage(error)}`);
        });
        bot.run();
      }
    };
    await Promise.all(Array.from({ length: Math.min(JOIN_CONCURRENCY, bots.length) }, worker));
    return bots.map((bot) => bot.status());
  }

  remove(name: string): string[] {
    const targets = name === "all" ? [...this.bots.values()] : [this.bots.get(name)].filter((bot): bot is DevBot => bot !== undefined);
    if (targets.length === 0) throw new Error(`no bot named ${name}; known: ${[...this.bots.keys()].join(", ") || "none"}`);
    for (const bot of targets) {
      bot.remove();
      this.bots.delete(bot.name);
    }
    return targets.map((bot) => bot.name);
  }

  statuses(): DevBotStatus[] {
    return [...this.bots.values()].map((bot) => bot.status());
  }

  removeAll(): void {
    for (const bot of this.bots.values()) bot.remove();
    this.bots.clear();
  }
}
