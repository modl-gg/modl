import { auditConsole, type ConsoleAudit, type ConsoleProblem } from "./console-policy";
import type { Environment } from "./environment";
import { JAVA_FEATURE_VERSION } from "./java-runtime";
import { LINES, type PlatformLine } from "./lines";
import { entryText } from "./log-entries";
import { LOG_START, loadMarks, saveMarks, type LogMark } from "./logs";
import { isLineName, isRemoteBackendKind, isTopologyName, LINE_NAMES, REMOTE_BACKEND_KINDS, TOPOLOGY_NAMES, type TopologyName, type UpOptions } from "./state";

export interface LogQuery {
  server: string;
  tail?: number | undefined;
  grep?: string | undefined;
  sinceMark?: boolean | undefined;
  level?: "warn" | "error" | undefined;
}

const WARN_OR_WORSE = new Set(["WARN", "ERROR", "FATAL"]);
const ERROR_OR_WORSE = new Set(["ERROR", "FATAL"]);

function sinceFor(server: string, sinceMark: boolean | undefined): LogMark {
  return sinceMark ? (loadMarks()[server] ?? LOG_START) : LOG_START;
}

export async function queryLogs(environment: Environment, query: LogQuery): Promise<string[]> {
  let entries = await environment.entries(query.server, sinceFor(query.server, query.sinceMark));
  if (query.level) {
    const accepted = query.level === "error" ? ERROR_OR_WORSE : WARN_OR_WORSE;
    entries = entries.filter((entry) => accepted.has(entry.level));
  }
  if (query.grep) {
    const pattern = new RegExp(query.grep, "i");
    entries = entries.filter((entry) => pattern.test(entryText(entry)));
  }
  return entries.flatMap((entry) => entry.lines).slice(-(query.tail ?? 80));
}

export async function markLogs(environment: Environment): Promise<Record<string, LogMark>> {
  const marks = await environment.markAll();
  saveMarks(marks);
  return marks;
}

function selected(environment: Environment, servers: readonly string[]): string[] {
  return servers.length > 0 ? [...servers] : environment.serverNames();
}

export async function consoleErrors(environment: Environment, servers: readonly string[], sinceMark: boolean): Promise<ConsoleProblem[]> {
  const names = selected(environment, servers);
  const since = Object.fromEntries(names.map((name) => [name, sinceFor(name, sinceMark)]));
  return (await environment.problems(since)).filter((problem) => names.includes(problem.server));
}

export async function consoleAudits(environment: Environment, servers: readonly string[], sinceMark: boolean): Promise<Record<string, ConsoleAudit>> {
  const names = selected(environment, servers);
  const leaks = await environment.leakIndex();
  const audits = await Promise.all(
    names.map(async (name) => [name, auditConsole(name, await environment.entries(name, sinceFor(name, sinceMark)), leaks)] as const),
  );
  return Object.fromEntries(audits);
}

export interface UpRequest {
  topology: string;
  fresh?: boolean | undefined;
  adminEmail?: string | null | undefined;
  backend?: string | undefined;
  proxyPacketEvents?: boolean | undefined;
  line?: string | undefined;
  java?: string | undefined;
  deferSetup?: boolean | undefined;
  updateFixture?: boolean | undefined;
  realClient?: boolean | undefined;
}

const DEFAULT_LINE = "latest";

function platformLine(name: string): PlatformLine {
  if (!isLineName(name)) throw new Error(`unknown line '${name}' (${LINE_NAMES.join(", ")})`);
  return LINES[name];
}

function requireTopologyOnLine(line: PlatformLine, topology: TopologyName): void {
  const reason = line.unavailable[topology];
  if (reason !== undefined) throw new Error(`the ${line.name} line has no ${topology} topology: ${reason}`);
}

function javaOverride(line: PlatformLine, version: string | undefined): number | null {
  if (version === undefined) return null;
  if (!JAVA_FEATURE_VERSION.test(version)) throw new Error(`--java takes a Java feature version such as ${line.paper.java} or 27, got '${version}'`);
  const java = Number(version);
  if (java < line.paper.java) throw new Error(`${line.paper.software} ${line.paper.version} on the ${line.name} line needs Java ${line.paper.java} or newer, got --java ${version}`);
  return java;
}

export function upOptions(request: UpRequest): UpOptions {
  if (!isTopologyName(request.topology)) throw new Error(`unknown topology '${request.topology}' (${TOPOLOGY_NAMES.join(", ")})`);
  const backend = request.backend ?? "jar";
  if (!isRemoteBackendKind(backend)) throw new Error(`unknown remote backend '${backend}' (${REMOTE_BACKEND_KINDS.join(", ")})`);
  const line = platformLine(request.line ?? DEFAULT_LINE);
  requireTopologyOnLine(line, request.topology);
  return {
    topology: request.topology,
    fresh: request.fresh ?? false,
    presetAdminEmail: request.adminEmail ?? null,
    remoteBackend: backend,
    proxyPacketEvents: request.proxyPacketEvents ?? true,
    line: line.name,
    java: javaOverride(line, request.java),
    deferSetup: request.deferSetup ?? false,
    updateFixture: request.updateFixture ?? false,
    realClient: request.realClient ?? false,
  };
}
