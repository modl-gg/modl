import { spawn } from "node:child_process";
import { z } from "zod";
import { BotDaemonClient } from "../lib/bots/daemon-client";
import { flagNumber, flagSet, flagText, parseArgs, printOut } from "../lib/cli-args";
import { createLogger } from "../lib/log";
import { errorDetail } from "../lib/util";
import { BOT_BEHAVIORS, devBotStatusSchema, isBotBehavior, type DevBotStatus } from "./bots";
import { DEV_BOT_DAEMON, DEV_COMPOSE_FILE, DEV_PROJECT, DEV_SERVER_NAMES, devContainerName, isBackendName, isDevServerName, PANEL_LOG_SERVICE, type DevServerName } from "./layout";
import { DevNetwork, type DevOverview } from "./network";

const logger = createLogger("devnet");
const bots = new BotDaemonClient(DEV_BOT_DAEMON);

const BOOLEAN_FLAGS: ReadonlySet<string> = new Set(["follow", "yes", "help", "json"]);
const DEFAULT_LOG_TAIL = 200;
const BOT_JOIN_TIMEOUT_PER_BOT_MS = 60_000;

const HELP = `modl dev network: a permanent local Velocity network for playing and testing by hand

  npm run dev -- up                      boot or resume; prints the join address, panel, logs and setup code
  npm run dev -- down                    stop everything (worlds, plugins and modl data are kept)
  npm run dev -- status                  servers, modl state, panel setup, bots
  npm run dev -- rebuild                 install the current selfhost/dist build and restart in order (data kept)
  npm run dev -- restart [server]        restart one server or the whole network
  npm run dev -- logs [server|panel] [--follow] [--tail N]
  npm run dev -- cmd <server> <command...>   console command, prints the console lines it caused
  npm run dev -- connect --email <owner email> [--code <emailed code>]
                                         after the panel setup: owner sign-in, then server keys for every server
  npm run dev -- bots add <n> [--server latest|legacy] [--behavior ${BOT_BEHAVIORS.join("|")}] [--prefix Name]
  npm run dev -- bots list | bots remove <name|all>
  npm run dev -- reset --yes             delete every world, plugin folder and the panel data

Servers: ${DEV_SERVER_NAMES.join(", ")} (logs also takes panel: the built-in panel's server.log)
`;

function requireServer(value: string | undefined): DevServerName {
  if (value === undefined || !isDevServerName(value)) throw new Error(`name a server: ${DEV_SERVER_NAMES.join(", ")}`);
  return value;
}

function renderOverview(overview: DevOverview): string {
  const lines = [
    "modl dev network",
    `  Join (online mode, your account):  ${overview.joinAddress}`,
    `  Bot gate (offline mode, bots):     ${overview.botGateAddress}`,
    `  Panel:                             ${overview.panelUrl}${overview.panelHealthy ? "" : " (not answering)"}`,
    `  Logs:                              ${overview.logsUrl}`,
  ];
  if (overview.setup !== null) {
    const code = overview.setup.code === null ? "" : `, setup code ${overview.setup.code} (enter it at ${overview.setup.setupUrl})`;
    lines.push(`  Panel setup:                       ${overview.setup.phase}${code}`);
  }
  if (overview.installed !== null) lines.push(`  Installed:                         ${overview.installed}`);
  lines.push("  Servers:");
  for (const server of overview.servers) {
    lines.push(`    ${server.name.padEnd(8)} ${server.container.padEnd(8)} ${server.platform}`);
    lines.push(`    ${"".padEnd(8)} ${"".padEnd(8)} modl: ${server.modl}`);
  }
  for (const note of overview.notes) lines.push(`  Note: ${note}`);
  return lines.join("\n");
}

const botStatuses = z.array(devBotStatusSchema);

function renderBots(statuses: readonly DevBotStatus[]): string {
  if (statuses.length === 0) return "no bots";
  return statuses
    .map((bot) => {
      const where = bot.position === null ? "" : ` at ${bot.position.x.toFixed(0)} ${bot.position.y.toFixed(0)} ${bot.position.z.toFixed(0)}`;
      const problem = bot.lastProblem === null ? "" : ` (${bot.lastProblem.replace(/\s+/g, " ").trim()})`;
      return `${bot.name.padEnd(16)} ${bot.server.padEnd(7)} ${bot.behavior.padEnd(7)} ${bot.phase}${where}${problem}`;
    })
    .join("\n");
}

async function inherit(args: readonly string[]): Promise<number> {
  return new Promise((resolve, reject) => {
    const child = spawn("docker", args, { stdio: ["ignore", "inherit", "inherit"] });
    child.once("error", reject);
    child.once("close", (code) => resolve(code ?? 1));
  });
}

async function logs(server: string | undefined, follow: boolean, tail: number): Promise<number> {
  const options = ["--tail", String(tail), ...(follow ? ["--follow"] : [])];
  if (server === undefined) return inherit(["compose", "-p", DEV_PROJECT, "-f", DEV_COMPOSE_FILE, "logs", ...options]);
  const container = server === "panel" ? devContainerName(PANEL_LOG_SERVICE) : devContainerName(requireServer(server));
  return inherit(["logs", ...options, container]);
}

async function botsCommand(positional: readonly string[], flags: Record<string, string | true>): Promise<string> {
  const [sub, argument] = positional;
  switch (sub) {
    case "add": {
      const count = Number(argument ?? "1");
      if (!Number.isInteger(count) || count < 1) throw new Error("usage: bots add <n> [--server latest|legacy] [--behavior idle|wander|chat|pvp]");
      const server = flagText(flags, "server") ?? "latest";
      const behavior = flagText(flags, "behavior") ?? "wander";
      if (!isBackendName(server)) throw new Error(`--server must be latest or legacy, not ${server}`);
      if (!isBotBehavior(behavior)) throw new Error(`--behavior must be one of ${BOT_BEHAVIORS.join(", ")}`);
      const network = DevNetwork.existing(logger);
      if ((await network.overview()).servers.find((entry) => entry.name === "botgate")?.container !== "running") {
        throw new Error("the bot gate is not running; it starts after the panel setup and npm run dev -- connect");
      }
      const added = await bots.request(
        "POST",
        "/bots",
        { count, server, behavior, prefix: flagText(flags, "prefix") ?? "DevBot" },
        BOT_JOIN_TIMEOUT_PER_BOT_MS * count,
      );
      return renderBots(botStatuses.parse(added));
    }
    case "list": {
      const status = await bots.status();
      return status.running ? renderBots(botStatuses.parse(status.bots)) : "no bots (the bot daemon is not running)";
    }
    case "remove": {
      if (argument === undefined) throw new Error("usage: bots remove <name|all>");
      const removed = z.object({ removed: z.array(z.string()) }).parse(await bots.request("POST", "/remove", { name: argument }));
      return `removed ${removed.removed.join(", ")}`;
    }
    case undefined:
    default:
      throw new Error("usage: bots add <n> | bots list | bots remove <name|all>");
  }
}

async function main(argv: readonly string[]): Promise<number> {
  const { positional, flags } = parseArgs(argv, BOOLEAN_FLAGS);
  const [command, ...rest] = positional;
  const json = flagSet(flags, "json");
  const show = (overview: DevOverview): void => {
    printOut(json ? overview : renderOverview(overview));
  };
  switch (command) {
    case undefined:
    case "help":
      process.stdout.write(HELP);
      return 0;
    case "up":
      show(await DevNetwork.open(logger).up());
      return 0;
    case "down":
      await DevNetwork.existing(logger).down();
      printOut("The dev network is stopped. Worlds, plugin folders and the panel data are kept; npm run dev -- up resumes it.");
      return 0;
    case "status": {
      show(await DevNetwork.existing(logger).overview());
      const status = await bots.status();
      if (status.running) printOut(`Bots:\n${renderBots(botStatuses.parse(status.bots))}`);
      return 0;
    }
    case "rebuild":
      show(await DevNetwork.existing(logger).rebuild());
      return 0;
    case "restart":
      show(await DevNetwork.existing(logger).restart(rest[0] === undefined ? null : requireServer(rest[0])));
      return 0;
    case "logs":
      return logs(rest[0], flagSet(flags, "follow"), flagNumber(flags, "tail") ?? DEFAULT_LOG_TAIL);
    case "cmd": {
      const [server, ...words] = rest;
      if (words.length === 0) throw new Error("usage: cmd <server> <command...>");
      const result = await DevNetwork.existing(logger).command(requireServer(server), words.join(" "));
      printOut(result.lines.join("\n"));
      return 0;
    }
    case "connect": {
      const done = await DevNetwork.existing(logger).connect({ email: flagText(flags, "email"), code: flagText(flags, "code") });
      printOut(done.length === 0 ? "Every server already has its key." : done.join("\n"));
      return 0;
    }
    case "bots":
      printOut(await botsCommand(rest, flags));
      return 0;
    case "reset":
      if (!flagSet(flags, "yes")) throw new Error("reset deletes every world, plugin folder and the panel data of the dev network; run it again with --yes");
      await DevNetwork.open(logger).reset();
      printOut("The dev network was deleted. npm run dev -- up boots a fresh one.");
      return 0;
    default:
      throw new Error(`unknown command '${command}' (run npm run dev -- help)`);
  }
}

main(process.argv.slice(2))
  .then((code) => process.exit(code))
  .catch((error: unknown) => {
    logger.error(errorDetail(error));
    process.exit(1);
  });
