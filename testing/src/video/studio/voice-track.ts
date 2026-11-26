import { sleep } from "../../lib/util";
import type { SpokenClip } from "./segment";
import { CROSSFADE_SECONDS } from "./theme";

const LEAD_MS = Math.round(CROSSFADE_SECONDS * 1000) + 100;
const CLIP_GAP_MS = 450;
const SCENE_TAIL_MS = Math.round(CROSSFADE_SECONDS * 1000) + 700;

export interface TimedClip {
  clip: SpokenClip;
  atMs: number;
}

async function waitUntil(epochMs: number): Promise<void> {
  const remaining = epochMs - Date.now();
  if (remaining > 0) await sleep(remaining);
}

export class VoiceTrack {
  private readonly clips: TimedClip[] = [];
  private started = false;
  private nextAllowedAt = 0;
  private speakingUntil = 0;

  begin(): void {
    this.clips.length = 0;
    this.started = true;
    this.nextAllowedAt = Date.now() + LEAD_MS;
    this.speakingUntil = 0;
  }

  async say(clip: SpokenClip): Promise<void> {
    if (!this.started) throw new Error(`"${clip.text}" was said before its take started`);
    await waitUntil(this.nextAllowedAt);
    const atMs = Date.now();
    this.clips.push({ clip, atMs });
    this.speakingUntil = atMs + clip.seconds * 1000;
    this.nextAllowedAt = this.speakingUntil + CLIP_GAP_MS;
  }

  async untilSilent(): Promise<void> {
    await waitUntil(this.speakingUntil);
  }

  async finish(): Promise<TimedClip[]> {
    this.started = false;
    if (this.clips.length > 0) await waitUntil(this.speakingUntil + SCENE_TAIL_MS);
    return [...this.clips];
  }
}
