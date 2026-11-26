import { join } from "node:path";
import { z } from "zod";
import { SOURCE_DIR } from "../lib/paths";
import { readTextFile, writeText } from "../lib/util";

const MANIFEST_FILE = join(SOURCE_DIR, "video", "manifest.json");

const NARRATION = z.strictObject({
  engine: z.string(),
  model: z.string(),
  modelSha256: z.string(),
  modelLicence: z.string(),
  voice: z.string(),
  voiceSource: z.string(),
  voiceSha256: z.string().nullable(),
  voiceLicence: z.string(),
});

const MUSIC = z.strictObject({
  title: z.string(),
  author: z.string(),
  licence: z.string(),
  source: z.string(),
  file: z.string(),
  sha256: z.string(),
});

const MANIFEST_ENTRY = z.strictObject({
  id: z.string(),
  title: z.string(),
  wikiPage: z.string().nullable(),
  modlVersion: z.string(),
  seconds: z.number(),
  bytes: z.number(),
  sha256: z.string(),
  audio: z.strictObject({ narration: NARRATION.nullable(), music: z.array(MUSIC) }).optional(),
});
const MANIFEST = z.strictObject({ videos: z.array(MANIFEST_ENTRY) });

export type ManifestEntry = z.infer<typeof MANIFEST_ENTRY>;
export type AudioCredits = NonNullable<ManifestEntry["audio"]>;

export function recordInManifest(entry: ManifestEntry): void {
  const text = readTextFile(MANIFEST_FILE);
  const current = text === null ? { videos: [] } : MANIFEST.parse(JSON.parse(text));
  const videos = [...current.videos.filter((video) => video.id !== entry.id), entry].sort((left, right) => left.id.localeCompare(right.id));
  writeText(MANIFEST_FILE, `${JSON.stringify({ videos }, null, 2)}\n`);
}
