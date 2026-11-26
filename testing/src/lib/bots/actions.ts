import type { TestBot } from "./bot";

export type BotActionArgs = Record<string, unknown>;

type BotAction = (bot: TestBot, args: BotActionArgs) => unknown;

function requiredText(args: BotActionArgs, key: string): string {
  const value = args[key];
  if (typeof value !== "string" || value === "") throw new Error(`missing argument '${key}'`);
  return value;
}

function optionalNumber(args: BotActionArgs, key: string): number | undefined {
  const value = args[key];
  if (value === undefined || value === null || value === "") return undefined;
  const parsed = Number(value);
  if (!Number.isFinite(parsed)) throw new Error(`argument '${key}' must be a number`);
  return parsed;
}

function requiredNumber(args: BotActionArgs, key: string): number {
  const value = optionalNumber(args, key);
  if (value === undefined) throw new Error(`missing argument '${key}'`);
  return value;
}

function optionalPattern(args: BotActionArgs, key: string): RegExp | undefined {
  const value = args[key];
  return typeof value === "string" && value !== "" ? new RegExp(value, "i") : undefined;
}

function textOnly(bot: TestBot, since: number) {
  return bot.messagesSince(since).map((message) => ({ position: message.position, text: message.text }));
}

export const BOT_ACTIONS = {
  chat: (bot, args) => {
    const message = requiredText(args, "message");
    bot.chat(message);
    return { sent: message };
  },
  command: async (bot, args) => {
    const since = bot.lastSeq;
    const command = requiredText(args, "command");
    await bot.runCommand(command, {
      expect: optionalPattern(args, "expect"),
      timeoutMs: optionalNumber(args, "timeoutMs"),
    });
    return { command, messages: textOnly(bot, since) };
  },
  recent: (bot, args) =>
    bot.recentMessages(optionalNumber(args, "limit") ?? 20).map((message) => ({ position: message.position, text: message.text })),
  "recent-styled": (bot, args) => bot.recentStyledMessages(optionalNumber(args, "limit") ?? 20),
  menu: async (bot, args) => bot.openWindow(requiredText(args, "trigger"), optionalNumber(args, "timeoutMs")),
  window: (bot) => bot.readWindow() ?? { open: false },
  styled: (bot) => bot.readStyledWindow() ?? { open: false },
  click: async (bot, args) => {
    await bot.clickSlot(requiredNumber(args, "slot"), args["button"] === "right" ? "right" : "left");
    return { clicked: args["slot"], window: bot.readWindow() };
  },
  close: (bot) => {
    bot.closeWindow();
    return { closed: true };
  },
  status: (bot) => bot.status(),
  hotbar: (bot) => bot.hotbar(),
  players: (bot) => bot.listPlayers(),
  sidebar: (bot) => bot.sidebar() ?? { sidebar: false },
  wander: async (bot, args) => {
    await bot.wander(optionalNumber(args, "ms") ?? 5000);
    return bot.status();
  },
} satisfies Record<string, BotAction>;

export type BotActionName = keyof typeof BOT_ACTIONS;

function isBotActionName(action: string): action is BotActionName {
  return Object.hasOwn(BOT_ACTIONS, action);
}

export async function runBotAction(bot: TestBot, action: string, args: BotActionArgs): Promise<unknown> {
  if (!isBotActionName(action)) throw new Error(`unknown bot action '${action}' (actions: ${Object.keys(BOT_ACTIONS).join(", ")})`);
  const handler: BotAction = BOT_ACTIONS[action];
  return Promise.resolve(handler(bot, args));
}
