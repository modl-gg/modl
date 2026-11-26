import { z } from "zod";
import { serveBotDaemon } from "../lib/bots/daemon-server";
import { createLogger } from "../lib/log";
import { BOT_BEHAVIORS, botTarget, DevBotPool } from "./bots";
import { BACKEND_NAMES, DEV_BOT_DAEMON } from "./layout";
import { readDevState } from "./state";

const logger = createLogger("devnet-botd");

function botGatePort(): number {
  const state = readDevState();
  if (!state) throw new Error("the dev network has not been booted");
  return state.ports.botGate;
}

const pool = new DevBotPool(() => botTarget(botGatePort()), logger);

const addBody = z.object({
  count: z.number().int().min(1).max(50),
  server: z.enum(BACKEND_NAMES),
  behavior: z.enum(BOT_BEHAVIORS),
  prefix: z.string().min(1).max(14),
});

const removeBody = z.object({ name: z.string().min(1) });

serveBotDaemon({
  file: DEV_BOT_DAEMON.file,
  idleTimeoutMs: null,
  logger,
  health: () => ({ bots: pool.statuses().length }),
  onShutdown: () => pool.removeAll(),
  routes: {
    "GET /bots": () => pool.statuses(),
    "POST /bots": async (body) => pool.add(addBody.parse(body)),
    "POST /remove": (body) => ({ removed: pool.remove(removeBody.parse(body).name) }),
  },
});
