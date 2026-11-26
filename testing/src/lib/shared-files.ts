import { readdirSync, writeFileSync } from "node:fs";
import { basename, dirname, join } from "node:path";
import { ensureJdkImage, runInJdkContainer } from "./jdk-container";
import { errorMessage, replaceFile, sha256OfFile, sleep, stageAndRename } from "./util";

const MOUNT = "/shared";
const VISIBLE_TIMEOUT_MS = 30_000;
const RECHECK_MS = 500;
const MIN_HASH_TIMEOUT_MS = 5_000;
const HASH_LINE = /^([0-9a-f]{64})\s+\*?(.+)$/u;

async function hashesSeenByContainers(directory: string, files: readonly string[], timeoutMs: number): Promise<Map<string, string>> {
  const result = await runInJdkContainer({
    workdir: MOUNT,
    volumes: [`${directory}:${MOUNT}:ro`],
    command: ["sha256sum", ...files],
    timeoutMs,
  });
  const seen = new Map<string, string>();
  for (const line of result.stdout.split("\n")) {
    const match = HASH_LINE.exec(line.trim());
    if (match?.[1] !== undefined && match[2] !== undefined) seen.set(match[2], match[1]);
  }
  return seen;
}

export async function awaitVisibleToContainers(directory: string, files: readonly string[]): Promise<void> {
  if (files.length === 0) return;
  await ensureJdkImage();
  const expected = new Map<string, string>();
  for (const file of files) expected.set(file, await sha256OfFile(join(directory, file)));
  const started = Date.now();
  let last = "no answer";
  while (Date.now() - started + MIN_HASH_TIMEOUT_MS <= VISIBLE_TIMEOUT_MS) {
    try {
      const seen = await hashesSeenByContainers(directory, files, VISIBLE_TIMEOUT_MS - (Date.now() - started));
      const stale = files.filter((file) => seen.get(file) !== expected.get(file));
      if (stale.length === 0) return;
      last = `older copies of ${stale.join(", ")}`;
    } catch (error) {
      last = errorMessage(error);
    }
    await sleep(RECHECK_MS);
  }
  throw new Error(`containers do not see the new files in ${directory} after ${Date.now() - started} ms (${last})`);
}

export async function replaceSharedFiles(directory: string, files: readonly (readonly [source: string, name: string])[]): Promise<void> {
  for (const [source, name] of files) replaceFile(source, join(directory, name));
  await awaitVisibleToContainers(directory, files.map(([, name]) => name));
}

export async function replaceSharedFile(source: string, target: string): Promise<void> {
  await replaceSharedFiles(dirname(target), [[source, basename(target)]]);
}

export async function writeSharedFile(target: string, content: Buffer): Promise<void> {
  stageAndRename(target, (staged) => {
    writeFileSync(staged, content);
  });
  await awaitVisibleToContainers(dirname(target), [basename(target)]);
}

const CONTENT_FILE = /(?:\.jar|\.zip|(?:^|\/)database\/modl\.db)$/u;

export function contentFilesUnder(directory: string): string[] {
  return readdirSync(directory, { recursive: true, encoding: "utf8" })
    .filter((file) => CONTENT_FILE.test(file))
    .sort();
}
