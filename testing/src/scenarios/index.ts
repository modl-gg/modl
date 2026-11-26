import { cancellable, cancellationError, throwIfCancelled } from "../lib/cancellation";
import { Environment } from "../lib/environment";
import { upOptions, type UpRequest } from "../lib/inspect";
import { down, up } from "../lib/lifecycle";
import type { Logger } from "../lib/log";
import { lineSummary, lineTopologies, LINES, type LineSummary } from "../lib/lines";
import { LINE_NAMES, readState, type LineName, type TopologyName, type UpOptions } from "../lib/state";
import { aiChatReport } from "./ai-chat-report";
import { alts } from "./alts";
import { passwordLogin, passwordRestart, passwordStaff, secondFactorRequired, setupPasswordClaim, totpLogin } from "./auth";
import { banKick } from "./ban-kick";
import { cloudflareTunnel } from "./cloudflare-tunnel";
import { consoleAllowlist } from "./console-allowlist";
import { databaseMove } from "./database-move";
import { embeddedDownload, embeddedDropIn, embeddedTamperedArchive } from "./embedded-archive";
import { notApplicableReason, runScenario, type Scenario, type ScenarioReport } from "./framework";
import { gui } from "./gui";
import { hostedImport } from "./hosted-import";
import { litebansImport } from "./litebans-import";
import {
  advancedbanImport,
  advancedbanMariaDbImport,
  banmanagerImport,
  banmanagerStoppedImport,
  bansystemImport,
  bansystemStoppedImport,
  libertybansImport,
  libertybansStoppedImport,
  vanillaImport,
} from "./plugin-import";
import { muteChat } from "./mute-chat";
import { embeddedNeedsJava21, missingPacketEvents, panelPortInUse, wrongMemberApiKey, wrongRemoteApiKey } from "./negative";
import { memberKeyScope } from "./member-key-scope";
import { memberReloadResyncsFreeze } from "./member-reload-resyncs-freeze";
import { networkMembers } from "./network-members";
import { noBridgePort } from "./no-bridge-port";
import { panelLinkLogin } from "./panel-link-login";
import { crashRecovery, restartPersistence } from "./persistence";
import { reloadTwice } from "./reload-twice";
import { remoteLateBackend } from "./remote-late-backend";
import { replay } from "./replay";
import { replayLoad } from "./replay-load";
import { report } from "./report";
import { setupConfigClaim, setupNoConsole, setupWizard } from "./setup";
import { smoke } from "./smoke";
import { updateCheck, updateDocker } from "./update-check";
import { updateEmbedded, updateEmbeddedRollback } from "./update-embedded";
import { updateBackupFailure, updateJar, updateJarInterrupted, updateJarRollback, updateManualGuard } from "./update-jar";
import { memberVersionMismatch, versionMismatch } from "./version-mismatch";

export const SCENARIOS: readonly Scenario[] = [
  setupWizard,
  setupNoConsole,
  setupConfigClaim,
  setupPasswordClaim,
  hostedImport,
  smoke,
  panelLinkLogin,
  passwordLogin,
  passwordStaff,
  passwordRestart,
  totpLogin,
  secondFactorRequired,
  banKick,
  muteChat,
  gui,
  report,
  aiChatReport,
  alts,
  replay,
  replayLoad,
  networkMembers,
  memberReloadResyncsFreeze,
  memberKeyScope,
  noBridgePort,
  remoteLateBackend,
  litebansImport,
  advancedbanImport,
  libertybansImport,
  banmanagerImport,
  bansystemImport,
  vanillaImport,
  libertybansStoppedImport,
  banmanagerStoppedImport,
  bansystemStoppedImport,
  advancedbanMariaDbImport,
  updateCheck,
  updateJar,
  updateJarRollback,
  updateJarInterrupted,
  updateBackupFailure,
  updateManualGuard,
  updateEmbedded,
  updateEmbeddedRollback,
  versionMismatch,
  memberVersionMismatch,
  updateDocker,
  consoleAllowlist,
  reloadTwice,
  restartPersistence,
  crashRecovery,
  databaseMove,
  embeddedDropIn,
  embeddedTamperedArchive,
  embeddedDownload,
  cloudflareTunnel,
  missingPacketEvents,
  panelPortInUse,
  wrongMemberApiKey,
  wrongRemoteApiKey,
  embeddedNeedsJava21,
];

export const SUITE_ALL = "all";

export function scenarioByName(name: string): Scenario {
  const scenario = SCENARIOS.find((candidate) => candidate.name === name);
  if (!scenario) throw new Error(`unknown scenario '${name}' (scenarios: ${SCENARIOS.map((entry) => entry.name).join(", ")}, or ${SUITE_ALL})`);
  return scenario;
}

export function resolveScenarios(names: readonly string[], options: UpOptions): Scenario[] {
  if (names.includes(SUITE_ALL)) return SCENARIOS.filter((scenario) => notApplicableReason(scenario, options) === null);
  return names.map(scenarioByName);
}

export function suiteAutoUp(topology: string | undefined, request: Omit<UpRequest, "topology">): UpOptions | null {
  if (topology !== undefined) return upOptions({ ...request, topology });
  if (request.line !== undefined || request.java !== undefined) throw new Error("--line and --java choose what --up boots; add --up <topology>");
  return null;
}

export interface SuiteOptions {
  autoUp: UpOptions | null;
  downAfter: boolean;
  retryFailed?: boolean;
  signal?: AbortSignal;
}

export interface SuiteSummary {
  topology: TopologyName | null;
  line: LineName | null;
  interrupted: string | null;
  passed: number;
  failed: number;
  skipped: number;
  ok: boolean;
  flaky: number;
  results: SuiteResult[];
}

export interface SuiteResult {
  scenario: string;
  status: ScenarioReport["status"];
  reason?: string | undefined;
  report?: string | undefined;
  firstAttempt?: { reason?: string | undefined; report?: string | undefined };
}

function sameEnvironment(current: UpOptions, wanted: UpOptions): boolean {
  return (
    current.topology === wanted.topology &&
    current.presetAdminEmail === wanted.presetAdminEmail &&
    current.remoteBackend === wanted.remoteBackend &&
    current.proxyPacketEvents === wanted.proxyPacketEvents &&
    current.line === wanted.line &&
    current.java === wanted.java &&
    current.updateFixture === wanted.updateFixture
  );
}

async function prepareEnvironment(wanted: UpOptions | null, logger: Logger): Promise<Environment> {
  if (wanted) {
    const current = readState();
    if (current && current.topology !== wanted.topology) await down({ keepData: false }, logger);
    const reusable = current !== null && !wanted.fresh && sameEnvironment(current.options, wanted);
    if (!reusable) await up(wanted, logger);
  }
  return Environment.current(logger);
}

interface SuiteTarget {
  topology: TopologyName;
  line: LineName;
}

async function runScenarios(names: readonly string[], options: SuiteOptions, logger: Logger, results: SuiteSummary["results"]): Promise<SuiteTarget> {
  const environment = await prepareEnvironment(options.autoUp, logger);
  for (const scenario of resolveScenarios(names, environment.state.options)) {
    throwIfCancelled();
    const { report: outcome } = await runScenario(scenario, environment, logger);
    if (outcome.status !== "failed" || options.retryFailed !== true) {
      results.push({ scenario: outcome.scenario, status: outcome.status, reason: outcome.reason, report: outcome.file });
      continue;
    }
    logger.warn(`${scenario.name} failed (${outcome.reason ?? "no reason"}); running it once more`);
    throwIfCancelled();
    const { report: retry } = await runScenario(scenario, environment, logger);
    results.push({
      scenario: retry.scenario,
      status: retry.status,
      reason: retry.reason,
      report: retry.file,
      firstAttempt: { reason: outcome.reason, report: outcome.file },
    });
  }
  return { topology: environment.state.topology, line: environment.state.options.line };
}

export async function runSuite(names: readonly string[], options: SuiteOptions, logger: Logger): Promise<SuiteSummary> {
  const results: SuiteSummary["results"] = [];
  const run = async () => runScenarios(names, options, logger, results);
  const current = readState();
  let target: SuiteTarget | null = options.autoUp ?? (current ? { topology: current.topology, line: current.options.line } : null);
  let interrupted: string | null = null;
  try {
    target = await (options.signal ? cancellable(options.signal, run) : run());
  } catch (error) {
    if (!options.signal?.aborted) throw error;
    interrupted = cancellationError(options.signal).message;
  } finally {
    if (options.downAfter) {
      const teardown = await down({ keepData: false }, logger);
      results.push({ scenario: "teardown", status: teardown.clean ? "passed" : "failed", reason: teardown.clean ? undefined : JSON.stringify(teardown.leftovers) });
    }
  }
  const count = (status: ScenarioReport["status"]) => results.filter((result) => result.status === status).length;
  return {
    topology: target?.topology ?? null,
    line: target?.line ?? null,
    interrupted,
    passed: count("passed"),
    failed: count("failed"),
    skipped: count("skipped"),
    flaky: results.filter((result) => result.status === "passed" && result.firstAttempt !== undefined).length,
    ok: interrupted === null && count("failed") === 0,
    results,
  };
}

export interface ScenarioMatrixEntry extends LineSummary {
  scenarios: Partial<Record<TopologyName, string[]>>;
  notApplicable: Partial<Record<TopologyName, Record<string, string>>>;
}

function notApplicableOn(options: UpOptions): Record<string, string> {
  const reasons: Record<string, string> = {};
  for (const scenario of SCENARIOS) {
    if (!scenario.topologies.includes(options.topology)) continue;
    const reason = notApplicableReason(scenario, options);
    if (reason !== null) reasons[scenario.name] = reason;
  }
  return reasons;
}

export function scenarioMatrix(): ScenarioMatrixEntry[] {
  return Object.values(LINES).map((line) => ({
    ...lineSummary(line),
    scenarios: Object.fromEntries(
      lineTopologies(line).map((topology) => [
        topology,
        resolveScenarios([SUITE_ALL], upOptions({ topology, line: line.name })).map((scenario) => scenario.name),
      ]),
    ),
    notApplicable: Object.fromEntries(
      lineTopologies(line).map((topology) => [topology, notApplicableOn(upOptions({ topology, line: line.name }))]),
    ),
  }));
}

export function scenarioCatalog(): { name: string; topologies: readonly TopologyName[]; lines: readonly LineName[]; description: string }[] {
  return SCENARIOS.map((scenario) => ({
    name: scenario.name,
    topologies: scenario.topologies,
    lines: scenario.lines ?? LINE_NAMES,
    description: scenario.description,
  }));
}
