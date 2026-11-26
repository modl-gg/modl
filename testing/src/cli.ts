import { z } from "zod";
import { artifactStatuses, ensureArtifacts, ARTIFACT_IDS } from "./lib/artifacts";
import { BOT_ACTIONS } from "./lib/bots/actions";
import { CancelledError } from "./lib/cancellation";
import { flagNumber, flagSet, flagText, parseArgs, printOut, type ParsedArgs } from "./lib/cli-args";
import { botDaemonStatus, botRequest, stopBotDaemon } from "./lib/bots/daemon-client";
import { Environment } from "./lib/environment";
import { down, status, up } from "./lib/lifecycle";
import { HTTP_AUTH, HTTP_METHOD } from "./lib/http";
import { consoleAudits, consoleErrors, markLogs, queryLogs, upOptions, type UpRequest } from "./lib/inspect";
import { createLogger } from "./lib/log";
import type { SetupCodeSource } from "./lib/instance";
import { doctorReport, instanceOverview } from "./lib/overview";
import { errorDetail, offlineUuid } from "./lib/util";
import { runSuite, scenarioCatalog, scenarioMatrix, suiteAutoUp } from "./scenarios/index";
import { isMatrixTier, MATRIX_TIERS, runMatrix } from "./matrix";

const logger = createLogger("modl-testing");

const BOOLEAN_FLAGS = new Set(["fresh", "keep-data", "down", "since-mark", "without-proxy-packetevents", "defer-setup", "update-fixture", "file", "help", "retry-failed"]);

function upRequestFrom(flags: ParsedArgs["flags"]): Omit<UpRequest, "topology"> {
  return {
    fresh: flagSet(flags, "fresh"),
    adminEmail: flagText(flags, "admin-email") ?? null,
    backend: flagText(flags, "backend"),
    proxyPacketEvents: !flagSet(flags, "without-proxy-packetevents"),
    line: flagText(flags, "line"),
    java: flagText(flags, "java"),
    deferSetup: flagSet(flags, "defer-setup"),
    updateFixture: flagSet(flags, "update-fixture"),
    realClient: flagSet(flags, "real-client"),
  };
}

function setupCodeSource(flags: ParsedArgs["flags"]): SetupCodeSource {
  return flagSet(flags, "file") ? "file" : "console";
}

const SIGNAL_EXIT_CODES: Record<string, number> = { SIGINT: 130, SIGTERM: 143 };

class SignalInterruption extends CancelledError {
  readonly signal: NodeJS.Signals;

  constructor(signal: NodeJS.Signals) {
    super(`interrupted by ${signal}`);
    this.signal = signal;
  }

  get exitCode(): number {
    return SIGNAL_EXIT_CODES[this.signal] ?? 1;
  }
}

function interruptOnSignal(interruption: AbortController): () => void {
  const handler = (signal: NodeJS.Signals) => {
    if (interruption.signal.aborted) process.exit(new SignalInterruption(signal).exitCode);
    logger.warn(`${signal} received: stopping and tearing the environment down (send it again to exit at once)`);
    interruption.abort(new SignalInterruption(signal));
  };
  process.on("SIGINT", handler);
  process.on("SIGTERM", handler);
  return () => {
    process.off("SIGINT", handler);
    process.off("SIGTERM", handler);
  };
}

const out = printOut;

const HELP = `modl testing harness (Docker Minecraft servers + Mineflayer bots)

Environment
  up <standalone|network|network-bungee|remote> [--line latest|1.21|legacy|folia] [--fresh] [--admin-email <email>]
     [--backend jar|compose] [--without-proxy-packetevents] [--java <feature version, at least the line's>]
     [--defer-setup]                    leave the claim (or owner sign-in) to a scenario or the panel; see connect
     [--update-fixture]                 build and sign test releases and add their mirror; the update scenarios need it
  lines                                 platform lines: pins, Java, topologies and the scenarios that apply
  down [--keep-data]                    stop everything, verify nothing is left (data removed unless --keep-data)
  status | doctor
  restart|stop|start|kill <server>      lifecycle of one server (see status for names)
  connect                               after up --defer-setup: owner session, then connect the servers that need the API key
  deps fetch|list                       pinned third-party jars (Paper, Velocity, BungeeCord, PacketEvents)

Consoles and logs
  cmd <command...> [--server S] [--expect RE] [--timeout MS]   console command via stdin, prints new console lines
  rcon <server> <command...>            Paper RCON command, prints the direct reply
  logs [server] [--tail N] [--grep RE] [--since-mark] [--level warn|error]
  mark                                  remember log positions for --since-mark
  errors [server...] [--since-mark]     ERROR/FATAL and exception warnings (benign noise filtered)
  audit [server...] [--since-mark]      console policy audit: noise and unexpected modl lines

Panel and backend
  instance                              panel URL (for Playwright), setup phase, setup code and code file, owner
  login                                 ensure an owner session (claim, owner sign-in code or console login code)
  setup-code [--file]                   latest setup code from the console (or read the code file)
  api <METHOD> <path> [json] [--auth session|api-key|none]
  link-code                             create a Minecraft link code for the owner

Bots (kept alive by a background daemon between calls)
  bot join <name> | bot quit <name> | bot list | bot stop | bot actions
  bot do <name> <action> [key=value ...]
  bot run <name> <command...>          run a command and print the replies
  bot say <name> <message...>

Scenarios
  scenario <name...|all> [--up <topology>] [--down] [--retry-failed] [up flags]   --down also tears down on Ctrl+C; --retry-failed runs a failed scenario once more and reports a pass as flaky
  list
  matrix [--tier quick|full|ci] [--lanes 1|2]   run scenario jobs in parallel lanes (each boots fresh and tears down);
                                        writes reports/matrix/<time>/summary.md; quick: standalone and network on latest; ci: standalone only

Wiki videos
  video <id...|all>                     boot each video's topology fresh, record it, tear down (runtime data removed);
                                        writes reports/video/ and src/video/manifest.json
  video-voices                          render the narration voice samples to reports/video/voice-samples/
`;

const KEY_VALUE_OBJECT = z.record(z.string(), z.unknown());

function keyValues(parts: readonly string[]): Record<string, unknown> {
  const joined = parts.join(" ").trim();
  if (joined.startsWith("{")) return KEY_VALUE_OBJECT.parse(JSON.parse(joined));
  return Object.fromEntries(
    parts.map((part) => {
      const separator = part.indexOf("=");
      if (separator <= 0) throw new Error(`expected key=value, got '${part}'`);
      return [part.slice(0, separator), part.slice(separator + 1)];
    }),
  );
}

async function botCommand(positional: string[]): Promise<unknown> {
  const [sub, name, ...rest] = positional;
  switch (sub) {
    case "join":
      return botRequest("POST", "/bots", { name }, 120_000);
    case "quit":
      return botRequest("POST", "/quit", { name });
    case "list":
      return botDaemonStatus();
    case "stop":
      return stopBotDaemon();
    case "actions":
      return Object.keys(BOT_ACTIONS);
    case "do": {
      const [action, ...args] = rest;
      return botRequest("POST", "/do", { name, action, args: keyValues(args) }, 180_000);
    }
    case "run":
      return botRequest("POST", "/do", { name, action: "command", args: { command: rest.join(" ") } });
    case "say":
      return botRequest("POST", "/do", { name, action: "chat", args: { message: rest.join(" ") } });
    case undefined:
    default:
      throw new Error(`unknown bot subcommand '${sub ?? ""}'`);
  }
}

async function main(argv: readonly string[]): Promise<number> {
  const { positional, flags } = parseArgs(argv, BOOLEAN_FLAGS);
  const [command, ...rest] = positional;
  if (!command || command === "help" || flagSet(flags, "help")) {
    process.stdout.write(HELP);
    return 0;
  }
  const environment = () => Environment.current(logger);
  switch (command) {
    case "up": {
      const report = await up(upOptions({ ...upRequestFrom(flags), topology: rest[0] ?? "" }), logger);
      out(report);
      return report.problems.length > 0 ? 2 : 0;
    }
    case "down": {
      const report = await down({ keepData: flagSet(flags, "keep-data") }, logger);
      out(report);
      return report.clean ? 0 : 1;
    }
    case "status":
      out(await status(logger));
      return 0;
    case "doctor":
      out(await doctorReport());
      return 0;
    case "restart":
    case "stop":
    case "start":
    case "kill": {
      const server = rest[0];
      if (!server) throw new Error(`usage: ${command} <server>`);
      const env = environment();
      const result =
        command === "restart"
          ? await env.restart(server)
          : command === "stop"
            ? await env.stopGracefully(server)
            : command === "start"
              ? await env.start(server)
              : await env.kill(server);
      out(result);
      return 0;
    }
    case "deps": {
      if (rest[0] === "fetch") await ensureArtifacts(ARTIFACT_IDS, logger);
      out(await artifactStatuses());
      return 0;
    }
    case "cmd": {
      const env = environment();
      const expect = flagText(flags, "expect");
      out(
        await env.console(flagText(flags, "server")).run(rest.join(" "), {
          expect: expect ? new RegExp(expect, "i") : undefined,
          timeoutMs: flagNumber(flags, "timeout"),
        }),
      );
      return 0;
    }
    case "rcon": {
      const [server, ...words] = rest;
      if (!server) throw new Error("usage: rcon <server> <command...>");
      out(await environment().console(server).rcon(words.join(" ")));
      return 0;
    }
    case "logs": {
      const env = environment();
      const level = flagText(flags, "level");
      const lines = await queryLogs(env, {
        server: rest[0] ?? env.state.coreServer,
        tail: flagNumber(flags, "tail"),
        grep: flagText(flags, "grep"),
        sinceMark: flagSet(flags, "since-mark"),
        level: level === "warn" || level === "error" ? level : undefined,
      });
      out(lines.join("\n"));
      return 0;
    }
    case "mark":
      out(await markLogs(environment()));
      return 0;
    case "errors":
      out(await consoleErrors(environment(), rest, flagSet(flags, "since-mark")));
      return 0;
    case "audit":
      out(await consoleAudits(environment(), rest, flagSet(flags, "since-mark")));
      return 0;
    case "instance":
      out(await instanceOverview(environment()));
      return 0;
    case "login":
      out(await environment().instance.ensureOwnerSession());
      return 0;
    case "setup-code":
      out(await environment().instance.setupCode(setupCodeSource(flags)));
      return 0;
    case "connect":
      out(await environment().connectServers());
      return 0;
    case "api": {
      const [rawMethod, path, ...body] = rest;
      if (!rawMethod || !path) throw new Error("usage: api <METHOD> <path> [json]");
      const method = HTTP_METHOD.safeParse(rawMethod.toUpperCase());
      if (!method.success) throw new Error(`unknown HTTP method '${rawMethod}' (${HTTP_METHOD.options.join("|")})`);
      const rawAuth = flagText(flags, "auth") ?? "session";
      const auth = HTTP_AUTH.safeParse(rawAuth);
      if (!auth.success) throw new Error(`unknown --auth '${rawAuth}' (${HTTP_AUTH.options.join("|")})`);
      const json: unknown = body.length > 0 ? JSON.parse(body.join(" ")) : undefined;
      const result = await environment().instance.apiWithCredentials(path, { method: method.data, json, auth: auth.data });
      out({ status: result.status, contentType: result.contentType, body: result.json ?? result.text });
      return result.status < 400 ? 0 : 1;
    }
    case "link-code": {
      const instance = environment().instance;
      await instance.ensureOwnerSession();
      out(await instance.createMinecraftLinkCode());
      return 0;
    }
    case "bot":
      out(await botCommand(rest));
      return 0;
    case "uuid":
      out({ username: rest[0], offlineUuid: offlineUuid(rest[0] ?? "") });
      return 0;
    case "list":
      out(scenarioCatalog());
      return 0;
    case "lines":
      out(scenarioMatrix());
      return 0;
    case "matrix": {
      const tier = flagText(flags, "tier") ?? "quick";
      if (!isMatrixTier(tier)) throw new Error(`--tier must be one of ${MATRIX_TIERS.join(", ")}`);
      const report = await runMatrix(tier, flagNumber(flags, "lanes") ?? 2, logger);
      out(report);
      return report.ok ? 0 : 1;
    }
    case "scenario": {
      if (rest.length === 0) throw new Error("usage: scenario <name...|all>");
      const autoUp = suiteAutoUp(flagText(flags, "up"), upRequestFrom(flags));
      const downAfter = flagSet(flags, "down");
      const interruption = new AbortController();
      const releaseSignals = downAfter ? interruptOnSignal(interruption) : () => undefined;
      try {
        const summary = await runSuite(rest, { autoUp, downAfter, retryFailed: flagSet(flags, "retry-failed"), signal: interruption.signal }, logger);
        out(summary);
        const reason: unknown = interruption.signal.reason;
        if (reason instanceof SignalInterruption) return reason.exitCode;
        return summary.ok ? 0 : 1;
      } finally {
        releaseSignals();
      }
    }
    case "video": {
      const { recordVideos } = await import("./video/index");
      const interruption = new AbortController();
      const releaseSignals = interruptOnSignal(interruption);
      try {
        out(await recordVideos(rest, interruption.signal, logger));
        return 0;
      } finally {
        releaseSignals();
      }
    }
    case "video-voices": {
      const { renderVoiceSamples } = await import("./video/voice-samples");
      const interruption = new AbortController();
      const releaseSignals = interruptOnSignal(interruption);
      try {
        out(await renderVoiceSamples(interruption.signal, logger));
        return 0;
      } finally {
        releaseSignals();
      }
    }
    default:
      throw new Error(`unknown command '${command}' (run help)`);
  }
}

main(process.argv.slice(2))
  .then((code) => process.exit(code))
  .catch((error: unknown) => {
    logger.error(errorDetail(error));
    process.exit(1);
  });
