import { existsSync } from "node:fs";
import { join } from "node:path";
import { HARNESS_VERSION } from "./harness";
import type { LogLevel } from "./log";
import { HARNESS_ROOT } from "./paths";

export interface MemoryProfile {
  limit: string;
  heapMb: number;
}

export interface HarnessSettings {
  ownerEmail: string;
  ownerPlayer: string;
  serverName: string;
  botVersionOverride: string | null;
  embeddedHost: MemoryProfile;
  memberHost: MemoryProfile;
  remotePaper: MemoryProfile;
  backendHeapMb: number;
  pidsLimit: number;
  tmpSize: string;
  bootTimeoutMs: number;
  botJoinTimeoutMs: number;
  artifactSeedDir: string | null;
  pluginJarOverride: string | null;
  serverJarOverride: string | null;
  embeddedArchiveOverride: string | null;
  userAgent: string;
  logLevel: LogLevel;
}

let loaded: HarnessSettings | null = null;

function loadDotEnv(): void {
  const envFile = join(HARNESS_ROOT, ".env");
  if (existsSync(envFile)) process.loadEnvFile(envFile);
}

function optionalText(name: string): string | null {
  const value = process.env[name];
  return value !== undefined && value.trim() !== "" ? value.trim() : null;
}

function text(name: string, fallback: string): string {
  return optionalText(name) ?? fallback;
}

function logLevel(name: string): LogLevel {
  const raw = text(name, "info").toLowerCase();
  return raw === "debug" || raw === "warn" || raw === "error" ? raw : "info";
}

function integer(name: string, fallback: number): number {
  const parsed = Number(process.env[name]);
  return Number.isInteger(parsed) && parsed > 0 ? parsed : fallback;
}

export function settings(): HarnessSettings {
  if (loaded) return loaded;
  loadDotEnv();
  loaded = {
    ownerEmail: text("MODL_TEST_OWNER_EMAIL", "owner@modl.test"),
    ownerPlayer: text("MODL_TEST_OWNER_PLAYER", "ModlOwner"),
    serverName: text("MODL_TEST_SERVER_NAME", "modl test server"),
    botVersionOverride: optionalText("MODL_TEST_BOT_VERSION"),
    embeddedHost: {
      limit: text("MODL_TEST_EMBEDDED_MEMORY", "3g"),
      heapMb: integer("MODL_TEST_EMBEDDED_HEAP_MB", 2304),
    },
    memberHost: {
      limit: text("MODL_TEST_MEMBER_MEMORY", "2g"),
      heapMb: integer("MODL_TEST_MEMBER_HEAP_MB", 1280),
    },
    remotePaper: {
      limit: text("MODL_TEST_REMOTE_PAPER_MEMORY", "2g"),
      heapMb: integer("MODL_TEST_REMOTE_PAPER_HEAP_MB", 1280),
    },
    backendHeapMb: integer("MODL_TEST_BACKEND_HEAP_MB", 1024),
    pidsLimit: integer("MODL_TEST_PIDS_LIMIT", 512),
    tmpSize: text("MODL_TEST_TMP_SIZE", "100m"),
    bootTimeoutMs: integer("MODL_TEST_BOOT_TIMEOUT_MS", 300_000),
    botJoinTimeoutMs: integer("MODL_TEST_BOT_JOIN_TIMEOUT_MS", 45_000),
    artifactSeedDir: optionalText("MODL_TEST_ARTIFACT_SEED_DIR"),
    pluginJarOverride: optionalText("MODL_TEST_PLUGIN_JAR"),
    serverJarOverride: optionalText("MODL_TEST_SERVER_JAR"),
    embeddedArchiveOverride: optionalText("MODL_TEST_EMBEDDED_ARCHIVE"),
    userAgent: `modl-testing-harness/${HARNESS_VERSION}`,
    logLevel: logLevel("MODL_TEST_LOG_LEVEL"),
  };
  return loaded;
}

export function hostUser(): string {
  const uid = process.getuid?.() ?? 1000;
  const gid = process.getgid?.() ?? 1000;
  return `${uid}:${gid}`;
}
