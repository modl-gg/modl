import { join } from "node:path";
import type { TestBot } from "../lib/bots/bot";
import { cancellable, cancellationError } from "../lib/cancellation";
import { BotPool, entryTarget } from "../lib/bots/pool";
import type { ConsoleResult, ConsoleRunOptions } from "../lib/console";
import type { ConsoleProblem } from "../lib/console-policy";
import { ConsoleCommands, PaperProblems, type SetupPhase } from "../lib/contract";
import type { Environment, LifecycleReport } from "../lib/environment";
import type { InstanceClient } from "../lib/instance";
import type { Logger } from "../lib/log";
import { REPORTS_DIR } from "../lib/paths";
import { settings } from "../lib/settings";
import type { LineName, TopologyName, UpOptions } from "../lib/state";
import { errorDetail, errorMessage, sleep, type Awaitable, truncate, uniquePlayerName, writeJson } from "../lib/util";

export interface Scenario {
  name: string;
  description: string;
  topologies: readonly TopologyName[];
  lines?: readonly LineName[];
  setupPhase?: SetupPhase;
  unsupportedBy?(options: UpOptions): string | null;
  run(t: ScenarioContext): Promise<void>;
}

export function defineScenario(scenario: Scenario): Scenario {
  return scenario;
}

export function notApplicableReason(scenario: Scenario, options: UpOptions): string | null {
  if (!scenario.topologies.includes(options.topology)) return `runs on ${scenario.topologies.join(", ")}`;
  if (scenario.lines && !scenario.lines.includes(options.line)) return `not applicable on the ${options.line} line (runs on ${scenario.lines.join(", ")})`;
  return scenario.unsupportedBy?.(options) ?? null;
}

async function setupPhaseMismatch(scenario: Scenario, env: Environment): Promise<string | null> {
  if (!scenario.setupPhase) return null;
  const status = await env.instance.setupStatus().catch(() => null);
  if (status === null || status.phase === scenario.setupPhase) return null;
  const presetOwner = env.state.options.presetAdminEmail === null ? "" : ` --admin-email ${env.state.options.presetAdminEmail}`;
  const fresh = `up ${env.state.topology} --fresh --defer-setup${presetOwner}`;
  return `needs a fresh instance in ${scenario.setupPhase}, but it is in ${status.phase} (start one with ${fresh} and run this scenario first)`;
}

export interface StepOptions {
  timeoutMs?: number;
  allowConsoleErrors?: boolean;
  ignoreProblems?: readonly RegExp[];
}

export interface CheckRecord {
  name: string;
  ok: boolean;
  evidence?: unknown;
}

export interface EvidenceRecord {
  label: string;
  data: unknown;
}

export interface StepRecord {
  name: string;
  ok: boolean;
  ms: number;
  error?: string;
  checks: CheckRecord[];
  evidence: EvidenceRecord[];
  consoleProblems?: ConsoleProblem[];
}

export interface ScenarioReport {
  scenario: string;
  description: string;
  topology: TopologyName;
  line: LineName;
  startedAt: string;
  finishedAt?: string;
  status: "passed" | "failed" | "skipped";
  reason?: string;
  steps: StepRecord[];
  notes: string[];
  file?: string;
}

export class StepFailure extends Error {}

const DEFAULT_STEP_TIMEOUT_MS = 120_000;
const CONNECT_MARGIN_MS = 240_000;

async function withTimeout<T>(body: () => Awaitable<T>, ms: number): Promise<T> {
  const step = new AbortController();
  const timer = setTimeout(() => step.abort(new StepFailure(`step timed out after ${ms}ms`)), ms);
  const expiry = new Promise<never>((_, reject) => {
    step.signal.addEventListener("abort", () => reject(cancellationError(step.signal)), { once: true });
  });
  try {
    return await Promise.race([cancellable(step.signal, body), expiry]);
  } finally {
    clearTimeout(timer);
    step.abort(new StepFailure("step finished"));
  }
}

export class ScenarioContext {
  private readonly pool: BotPool;
  private readonly strays = new Set<TestBot>();
  private current: StepRecord | null = null;
  private problemsExpectedInStep: RegExp[] = [];
  readonly env: Environment;
  readonly logger: Logger;
  private readonly report: ScenarioReport;

  constructor(env: Environment, logger: Logger, report: ScenarioReport) {
    this.env = env;
    this.logger = logger;
    this.report = report;
    this.pool = new BotPool(() => entryTarget(env.state), logger);
  }

  get topology(): TopologyName {
    return this.env.state.topology;
  }

  get instance(): InstanceClient {
    return this.env.instance;
  }

  get coreServer(): string {
    return this.env.state.coreServer;
  }

  get noticeServer(): string {
    return this.env.state.instance.noticeServer;
  }

  playerName(prefix: string): string {
    return uniquePlayerName(prefix);
  }

  async sleep(ms: number): Promise<void> {
    return sleep(ms);
  }

  async step<T>(name: string, body: () => Awaitable<T>, options: StepOptions = {}): Promise<T> {
    const record: StepRecord = { name, ok: true, ms: 0, checks: [], evidence: [] };
    this.current = record;
    const started = Date.now();
    this.problemsExpectedInStep = [...(options.ignoreProblems ?? []), ...this.env.problemsExpectedUntilConnected()];
    const marks = await this.env.markAll();
    this.logger.info(`> ${name}`);
    let outcome: { value: T } | null = null;
    try {
      outcome = { value: await withTimeout(body, options.timeoutMs ?? DEFAULT_STEP_TIMEOUT_MS) };
    } catch (error) {
      record.ok = false;
      record.error = errorMessage(error);
    }
    await sleep(400);
    const problems = await this.env.problems(marks, this.problemsExpectedInStep);
    const firstProblem = problems[0];
    if (firstProblem) {
      record.consoleProblems = problems;
      if (!options.allowConsoleErrors) {
        record.ok = false;
        record.error ??= `${problems.length} console problem(s), first on ${firstProblem.server}: ${truncate(firstProblem.message, 200)}`;
      }
    }
    const failedChecks = record.checks.filter((check) => !check.ok);
    if (failedChecks.length > 0) {
      record.ok = false;
      record.error ??= `failed checks: ${failedChecks.map((check) => check.name).join("; ")}`;
    }
    record.ms = Date.now() - started;
    this.report.steps.push(record);
    this.current = null;
    this.problemsExpectedInStep = [];
    if (outcome === null || !record.ok) {
      const failure = record.error ?? "the step failed";
      this.logger.info(`FAIL ${name} (${record.ms}ms): ${failure}`);
      throw new StepFailure(`step "${name}" failed: ${failure}`);
    }
    this.logger.info(`PASS ${name} (${record.ms}ms)`);
    return outcome.value;
  }

  async sendReload(server: string): Promise<void> {
    if (this.current === null) throw new StepFailure("sendReload runs only inside a step, which owns the reload's expected problems");
    this.problemsExpectedInStep.push(PaperProblems.reloadCommandTreeRace);
    await this.env.console(server).send(ConsoleCommands.reload);
  }

  check(name: string, condition: boolean, evidence?: unknown): boolean {
    const record: CheckRecord = { name, ok: condition, ...(evidence === undefined ? {} : { evidence }) };
    if (this.current) this.current.checks.push(record);
    else this.report.notes.push(`${condition ? "ok" : "FAILED"}: ${name}`);
    if (!condition) this.logger.warn(`check failed: ${name} ${truncate(JSON.stringify(evidence ?? null), 400)}`);
    return condition;
  }

  expectMatch(name: string, actual: string | readonly string[], pattern: RegExp): boolean {
    const text = typeof actual === "string" ? actual : actual.join("\n");
    return this.check(name, pattern.test(text), { expected: String(pattern), actual: truncate(text, 1500) });
  }

  require(condition: unknown, message: string): asserts condition {
    const holds = Boolean(condition);
    if (!holds) throw new StepFailure(message);
  }

  fail(message: string): never {
    throw new StepFailure(message);
  }

  evidence(label: string, data: unknown): void {
    if (this.current) this.current.evidence.push({ label, data });
    else this.report.notes.push(`${label}: ${truncate(JSON.stringify(data), 600)}`);
  }

  note(message: string): void {
    this.report.notes.push(message);
    this.logger.info(`note: ${message}`);
  }

  async bot(username: string): Promise<TestBot> {
    return this.pool.join(username);
  }

  newBot(username: string): TestBot {
    const bot = this.pool.create(username);
    this.strays.add(bot);
    return bot;
  }

  quitBot(username: string): void {
    this.pool.quit(username);
  }

  quitAllBots(): void {
    this.pool.quitAll();
    for (const bot of this.strays) bot.quit();
    this.strays.clear();
  }

  async connectPendingServers(name: string): Promise<void> {
    if (!this.env.state.connectPending) return;
    await this.step(name, async () => this.env.connectServers(), { timeoutMs: settings().bootTimeoutMs + CONNECT_MARGIN_MS });
  }

  async consoleRun(command: string, options: ConsoleRunOptions & { server?: string } = {}): Promise<ConsoleResult> {
    const result = await this.env.console(options.server ?? this.coreServer).run(command, options);
    this.evidence(`console ${result.server}: ${command}`, { matched: result.matched, lines: result.lines.slice(-12) });
    return result;
  }

  async consoleExpect(command: string, expect: RegExp, options: { server?: string; timeoutMs?: number } = {}): Promise<ConsoleResult> {
    const result = await this.consoleRun(command, { ...options, expect, timeoutMs: options.timeoutMs ?? 30_000 });
    this.require(result.matched !== null, `console '${command}' never printed ${expect.toString()}`);
    return result;
  }

  async restart(server: string): Promise<LifecycleReport> {
    this.quitAllBots();
    return this.env.restart(server);
  }

  async kill(server: string): Promise<{ server: string; exitCode: number | null }> {
    this.quitAllBots();
    return this.env.kill(server);
  }

  async start(server: string): Promise<LifecycleReport> {
    return this.env.start(server);
  }
}

export interface RunResult {
  report: ScenarioReport;
  ok: boolean;
}

function reportFile(report: ScenarioReport): string {
  const stamp = report.startedAt.replace(/[:.]/g, "-");
  return join(REPORTS_DIR, "scenarios", report.topology, `${report.scenario}--${report.line}--${stamp}.json`);
}

export async function runScenario(scenario: Scenario, env: Environment, logger: Logger): Promise<RunResult> {
  const report: ScenarioReport = {
    scenario: scenario.name,
    description: scenario.description,
    topology: env.state.topology,
    line: env.state.options.line,
    startedAt: new Date().toISOString(),
    status: "passed",
    steps: [],
    notes: [],
  };
  const skipReason = notApplicableReason(scenario, env.state.options) ?? (await setupPhaseMismatch(scenario, env));
  if (skipReason !== null) {
    report.status = "skipped";
    report.reason = skipReason;
    return { report, ok: true };
  }
  logger.info(`=== ${scenario.name} (${env.state.topology}, ${env.state.options.line} line): ${scenario.description}`);
  const context = new ScenarioContext(env, logger, report);
  try {
    if (!scenario.setupPhase) await context.connectPendingServers("the servers waiting for the API key are connected");
    await scenario.run(context);
  } catch (error) {
    report.status = "failed";
    report.reason = errorMessage(error);
    if (!(error instanceof StepFailure)) report.notes.push(`aborted: ${errorDetail(error)}`);
  } finally {
    context.quitAllBots();
  }
  if (report.steps.length === 0 && report.status === "passed") {
    report.status = "failed";
    report.reason = "scenario ran no steps";
  }
  report.finishedAt = new Date().toISOString();
  report.file = reportFile(report);
  writeJson(report.file, report);
  logger.info(`=== ${report.status.toUpperCase()} ${scenario.name}${report.reason ? `: ${report.reason}` : ""}`);
  return { report, ok: report.status !== "failed" };
}
