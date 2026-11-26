import { Rcon } from "rcon-client";
import { execInContainer } from "./docker";
import { ContainerLog, type LogMark } from "./logs";
import type { EnvironmentState, ServerState } from "./state";
import { errorMessage, plainText, sleep } from "./util";

export const CONTAINER_CONSOLE_SCRIPT = "/modl-testing/console.sh";

const RCON_TIMEOUT_MS = 10_000;

export interface ConsoleRunOptions {
  expect?: RegExp | undefined;
  timeoutMs?: number | undefined;
  settleMs?: number | undefined;
}

export interface ConsoleResult {
  server: string;
  command: string;
  since: LogMark;
  lines: string[];
  matched: string | null;
}

export class ServerConsole {
  readonly log: ContainerLog;
  private readonly server: ServerState;
  private readonly rconPassword: string;

  constructor(server: ServerState, rconPassword: string) {
    this.server = server;
    this.rconPassword = rconPassword;
    this.log = new ContainerLog(server.name, server.container);
  }

  get name(): string {
    return this.server.name;
  }

  async send(command: string): Promise<void> {
    if (!this.server.hasConsole) throw new Error(`${this.server.name} has no console`);
    await execInContainer(this.server.container, ["/bin/sh", CONTAINER_CONSOLE_SCRIPT, command.replace(/^\//, "")]);
  }

  async run(command: string, options: ConsoleRunOptions = {}): Promise<ConsoleResult> {
    const since = await this.log.mark();
    await this.send(command);
    return options.expect
      ? this.collectUntilMatch(command, since, options.expect, options.timeoutMs ?? 20_000)
      : this.collectUntilQuiet(command, since, options.settleMs ?? 1500, options.timeoutMs ?? 8000);
  }

  private async collectUntilMatch(command: string, since: LogMark, expect: RegExp, timeoutMs: number): Promise<ConsoleResult> {
    const deadline = Date.now() + timeoutMs;
    let lines: string[] = [];
    while (Date.now() < deadline) {
      lines = await this.log.text(since);
      const matched = lines.find((line) => expect.test(line));
      if (matched) return { server: this.server.name, command, since, lines, matched };
      await sleep(400);
    }
    return { server: this.server.name, command, since, lines, matched: null };
  }

  private async collectUntilQuiet(command: string, since: LogMark, settleMs: number, timeoutMs: number): Promise<ConsoleResult> {
    const deadline = Date.now() + timeoutMs;
    let lines: string[] = [];
    let lastGrowth = Date.now();
    while (Date.now() < deadline) {
      await sleep(300);
      const next = await this.log.text(since);
      if (next.length !== lines.length) lastGrowth = Date.now();
      lines = next;
      if (lines.length > 0 && Date.now() - lastGrowth >= settleMs) break;
    }
    return { server: this.server.name, command, since, lines, matched: null };
  }

  async rcon(command: string): Promise<string> {
    if (this.server.rconPort === null) throw new Error(`${this.server.name} has no RCON (only Paper servers do)`);
    const client = await Rcon.connect({
      host: "127.0.0.1",
      port: this.server.rconPort,
      password: this.rconPassword,
      connectTimeout: RCON_TIMEOUT_MS,
      timeout: RCON_TIMEOUT_MS,
      reconnect: false,
    });
    try {
      return plainText(await client.send(command.replace(/^\//, ""))).trim();
    } finally {
      await client.end();
    }
  }

  async waitForRcon(timeoutMs: number): Promise<void> {
    const deadline = Date.now() + timeoutMs;
    let lastError = "";
    while (Date.now() < deadline) {
      try {
        await this.rcon("list");
        return;
      } catch (error) {
        lastError = errorMessage(error);
        await sleep(500);
      }
    }
    throw new Error(`RCON on ${this.server.name} did not answer within ${timeoutMs}ms: ${lastError}`);
  }
}

export function consoleFor(state: EnvironmentState, server: ServerState): ServerConsole {
  return new ServerConsole(server, state.rconPassword);
}
