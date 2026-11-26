import { spawn, type ChildProcess } from "node:child_process";
import { createWriteStream, mkdirSync } from "node:fs";
import { join, relative } from "node:path";
import { z } from "zod";
import type { Logger } from "./lib/log";
import { HARNESS_ROOT, REPORTS_DIR } from "./lib/paths";
import { writeJson, writeText } from "./lib/util";

export const MATRIX_TIERS = ["quick", "full", "ci"] as const;
export type MatrixTier = (typeof MATRIX_TIERS)[number];

export function isMatrixTier(value: string): value is MatrixTier {
  return MATRIX_TIERS.some((tier) => tier === value);
}

type LaneName = "main" | "network";

interface MatrixJob {
  id: string;
  lane: LaneName;
  topology: string;
  line: string;
  scenarios: string[];
  flags: string[];
}

const QUICK_STANDALONE = ["smoke", "ban-kick", "mute-chat", "gui", "report", "alts", "panel-link-login", "console-allowlist", "reload-twice"];
const QUICK_NETWORK = ["network-members", "member-key-scope", "wrong-member-key"];
const CURRENT_LINES = ["latest", "1.21", "folia"] as const;

function plannedJob(lane: LaneName, topology: string, line: string, scenarios: string[], flags: string[] = []): MatrixJob {
  const suffix = flags.length === 0 ? "" : `-${flags.filter((flag) => flag.startsWith("--")).map((flag) => flag.slice(2)).join("-")}`;
  const which = scenarios.length === 1 && scenarios[0] !== "all" ? `-${scenarios[0] ?? ""}` : "";
  return { id: `${topology}-${line}${which}${suffix}`, lane, topology, line, scenarios, flags };
}

function fullJobs(): MatrixJob[] {
  const jobs: MatrixJob[] = [];
  for (const line of CURRENT_LINES) {
    jobs.push(plannedJob("network", "network", line, ["all"], ["--defer-setup"]));
    jobs.push(plannedJob("network", "network-bungee", line, ["all"], ["--defer-setup"]));
    jobs.push(plannedJob("main", "standalone", line, ["all"], ["--defer-setup"]));
    jobs.push(plannedJob("main", "remote", line, ["all"], ["--defer-setup"]));
    jobs.push(plannedJob("main", "remote", line, ["all"], ["--backend", "compose", "--defer-setup"]));
    for (const topology of ["standalone", "network", "remote"]) {
      const lane: LaneName = topology === "network" ? "network" : "main";
      jobs.push(plannedJob(lane, topology, line, ["setup-no-console"], ["--defer-setup"]));
      jobs.push(plannedJob(lane, topology, line, ["setup-config-claim"], ["--defer-setup", "--admin-email", "owner@modl.test"]));
    }
  }
  jobs.push(plannedJob("network", "network-bungee", "legacy", ["all"]));
  jobs.push(plannedJob("main", "remote", "legacy", ["all"]));
  jobs.push(plannedJob("main", "standalone", "latest", ["smoke"], ["--java", "27"]));
  return jobs;
}

export function matrixJobs(tier: MatrixTier): MatrixJob[] {
  if (tier === "full") return fullJobs();
  if (tier === "ci") return [plannedJob("main", "standalone", "latest", QUICK_STANDALONE)];
  return [plannedJob("main", "standalone", "latest", QUICK_STANDALONE), plannedJob("network", "network", "latest", QUICK_NETWORK)];
}

const RESULT = z.object({
  scenario: z.string(),
  status: z.string(),
  reason: z.string().optional(),
  report: z.string().optional(),
  firstAttempt: z.object({ reason: z.string().optional(), report: z.string().optional() }).optional(),
});
const SUMMARY = z.object({ passed: z.number(), failed: z.number(), skipped: z.number(), flaky: z.number().default(0), results: z.array(RESULT) });

type JobSummary = z.infer<typeof SUMMARY>;

interface JobOutcome {
  job: MatrixJob;
  seconds: number;
  exitCode: number;
  summary: JobSummary | null;
  log: string;
}

const JOB_TIMEOUT_MS = 60 * 60_000;
const KILL_GRACE_MS = 120_000;
const running = new Set<ChildProcess>();

function parseSummary(stdout: string): JobSummary | null {
  const start = stdout.startsWith("{") ? 0 : stdout.lastIndexOf("\n{") + 1;
  if (start < 0 || stdout.charAt(start) !== "{") return null;
  try {
    const raw: unknown = JSON.parse(stdout.slice(start));
    const parsed = SUMMARY.safeParse(raw);
    return parsed.success ? parsed.data : null;
  } catch {
    return null;
  }
}

function forwardSignals(): () => void {
  const forward = (signal: NodeJS.Signals) => {
    for (const child of running) child.kill(signal);
  };
  const onInterrupt = () => {
    forward("SIGINT");
  };
  const onTerminate = () => {
    forward("SIGTERM");
  };
  process.on("SIGINT", onInterrupt);
  process.on("SIGTERM", onTerminate);
  return () => {
    process.off("SIGINT", onInterrupt);
    process.off("SIGTERM", onTerminate);
  };
}

async function runJob(job: MatrixJob, outDir: string, logger: Logger): Promise<JobOutcome> {
  const log = join(outDir, `${job.id}.log`);
  const args = ["tsx", "src/cli.ts", "scenario", ...job.scenarios, "--up", job.topology, "--line", job.line, "--fresh", "--down", "--retry-failed", ...job.flags];
  const started = Date.now();
  logger.info(`[${job.lane}] ${job.id}: starting`);
  const stream = createWriteStream(log);
  let stdout = "";
  const exitCode = await new Promise<number>((resolve) => {
    const child = spawn("npx", args, { cwd: HARNESS_ROOT, env: { ...process.env, MODL_TEST_LANE: job.lane }, stdio: ["ignore", "pipe", "pipe"] });
    running.add(child);
    const interrupt = setTimeout(() => {
      stream.write(`\n[matrix] ${job.id} ran past ${String(JOB_TIMEOUT_MS / 60_000)} min; interrupting it\n`);
      child.kill("SIGINT");
    }, JOB_TIMEOUT_MS);
    const kill = setTimeout(() => child.kill("SIGKILL"), JOB_TIMEOUT_MS + KILL_GRACE_MS);
    child.stdout.on("data", (chunk: Buffer) => {
      stdout += chunk.toString("utf8");
      stream.write(chunk);
    });
    child.stderr.pipe(stream, { end: false });
    const finish = (code: number) => {
      clearTimeout(interrupt);
      clearTimeout(kill);
      running.delete(child);
      resolve(code);
    };
    child.on("error", (error) => {
      stream.write(`\n[matrix] ${job.id} could not start: ${error.message}\n`);
      finish(1);
    });
    child.on("close", (code) => {
      finish(code ?? 1);
    });
  });
  stream.end();
  const seconds = Math.round((Date.now() - started) / 1000);
  const summary = parseSummary(stdout);
  logger.info(`[${job.lane}] ${job.id}: ${summary ? `${String(summary.passed)} passed, ${String(summary.failed)} failed, ${String(summary.flaky)} flaky` : `exit ${String(exitCode)}`} in ${String(seconds)} s`);
  return { job, seconds, exitCode, summary, log };
}

function link(path: string | undefined): string {
  return path === undefined ? "" : ` ([report](${relative(REPORTS_DIR, path)}))`;
}

function markdown(tier: MatrixTier, outcomes: readonly JobOutcome[], seconds: number): string {
  const rows = outcomes.map((outcome) => {
    const summary = outcome.summary;
    const counts = summary ? `${String(summary.passed)} | ${String(summary.failed)} | ${String(summary.flaky)} | ${String(summary.skipped)}` : `- | crashed (exit ${String(outcome.exitCode)}) | - | -`;
    return `| ${outcome.job.id} | ${counts} | ${String(outcome.seconds)} s |`;
  });
  const failures = outcomes.flatMap((outcome) =>
    (outcome.summary?.results ?? []).filter((result) => result.status === "failed").map((result) => {
      const first = result.firstAttempt ? `; first attempt: ${result.firstAttempt.reason ?? "failed"}${link(result.firstAttempt.report)}` : "";
      return `- **${outcome.job.id} / ${result.scenario}**: ${result.reason ?? "no reason"}${link(result.report)}${first}`;
    }),
  );
  const crashed = outcomes.filter((outcome) => outcome.summary === null).map((outcome) => `- **${outcome.job.id}** did not report a summary; see [its log](${relative(REPORTS_DIR, outcome.log)})`);
  const flaky = outcomes.flatMap((outcome) =>
    (outcome.summary?.results ?? []).filter((result) => result.firstAttempt !== undefined && result.status === "passed").map((result) => `- ${outcome.job.id} / ${result.scenario}: first attempt ${result.firstAttempt?.reason ?? "failed"}${link(result.firstAttempt?.report)}`),
  );
  return [
    `# Scenario matrix (${tier})`,
    "",
    `${String(outcomes.length)} jobs in ${String(Math.round(seconds / 60))} min.`,
    "",
    "| Job | Passed | Failed | Flaky | Skipped | Time |",
    "|---|---|---|---|---|---|",
    ...rows,
    "",
    ...(failures.length + crashed.length > 0 ? ["## Failures", "", ...crashed, ...failures, ""] : []),
    ...(flaky.length > 0 ? ["## Flaky (passed on the second attempt)", "", ...flaky, ""] : []),
  ].join("\n");
}

export interface MatrixReport {
  ok: boolean;
  summary: string;
  jobs: number;
  failed: number;
  flaky: number;
}

export async function runMatrix(tier: MatrixTier, lanes: number, logger: Logger): Promise<MatrixReport> {
  const outDir = join(REPORTS_DIR, "matrix", new Date().toISOString().replace(/[:.]/g, "-"));
  mkdirSync(outDir, { recursive: true });
  const jobs = matrixJobs(tier);
  const queues = new Map<LaneName, MatrixJob[]>();
  for (const entry of jobs) {
    const lane: LaneName = lanes <= 1 ? "main" : entry.lane;
    queues.set(lane, [...(queues.get(lane) ?? []), { ...entry, lane }]);
  }
  const started = Date.now();
  const stopForwarding = forwardSignals();
  const outcomes = (
    await Promise.all(
      [...queues.values()].map(async (queue) => {
        const done: JobOutcome[] = [];
        for (const entry of queue) done.push(await runJob(entry, outDir, logger));
        return done;
      }),
    )
  ).flat();
  stopForwarding();
  const ordered = jobs.map((entry) => outcomes.find((outcome) => outcome.job.id === entry.id)).filter((outcome) => outcome !== undefined);
  const summaryFile = join(outDir, "summary.md");
  writeText(summaryFile, markdown(tier, ordered, (Date.now() - started) / 1000));
  writeJson(join(outDir, "summary.json"), ordered.map((outcome) => ({ job: outcome.job, seconds: outcome.seconds, exitCode: outcome.exitCode, summary: outcome.summary })));
  const failed = ordered.filter((outcome) => outcome.summary === null || outcome.summary.failed > 0).length;
  const flaky = ordered.reduce((total, outcome) => total + (outcome.summary?.flaky ?? 0), 0);
  if (process.env["GITHUB_ACTIONS"] === "true") {
    for (const outcome of ordered) {
      for (const result of outcome.summary?.results ?? []) {
        if (result.status === "passed" && result.firstAttempt) process.stdout.write(`::warning title=Flaky scenario::${outcome.job.id} / ${result.scenario} passed only on its second attempt: ${(result.firstAttempt.reason ?? "").replace(/\n/g, " ")}\n`);
      }
    }
  }
  return { ok: failed === 0, summary: summaryFile, jobs: ordered.length, failed, flaky };
}
