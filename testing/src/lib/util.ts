import { createHash, randomBytes } from "node:crypto";
import { copyFileSync, createReadStream, existsSync, mkdirSync, readFileSync, renameSync, rmSync, writeFileSync } from "node:fs";
import { dirname } from "node:path";
import { pipeline } from "node:stream/promises";
import type { z } from "zod";
import { cancellableDelay } from "./cancellation";

export type Awaitable<T> = T | Promise<T>;

export async function sleep(ms: number): Promise<void> {
  return cancellableDelay(ms);
}

const LEGACY_FORMATTING = /§[0-9a-fk-orx]/gi;
const ESCAPE = String.fromCharCode(0x1b);
const ANSI_ESCAPE = new RegExp(`${ESCAPE}\\[[0-9;?]*[A-Za-z]`, "g");

export function plainText(value: string): string {
  return value.replace(ANSI_ESCAPE, "").replace(LEGACY_FORMATTING, "");
}

export function readJson<T>(file: string, schema: z.ZodType<T>, fallback: T): T {
  if (!existsSync(file)) return fallback;
  try {
    const parsed = schema.safeParse(JSON.parse(readFileSync(file, "utf8")));
    return parsed.success ? parsed.data : fallback;
  } catch {
    return fallback;
  }
}

export function errnoCode(error: unknown): string | undefined {
  return error instanceof Error && "code" in error && typeof error.code === "string" ? error.code : undefined;
}

export function readTextFile(file: string): string | null {
  try {
    return readFileSync(file, "utf8");
  } catch (error) {
    if (errnoCode(error) === "ENOENT") return null;
    throw error;
  }
}

export function writeJson(file: string, data: unknown): void {
  mkdirSync(dirname(file), { recursive: true });
  const temporary = `${file}.${process.pid}.tmp`;
  writeFileSync(temporary, `${JSON.stringify(data, null, 2)}\n`, { mode: 0o600 });
  renameSync(temporary, file);
}

export function writeText(file: string, content: string): void {
  mkdirSync(dirname(file), { recursive: true });
  writeFileSync(file, content);
}

export function stageAndRename(target: string, write: (staged: string) => void): void {
  const staged = `${target}.${randomSecret(6)}.tmp`;
  try {
    write(staged);
    renameSync(staged, target);
  } catch (error) {
    rmSync(staged, { force: true });
    throw error;
  }
}

export function replaceFile(source: string, target: string): void {
  stageAndRename(target, (staged) => {
    copyFileSync(source, staged);
  });
}

export function removePath(path: string): void {
  rmSync(path, { recursive: true, force: true });
}

export async function sha256OfFile(file: string): Promise<string> {
  const hash = createHash("sha256");
  await pipeline(createReadStream(file), hash);
  return hash.digest("hex");
}

export function randomSecret(bytes = 24): string {
  return randomBytes(bytes).toString("hex");
}

export function offlineUuid(username: string): string {
  const digest = createHash("md5").update(`OfflinePlayer:${username}`, "utf8").digest();
  digest.writeUInt8((digest.readUInt8(6) & 0x0f) | 0x30, 6);
  digest.writeUInt8((digest.readUInt8(8) & 0x3f) | 0x80, 8);
  const hex = digest.toString("hex");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

export function normalizeUuid(uuid: string): string {
  return uuid.replace(/-/g, "").toLowerCase();
}

let nameCounter = 0;

export function uniquePlayerName(prefix: string): string {
  nameCounter += 1;
  const name = `${prefix}${Date.now().toString(36).slice(-5)}${nameCounter.toString(36)}`;
  if (name.length > 16) throw new Error(`player name ${name} exceeds 16 characters; shorten the prefix "${prefix}"`);
  return name;
}

export function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

export function errorDetail(error: unknown): string {
  return error instanceof Error ? (error.stack ?? error.message) : String(error);
}

export function truncate(value: string, max = 600): string {
  return value.length > max ? `${value.slice(0, max)}...` : value;
}

export function escapeRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}
