import { readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { removePath } from "../../lib/util";
import type { Ffmpeg } from "../ffmpeg";
import { AUDIO_SAMPLE_RATE as SAMPLE_RATE } from "./format";
import { MixInspector } from "./mix-qa";
import { MediaProbe } from "./probe";

const CHANNELS = 2;
const LEVELING = { boostDb: 10, cutDb: 8, smoothingSeconds: 1.2, lookaheadSeconds: 1.5, silentLufs: -70 } as const;

export interface BedLevel {
  readonly lufsAt: (time: number) => number;
  readonly ceilingDb: number;
}

function interpolatedGain(times: readonly number[], gains: readonly number[], time: number): number {
  if (times.length === 0) return 0;
  let low = 0;
  let high = times.length - 1;
  while (high - low > 1) {
    const middle = (low + high) >> 1;
    if ((times[middle] ?? 0) <= time) low = middle;
    else high = middle;
  }
  const from = times[low] ?? 0;
  const to = times[high] ?? from;
  const progress = to === from ? 0 : Math.min(1, Math.max(0, (time - from) / (to - from)));
  return (gains[low] ?? 0) + ((gains[high] ?? 0) - (gains[low] ?? 0)) * progress;
}

function smoothed(values: readonly number[], radius: number): number[] {
  return values.map((_, index) => {
    const window = values.slice(Math.max(0, index - radius), index + radius + 1);
    return window.reduce((sum, value) => sum + value, 0) / Math.max(1, window.length);
  });
}

export class MusicBed {
  private readonly ffmpeg: Ffmpeg;
  private readonly workDir: string;

  constructor(ffmpeg: Ffmpeg, workDir: string) {
    this.ffmpeg = ffmpeg;
    this.workDir = workDir;
  }

  async level(music: string, seconds: number, target: BedLevel, name = "bed"): Promise<string> {
    const trimmed = join(this.workDir, `${name}-trimmed.wav`);
    const raw = join(this.workDir, `${name}.f32`);
    const output = join(this.workDir, `${name}.wav`);
    try {
      await this.ffmpeg.ffmpeg([
        "-stream_loop", "-1", "-i", this.ffmpeg.path(music), "-t", seconds.toFixed(3),
        "-af", `aresample=${String(SAMPLE_RATE)},aformat=channel_layouts=stereo`, "-c:a", "pcm_f32le", this.ffmpeg.path(trimmed),
      ]);
      const measured = await new MixInspector(this.ffmpeg).measure(trimmed);
      const audible = measured.frames.filter((frame) => frame.shortTerm > LEVELING.silentLufs);
      const times = audible.map((frame) => frame.time - LEVELING.lookaheadSeconds);
      const step = audible.length > 1 ? ((audible.at(-1)?.time ?? 0) - (audible[0]?.time ?? 0)) / (audible.length - 1) : 0.1;
      const baseline = target.lufsAt(seconds / 2) - (await new MediaProbe(this.ffmpeg).loudness(trimmed)).integratedLufs;
      const gains = smoothed(
        audible.map((frame) => baseline + Math.min(LEVELING.boostDb, Math.max(-LEVELING.cutDb, target.lufsAt(frame.time - LEVELING.lookaheadSeconds) - (frame.shortTerm + baseline)))),
        Math.max(1, Math.round(LEVELING.smoothingSeconds / step)),
      );
      await this.ffmpeg.ffmpeg(["-i", this.ffmpeg.path(trimmed), "-f", "f32le", "-c:a", "pcm_f32le", this.ffmpeg.path(raw)]);
      const bytes = readFileSync(raw);
      const samples = new Float32Array(bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + Math.floor(bytes.byteLength / 4) * 4));
      for (let frame = 0; frame < samples.length / CHANNELS; frame += 1) {
        const factor = 10 ** (interpolatedGain(times, gains, frame / SAMPLE_RATE) / 20);
        for (let channel = 0; channel < CHANNELS; channel += 1) {
          const index = frame * CHANNELS + channel;
          samples[index] = (samples[index] ?? 0) * factor;
        }
      }
      writeFileSync(raw, Buffer.from(samples.buffer));
      const ceiling = (10 ** (target.ceilingDb / 20)).toFixed(4);
      await this.ffmpeg.ffmpeg([
        "-f", "f32le", "-ar", String(SAMPLE_RATE), "-ac", String(CHANNELS), "-i", this.ffmpeg.path(raw),
        "-af", `alimiter=limit=${ceiling}:attack=5:release=80:level=0`, "-c:a", "pcm_f32le", this.ffmpeg.path(output),
      ]);
      return output;
    } finally {
      removePath(trimmed);
      removePath(raw);
    }
  }
}
