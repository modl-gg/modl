import { McpServer, type CallToolResult, type StandardSchemaWithJSON } from "@modelcontextprotocol/server";
import { serveStdio } from "@modelcontextprotocol/server/stdio";
import { z } from "zod";
import { runBotAction, type BotActionName } from "./lib/bots/actions";
import { BotPool, entryTarget } from "./lib/bots/pool";
import { Environment } from "./lib/environment";
import { HARNESS_VERSION } from "./lib/harness";
import { down, status, up } from "./lib/lifecycle";
import { HTTP_AUTH, type HttpAuth } from "./lib/http";
import { consoleAudits, consoleErrors, markLogs, queryLogs, upOptions } from "./lib/inspect";
import { JAVA_FEATURE_VERSION } from "./lib/java-runtime";
import { createLogger } from "./lib/log";
import { SETUP_CODE_SOURCES } from "./lib/instance";
import { doctorReport, instanceOverview } from "./lib/overview";
import { LINE_NAMES, REMOTE_BACKEND_KINDS, requireState, TOPOLOGY_NAMES } from "./lib/state";
import { errorMessage, offlineUuid } from "./lib/util";
import { runSuite, scenarioCatalog, scenarioMatrix, suiteAutoUp } from "./scenarios/index";

const logger = createLogger("mcp");
const bots = new BotPool(() => entryTarget(requireState()), logger);

type BotArgumentShape = Record<string, z.ZodType>;

function text(payload: unknown): CallToolResult {
  return { content: [{ type: "text", text: typeof payload === "string" ? payload : JSON.stringify(payload, null, 2) }] };
}

async function respond(name: string, run: () => unknown): Promise<CallToolResult> {
  try {
    return text(await run());
  } catch (error) {
    const message = `${name} failed: ${errorMessage(error)}`;
    logger.error(message);
    return { ...text(message), isError: true };
  }
}

class HarnessTools {
  private readonly server: McpServer;
  private readonly bots: BotPool;

  constructor(server: McpServer, botPool: BotPool) {
    this.server = server;
    this.bots = botPool;
  }

  tool<A>(name: string, description: string, inputSchema: StandardSchemaWithJSON<unknown, A>, handler: (args: A) => unknown): void {
    this.server.registerTool(name, { description, inputSchema }, async (args) => respond(name, () => handler(args)));
  }

  botTool(name: string, action: BotActionName, description: string, shape: BotArgumentShape): void {
    this.tool(name, description, z.object({ username: z.string().optional(), ...shape }), async ({ username, ...args }) =>
      runBotAction(this.bots.require(username), action, args),
    );
  }
}

function environment(): Environment {
  return Environment.current(logger);
}

const topology = z.enum(TOPOLOGY_NAMES);
const line = z.enum(LINE_NAMES).describe("platform line: latest (default), 1.21, legacy (Minecraft 1.8.8 on Java 8), or folia (Folia instead of Paper); see mc_lines");
const serverName = z.string().describe("server name from mc_status (paper, proxy, lobby, survival, backend)");
const noArguments = z.object({});
const milliseconds = z.number().int().positive();
const count = z.number().int().positive();

function registerLifecycleTools(tools: HarnessTools): void {
  tools.tool("mc_doctor", "Check Docker, Node, the product jars in dist/, cached server jars and leftovers.", noArguments, async () => doctorReport());

  tools.tool("mc_status", "Report the running topology, containers, ports, panel URL and claim state.", noArguments, async () => status(logger));

  tools.tool(
    "mc_up",
    "Boot a topology in Docker (standalone, network, network-bungee or remote) and wait until modl and the panel are ready. First boots download server jars and runtime libraries.",
    z.object({
      topology,
      line: line.optional(),
      fresh: z.boolean().optional(),
      adminEmail: z.string().optional().describe("preset the owner email instead of claiming with the setup code"),
      backend: z.enum(REMOTE_BACKEND_KINDS).optional().describe("remote topology: server jar (default) or the repository compose stack"),
      proxyPacketEvents: z.boolean().optional().describe("network topologies: install PacketEvents on the proxy (default true)"),
      java: z
        .string()
        .regex(JAVA_FEATURE_VERSION)
        .optional()
        .describe("Java feature version for the servers, at least the line's (latest 25, 1.21 21, legacy 8, folia 25); servers that need more keep their minimum"),
      deferSetup: z
        .boolean()
        .optional()
        .describe("leave the claim or owner sign-in to a scenario or the panel; network and remote then connect their servers on mc_connect"),
      updateFixture: z.boolean().optional().describe("build and sign test releases and add their mirror; the update scenarios need it"),
    }),
    async (args) => {
      bots.quitAll();
      return up(upOptions(args), logger);
    },
  );

  tools.tool(
    "mc_down",
    "Stop every server and bot, remove containers, networks and volumes, and verify nothing is left.",
    z.object({ keepData: z.boolean().optional() }),
    async (args) => {
      bots.quitAll();
      return down({ keepData: args.keepData ?? false }, logger);
    },
  );

  tools.tool(
    "mc_connect",
    "After mc_up with deferSetup: get an owner session and connect the servers that need a key (a server key for each server behind the network proxy, the API key for the remote Paper).",
    noArguments,
    async () => {
      bots.quitAll();
      return environment().connectServers();
    },
  );

  tools.tool(
    "mc_server",
    "Lifecycle of one server: restart (graceful), stop (graceful), start, or kill (SIGKILL).",
    z.object({ server: serverName, action: z.enum(["restart", "stop", "start", "kill"]) }),
    async (args) => {
      const env = environment();
      if (args.action === "restart") return env.restart(args.server);
      if (args.action === "stop") return env.stopGracefully(args.server);
      if (args.action === "start") return env.start(args.server);
      return env.kill(args.server);
    },
  );
}

function registerConsoleTools(tools: HarnessTools): void {
  tools.tool(
    "mc_console",
    "Run a console command (stdin, like a server panel console; bypasses staff permission and 2FA gating) and return the console lines it produced.",
    z.object({ command: z.string(), server: z.string().optional(), expect: z.string().optional(), timeoutMs: milliseconds.optional() }),
    async (args) =>
      environment()
        .console(args.server)
        .run(args.command, { expect: args.expect ? new RegExp(args.expect, "i") : undefined, timeoutMs: args.timeoutMs }),
  );

  tools.tool("mc_rcon", "Run a Paper RCON command and return its direct reply.", z.object({ server: serverName, command: z.string() }), async (args) =>
    environment().console(args.server).rcon(args.command),
  );

  tools.tool(
    "mc_logs",
    "Read a server console (docker logs of the container).",
    z.object({
      server: z.string().optional(),
      tail: count.optional(),
      grep: z.string().optional(),
      sinceMark: z.boolean().optional(),
      level: z.enum(["warn", "error"]).optional(),
    }),
    async (args) => {
      const env = environment();
      return (await queryLogs(env, { ...args, server: args.server ?? env.state.coreServer })).join("\n");
    },
  );

  tools.tool("mc_mark", "Remember the current position of every console for sinceMark reads.", noArguments, async () => markLogs(environment()));

  tools.tool(
    "mc_errors",
    "Console problems (ERROR/FATAL and warnings with exceptions), benign noise filtered.",
    z.object({ servers: z.array(z.string()).optional(), sinceMark: z.boolean().optional() }),
    async (args) => consoleErrors(environment(), args.servers ?? [], args.sinceMark ?? false),
  );

  tools.tool(
    "mc_audit",
    "Console policy audit: backend/library noise and modl lines outside the allowlist.",
    z.object({ servers: z.array(z.string()).optional(), sinceMark: z.boolean().optional() }),
    async (args) => consoleAudits(environment(), args.servers ?? [], args.sinceMark ?? false),
  );
}

function registerBotTools(tools: HarnessTools): void {
  tools.tool(
    "mc_bot_join",
    "Connect an offline-mode Mineflayer bot through the topology's entry point and wait for spawn.",
    z.object({ username: z.string() }),
    async (args) => {
      const bot = await bots.join(args.username);
      return { ...bot.status(), offlineUuid: offlineUuid(bot.username) };
    },
  );

  tools.tool("mc_bot_quit", "Disconnect a bot.", z.object({ username: z.string() }), (args) => ({ quit: bots.quit(args.username) }));

  tools.tool("mc_bot_list", "List bots with connection state, position and kick reasons.", noArguments, () => bots.statuses());

  tools.botTool("mc_chat", "chat", "Have a bot send a chat message or slash command.", { message: z.string() });
  tools.botTool("mc_run_command", "command", "Have a bot run a command and capture the replies.", {
    command: z.string(),
    expect: z.string().optional(),
    timeoutMs: milliseconds.optional(),
  });
  tools.botTool("mc_recent_chat", "recent", "Recent chat and system messages a bot received.", { limit: count.optional() });
  tools.botTool("mc_open_menu", "menu", "Run a command that opens an inventory GUI and read its items.", {
    trigger: z.string(),
    timeoutMs: milliseconds.optional(),
  });
  tools.botTool("mc_read_window", "window", "Read the bot's open inventory GUI.", {});
  tools.botTool("mc_click_slot", "click", "Click a slot in the bot's open inventory GUI.", {
    slot: z.number().int().nonnegative(),
    button: z.enum(["left", "right"]).optional(),
  });
  tools.botTool("mc_close_window", "close", "Close the bot's open inventory GUI.", {});
  tools.botTool("mc_bot_status", "status", "A bot's health, position, server switches and kick reason.", {});
  tools.botTool("mc_players", "players", "Players in the bot's tab list.", {});
  tools.botTool("mc_scoreboard", "sidebar", "The sidebar scoreboard the bot sees.", {});
  tools.botTool("mc_wander", "wander", "Walk and jump around for a while (gives replay recording something to capture).", { ms: milliseconds.optional() });

  tools.tool(
    "mc_offline_uuid",
    "Offline-mode UUID for a player name (how offline servers and modl identify bots).",
    z.object({ username: z.string() }),
    (args) => ({ username: args.username, offlineUuid: offlineUuid(args.username) }),
  );
}

function registerInstanceTools(tools: HarnessTools): void {
  tools.tool("modl_instance", "Panel base URL (for Playwright), health, setup phase, setup code and code file, and owner session.", noArguments, async () =>
    instanceOverview(environment()),
  );

  tools.tool(
    "modl_login",
    "Ensure an owner panel session: reuse, claim with the console setup code, sign in with the owner sign-in code, or log in with the console login code.",
    noArguments,
    async () => environment().instance.ensureOwnerSession(),
  );

  tools.tool(
    "modl_setup_code",
    "Setup code: the latest one printed to the console (default) or the one in the setup code file. A restart of the server that hosts the panel prints a new one.",
    z.object({ source: z.enum(SETUP_CODE_SOURCES).optional() }),
    async (args) => environment().instance.setupCode(args.source ?? "console"),
  );

  tools.tool(
    "modl_api",
    "Call the modl HTTP API with the owner session, the instance API key, or anonymously.",
    z.object({
      method: z.enum(["GET", "POST", "PUT", "PATCH", "DELETE"]),
      path: z.string(),
      body: z.unknown().optional(),
      auth: HTTP_AUTH.optional(),
    }),
    async (args) => {
      const auth: HttpAuth = args.auth ?? "session";
      const result = await environment().instance.apiWithCredentials(args.path, { method: args.method, json: args.body, auth });
      return { status: result.status, contentType: result.contentType, body: result.json ?? result.text };
    },
  );

  tools.tool("modl_link_code", "Create a Minecraft link code for the owner (run /modl link <code> with a bot).", noArguments, async () => {
    const instance = environment().instance;
    await instance.ensureOwnerSession();
    return instance.createMinecraftLinkCode();
  });
}

function registerScenarioTools(tools: HarnessTools): void {
  tools.tool("mc_scenarios", "List the scenarios with the topologies and platform lines they run on.", noArguments, () => scenarioCatalog());

  tools.tool("mc_lines", "Platform lines: pinned Paper, Velocity and BungeeCord, Java, topologies and the scenarios that apply to each.", noArguments, () =>
    scenarioMatrix(),
  );

  tools.tool(
    "mc_scenario",
    "Run scenarios (or 'all') against the running topology; optionally boot one first and tear down after.",
    z.object({
      names: z.array(z.string()).min(1),
      up: topology.optional(),
      line: line.optional(),
      deferSetup: z.boolean().optional().describe("with up: leave the claim or owner sign-in to the setup scenarios"),
      updateFixture: z.boolean().optional().describe("with up: build and sign test releases for the update scenarios"),
      down: z.boolean().optional(),
    }),
    async (args) => {
      bots.quitAll();
      const autoUp = suiteAutoUp(args.up, { line: args.line, deferSetup: args.deferSetup, updateFixture: args.updateFixture });
      return runSuite(args.names, { autoUp, downAfter: args.down ?? false }, logger);
    },
  );
}

function createServer(): McpServer {
  const server = new McpServer({ name: "modl-minecraft", version: HARNESS_VERSION });
  const tools = new HarnessTools(server, bots);
  registerLifecycleTools(tools);
  registerConsoleTools(tools);
  registerBotTools(tools);
  registerInstanceTools(tools);
  registerScenarioTools(tools);
  return server;
}

const connection = serveStdio(createServer, { onerror: (error) => logger.error(`stdio: ${error.message}`) });
logger.info("modl-minecraft MCP server serving stdio");

let shuttingDown = false;

function shutdown(): void {
  if (shuttingDown) return;
  shuttingDown = true;
  bots.quitAll();
  connection.close().catch((error: unknown) => logger.warn(`closing stdio failed: ${errorMessage(error)}`));
  setTimeout(() => process.exit(0), 200);
}

process.on("SIGINT", shutdown);
process.on("SIGTERM", shutdown);
process.stdin.on("close", shutdown);
