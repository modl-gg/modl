import { createWriteStream, existsSync, mkdirSync } from "node:fs";
import { dirname } from "node:path";
import { Readable } from "node:stream";
import { pipeline } from "node:stream/promises";
import { requestSignal } from "../../lib/cancellation";
import { settings } from "../../lib/settings";
import { sha256OfFile } from "../../lib/util";
import { writeAtomically } from "./atomic-file";

const DOWNLOAD_TIMEOUT_MS = 600_000;

export interface PinnedFile {
  url: string;
  sha256: string;
  path: string;
}

export async function verifyPinnedFile(file: PinnedFile): Promise<boolean> {
  return existsSync(file.path) && (await sha256OfFile(file.path)) === file.sha256;
}

export async function requirePinnedFile(file: PinnedFile): Promise<string> {
  if (await verifyPinnedFile(file)) return file.path;
  mkdirSync(dirname(file.path), { recursive: true });
  await writeAtomically(file.path, async (staged) => {
    const response = await fetch(file.url, { headers: { "User-Agent": settings().userAgent }, redirect: "follow", signal: requestSignal(DOWNLOAD_TIMEOUT_MS) });
    if (!response.ok || !response.body) throw new Error(`downloading ${file.url} failed with HTTP ${String(response.status)}`);
    await pipeline(Readable.fromWeb(response.body), createWriteStream(staged));
    const actual = await sha256OfFile(staged);
    if (actual !== file.sha256) throw new Error(`${file.url} has sha256 ${actual}, expected the pinned ${file.sha256}`);
  });
  return file.path;
}
