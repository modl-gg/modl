import { lineOf } from "../lines";
import type { Logger } from "../log";
import { settings } from "../settings";
import { entryGamePort, type EnvironmentState } from "../state";
import { TestBot, type BotStatus, type BotTarget } from "./bot";

const PLAYER_NAME = /^[A-Za-z0-9_]{3,16}$/;

export function entryTarget(state: EnvironmentState): BotTarget {
  return {
    host: "127.0.0.1",
    port: entryGamePort(state),
    version: settings().botVersionOverride ?? lineOf(state.options).paper.minecraftVersion,
    joinTimeoutMs: settings().botJoinTimeoutMs,
  };
}

export class BotPool {
  private readonly bots = new Map<string, TestBot>();
  private readonly target: () => BotTarget;
  private readonly logger: Logger;

  constructor(target: () => BotTarget, logger: Logger) {
    this.target = target;
    this.logger = logger;
  }

  create(username: string): TestBot {
    if (!PLAYER_NAME.test(username)) throw new Error(`invalid player name '${username}' (3-16 of A-Z, a-z, 0-9, _)`);
    return new TestBot(username, this.target(), this.logger);
  }

  async join(username: string): Promise<TestBot> {
    const existing = this.bots.get(username);
    if (existing?.connected) return existing;
    const bot = this.create(username);
    await bot.join();
    this.bots.set(username, bot);
    return bot;
  }

  get(username: string): TestBot | undefined {
    return this.bots.get(username);
  }

  require(username?: string): TestBot {
    const connected = this.list().filter((bot) => bot.connected);
    if (username) {
      const bot = this.bots.get(username);
      if (!bot) throw new Error(`no bot named ${username}; known: ${[...this.bots.keys()].join(", ") || "none"}`);
      return bot;
    }
    const [only, ...others] = connected;
    if (only && others.length === 0) return only;
    throw new Error(connected.length === 0 ? "no bots connected" : `several bots connected (${connected.map((bot) => bot.username).join(", ")}); name one`);
  }

  list(): TestBot[] {
    return [...this.bots.values()];
  }

  statuses(): BotStatus[] {
    return this.list().map((bot) => bot.status());
  }

  quit(username: string): boolean {
    const bot = this.bots.get(username);
    if (!bot) return false;
    bot.quit();
    this.bots.delete(username);
    return true;
  }

  quitAll(): void {
    for (const bot of this.bots.values()) bot.quit();
    this.bots.clear();
  }
}
