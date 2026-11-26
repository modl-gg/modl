import { join } from "node:path";
import { REPORTS_DIR } from "../lib/paths";
import { MUSIC_DIR } from "./audio/music";
import { TTS_CLIP_DIR } from "./voice/voice";

export const VIDEO_DIR = join(REPORTS_DIR, "video");

export const FFMPEG_DIRECTORIES: readonly string[] = [VIDEO_DIR, TTS_CLIP_DIR, MUSIC_DIR];

export function videoFile(id: string): string {
  return join(VIDEO_DIR, `${id}.mp4`);
}

export function contactSheetPrefix(id: string): string {
  return join(VIDEO_DIR, `${id}-sheet`);
}

export function workDirFor(id: string): string {
  return join(VIDEO_DIR, "work", id);
}
