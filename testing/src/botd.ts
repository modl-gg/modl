import { z } from "zod";
import { runBotAction } from "./lib/bots/actions";
import { serveBotDaemon } from "./lib/bots/daemon-server";
import { BotPool, entryTarget } from "./lib/bots/pool";
import { createLogger } from "./lib/log";
import { BOT_DAEMON_FILE } from "./lib/paths";
import { requireState } from "./lib/state";

const IDLE_TIMEOUT_MS = 2 * 60 * 60 * 1000;
const logger = createLogger("botd");
const pool = new BotPool(() => entryTarget(requireState()), logger);

const botNameBody = z.object({ name: z.string() });

const botActionBody = z.object({
  action: z.string().default(""),
  args: z.record(z.string(), z.unknown()).default({}),
});

function name(body: unknown): string {
  const parsed = botNameBody.safeParse(body);
  if (!parsed.success) throw new Error("missing bot name");
  return parsed.data.name;
}

async function runAction(body: unknown): Promise<unknown> {
  const bot = pool.require(name(body));
  const { action, args } = botActionBody.parse(body);
  return runBotAction(bot, action, args);
}

serveBotDaemon({
  file: BOT_DAEMON_FILE,
  idleTimeoutMs: IDLE_TIMEOUT_MS,
  logger,
  health: () => ({ bots: pool.statuses() }),
  onShutdown: () => pool.quitAll(),
  routes: {
    "GET /bots": () => pool.statuses(),
    "POST /bots": async (body) => (await pool.join(name(body))).status(),
    "POST /do": runAction,
    "POST /quit": (body) => ({ quit: pool.quit(name(body)) }),
  },
});
