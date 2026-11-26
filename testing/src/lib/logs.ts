import { z } from "zod";
import { containerLogs, containerState, runDocker } from "./docker";
import { MARKS_FILE } from "./paths";
import { plainText, readJson, sleep, writeJson } from "./util";

export type LogMark = string;

export const LOG_START: LogMark = "";

export interface LogLine {
  ts: string;
  text: string;
}

const DOCKER_TIMESTAMP = /^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d{1,9}))?Z$/;

export function timestampKey(ts: string): bigint {
  const [, wholeSeconds, fraction = ""] = DOCKER_TIMESTAMP.exec(ts) ?? [];
  if (wholeSeconds === undefined) return -1n;
  const seconds = BigInt(Math.floor(Date.parse(`${wholeSeconds}Z`) / 1000));
  const nanos = BigInt(fraction.padEnd(9, "0"));
  return seconds * 1_000_000_000n + nanos;
}

function parseLine(raw: string): LogLine | null {
  const separator = raw.indexOf(" ");
  if (separator <= 0) return null;
  const ts = raw.slice(0, separator);
  if (!DOCKER_TIMESTAMP.test(ts)) return null;
  return { ts, text: plainText(raw.slice(separator + 1)).replace(/\r$/, "") };
}

function chronological(left: LogLine, right: LogLine): number {
  const difference = timestampKey(left.ts) - timestampKey(right.ts);
  return difference < 0n ? -1 : difference > 0n ? 1 : 0;
}

function parseOutput(output: string): LogLine[] {
  return output
    .split("\n")
    .map(parseLine)
    .filter((line): line is LogLine => line !== null)
    .sort(chronological);
}

export interface ProgressWindow {
  pattern: RegExp;
  idleMs: number;
}

export interface WaitOptions {
  since?: LogMark;
  timeoutMs: number;
  pollMs?: number;
  failOn?: RegExp | undefined;
  requireRunning?: boolean;
  progress?: ProgressWindow;
}

class ProgressTracker {
  private readonly window: ProgressWindow;
  private seen = 0;
  private lastProgressAt = Date.now();

  constructor(window: ProgressWindow) {
    this.window = window;
  }

  stall(lines: readonly LogLine[]): string | null {
    const seen = lines.filter((line) => this.window.pattern.test(line.text)).length;
    if (seen > this.seen) {
      this.seen = seen;
      this.lastProgressAt = Date.now();
    }
    const idle = Date.now() - this.lastProgressAt;
    return idle > this.window.idleMs ? `no progress line matching ${this.window.pattern.toString()} for ${idle}ms` : null;
  }
}

export class LogWaitError extends Error {}

export class ContainerLog {
  readonly server: string;
  readonly container: string;

  constructor(server: string, container: string) {
    this.server = server;
    this.container = container;
  }

  async lines(since: LogMark = LOG_START): Promise<LogLine[]> {
    const all = parseOutput(await containerLogs(this.container, since || undefined));
    if (!since) return all;
    const floor = timestampKey(since);
    return all.filter((line) => timestampKey(line.ts) > floor);
  }

  async text(since: LogMark = LOG_START): Promise<string[]> {
    return (await this.lines(since)).map((line) => line.text);
  }

  async mark(): Promise<LogMark> {
    const result = await runDocker(["logs", "--timestamps", "--tail", "1", this.container], { allowFailure: true });
    const last = parseOutput(`${result.stdout}${result.stderr}`).at(-1);
    return last?.ts ?? LOG_START;
  }

  async find(pattern: RegExp, since: LogMark = LOG_START): Promise<LogLine[]> {
    return (await this.lines(since)).filter((line) => pattern.test(line.text));
  }

  async waitFor(pattern: RegExp, options: WaitOptions): Promise<LogLine> {
    const deadline = Date.now() + options.timeoutMs;
    const pollMs = options.pollMs ?? 750;
    const progress = options.progress ? new ProgressTracker(options.progress) : null;
    let polls = 0;
    while (Date.now() < deadline) {
      const lines = await this.lines(options.since ?? LOG_START);
      const hit = lines.find((line) => pattern.test(line.text));
      if (hit) return hit;
      const failOn = options.failOn;
      const failure = failOn ? lines.find((line) => failOn.test(line.text)) : undefined;
      if (failure) throw new LogWaitError(`${this.server}: ${failure.text}`);
      const stall = progress?.stall(lines);
      if (stall) throw new LogWaitError(`${this.server}: no console line matching ${pattern.toString()}, and ${stall}`);
      polls += 1;
      if (options.requireRunning && polls % 4 === 0) await this.assertRunning(lines);
      await sleep(pollMs);
    }
    throw new LogWaitError(`${this.server}: no console line matching ${pattern.toString()} within ${options.timeoutMs}ms`);
  }

  private async assertRunning(lines: LogLine[]): Promise<void> {
    const state = await containerState(this.container);
    if (state.status === "running" || state.status === "created" || state.status === "restarting") return;
    const tail = lines.slice(-40).map((line) => line.text).join("\n");
    const reason = state.oomKilled ? "out of memory (container limit)" : `exit code ${String(state.exitCode)}`;
    throw new LogWaitError(`${this.server} stopped (${state.status}, ${reason}). Last console lines:\n${tail}`);
  }
}

const LOG_MARKS = z.record(z.string(), z.string());

export function loadMarks(): Record<string, LogMark> {
  return readJson(MARKS_FILE, LOG_MARKS, {});
}

export function saveMarks(marks: Record<string, LogMark>): void {
  writeJson(MARKS_FILE, marks);
}
