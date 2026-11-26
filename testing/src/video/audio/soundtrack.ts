import { join } from "node:path";
import { z } from "zod";
import type { Ffmpeg } from "../ffmpeg";
import { AUDIO_SAMPLE_RATE as SAMPLE_RATE } from "./format";
import { removePath } from "../../lib/util";
import { MusicBed, type BedLevel } from "./bed";
import type { QaSection } from "./mix-qa";
import { MediaProbe, type Loudness } from "./probe";

export interface PlacedVoice {
  file: string;
  start: number;
  seconds: number;
}

interface Interval {
  start: number;
  end: number;
}

export interface Soundtrack {
  file: string;
  voice: Loudness;
}

const MUSIC_LUFS = -19;
const BED_LEVEL: BedLevel = { lufsAt: () => MUSIC_LUFS, ceilingDb: -10 };
export const MIX_TARGET = { integratedLufs: -16, toleranceLu: 1, truePeakCeilingDbtp: -1.5 } as const;
const MIX_LUFS = MIX_TARGET.integratedLufs;
const MIX_TRUE_PEAK = -2;
const MIX_RANGE = 11;
const FADE_IN_SECONDS = 1.5;
const FADE_OUT_SECONDS = 2;
const QA_LOW_LU = 5;
const QA_HIGH_LU = 3;

export function narratedQaSections(seconds: number): QaSection[] {
  return [{ name: "narrated mix", from: FADE_IN_SECONDS, to: seconds - FADE_OUT_SECONDS, lowLufs: MIX_LUFS - QA_LOW_LU, highLufs: MIX_LUFS + QA_HIGH_LU }];
}

export function mixQaRange(): { lowLufs: number; highLufs: number } {
  return { lowLufs: MIX_LUFS - QA_LOW_LU, highLufs: MIX_LUFS + QA_HIGH_LU };
}
export const DUCKING = { depthDb: -11, leadSeconds: 0.3, rampSeconds: 0.25, tailSeconds: 0.1, holdAcrossSeconds: 1.5, stepSamples: 480 } as const;

export function speechSpans(voices: readonly PlacedVoice[]): Interval[] {
  const sorted = [...voices].sort((left, right) => left.start - right.start);
  const spans: Interval[] = [];
  for (const voice of sorted) {
    const last = spans.at(-1);
    const end = voice.start + voice.seconds;
    if (last && voice.start - last.end < DUCKING.holdAcrossSeconds) last.end = Math.max(last.end, end);
    else spans.push({ start: voice.start, end });
  }
  return spans;
}

export function duckingExpression(spans: readonly Interval[]): string {
  const depth = 1 - 10 ** (DUCKING.depthDb / 20);
  const ramp = DUCKING.rampSeconds.toFixed(3);
  const terms = spans.map((span) => {
    const from = (span.start - DUCKING.leadSeconds).toFixed(3);
    const until = (span.end + DUCKING.tailSeconds + DUCKING.rampSeconds).toFixed(3);
    return `clip(min((t-${from})/${ramp},(${until}-t)/${ramp}),0,1)`;
  });
  return terms.length === 0 ? "1" : `1-${depth.toFixed(4)}*(${terms.join("+")})`;
}

const LOUDNORM_MEASUREMENT = z.object({
  input_i: z.string(),
  input_tp: z.string(),
  input_lra: z.string(),
  input_thresh: z.string(),
  target_offset: z.string(),
  normalization_type: z.string(),
});

type LoudnormReport = z.infer<typeof LOUDNORM_MEASUREMENT>;

function loudnormReport(log: string): LoudnormReport {
  const start = log.lastIndexOf("{");
  const end = log.lastIndexOf("}");
  if (start === -1 || end < start) throw new Error("ffmpeg loudnorm printed no measurement");
  const json: unknown = JSON.parse(log.slice(start, end + 1));
  return LOUDNORM_MEASUREMENT.parse(json);
}

function loudnormTarget(range: number): string {
  return `I=${String(MIX_LUFS)}:TP=${String(MIX_TRUE_PEAK)}:LRA=${String(range)}`;
}

export class SoundtrackMixer {
  private readonly ffmpeg: Ffmpeg;
  private readonly probe: MediaProbe;
  private readonly workDir: string;

  constructor(ffmpeg: Ffmpeg, workDir: string) {
    this.ffmpeg = ffmpeg;
    this.probe = new MediaProbe(ffmpeg);
    this.workDir = workDir;
  }

  async mix(voices: readonly PlacedVoice[], music: string, seconds: number): Promise<Soundtrack> {
    const voice = await this.voiceTrack(voices, seconds);
    const premix = await this.premix(voice, music, seconds, speechSpans(voices));
    const file = join(this.workDir, "soundtrack.wav");
    await normalizeToMixTarget(this.ffmpeg, premix, file);
    return { file, voice: await this.probe.loudness(voice) };
  }

  private async voiceTrack(voices: readonly PlacedVoice[], seconds: number): Promise<string> {
    if (voices.length === 0) throw new Error("a narrated video needs at least one voice clip");
    const output = join(this.workDir, "voice.wav");
    const delayed = voices.map((voice, index) => {
      const delay = Math.round(voice.start * 1000);
      return `[${String(index)}:a]aresample=${String(SAMPLE_RATE)},aformat=channel_layouts=mono,adelay=${String(delay)}:all=1[v${String(index)}]`;
    });
    const mixed = `${voices.map((_, index) => `[v${String(index)}]`).join("")}amix=inputs=${String(voices.length)}:normalize=0:dropout_transition=0,apad,atrim=0:${seconds.toFixed(3)}[voice]`;
    await this.ffmpeg.ffmpeg([
      ...voices.flatMap((voice) => ["-i", this.ffmpeg.path(voice.file)]),
      "-filter_complex", [...delayed, mixed].join(";"),
      "-map", "[voice]", "-ar", String(SAMPLE_RATE), "-c:a", "pcm_f32le", this.ffmpeg.path(output),
    ]);
    return output;
  }

  private async premix(voice: string, music: string, seconds: number, spans: readonly Interval[]): Promise<string> {
    const output = join(this.workDir, "premix.wav");
    const bed = await new MusicBed(this.ffmpeg, this.workDir).level(music, seconds, BED_LEVEL);
    const fadeOut = Math.max(0, seconds - FADE_OUT_SECONDS);
    const graph = [
      `[1:a]asetnsamples=n=${String(DUCKING.stepSamples)},volume='${duckingExpression(spans)}':eval=frame,afade=t=in:st=0:d=${String(FADE_IN_SECONDS)},afade=t=out:st=${fadeOut.toFixed(3)}:d=${String(FADE_OUT_SECONDS)}[music]`,
      "[0:a]aformat=channel_layouts=stereo[voice]",
      "[voice][music]amix=inputs=2:normalize=0:duration=first[mix]",
    ].join(";");
    try {
      await this.ffmpeg.ffmpeg([
        "-i", this.ffmpeg.path(voice), "-i", this.ffmpeg.path(bed),
        "-filter_complex", graph, "-map", "[mix]", "-t", seconds.toFixed(3), "-ar", String(SAMPLE_RATE), "-c:a", "pcm_f32le", this.ffmpeg.path(output),
      ]);
    } finally {
      removePath(bed);
    }
    return output;
  }
}

export async function normalizeWithinPeak(ffmpeg: Ffmpeg, premix: string, output: string): Promise<number> {
  const measured = loudnormReport(
    await ffmpeg.measure(["-i", ffmpeg.path(premix), "-af", `loudnorm=${loudnormTarget(MIX_RANGE)}:print_format=json`, "-f", "null", "-"]),
  );
  const gain = Math.min(MIX_LUFS - Number(measured.input_i), MIX_TRUE_PEAK - Number(measured.input_tp));
  await ffmpeg.ffmpeg(["-i", ffmpeg.path(premix), "-af", `volume=${gain.toFixed(2)}dB,aresample=${String(SAMPLE_RATE)}`, "-ac", "2", ffmpeg.path(output)]);
  return Number(measured.input_i) + gain;
}

export async function normalizeToMixTarget(ffmpeg: Ffmpeg, premix: string, output: string, range = MIX_RANGE): Promise<void> {
  const measured = loudnormReport(
    await ffmpeg.measure(["-i", ffmpeg.path(premix), "-af", `loudnorm=${loudnormTarget(range)}:print_format=json`, "-f", "null", "-"]),
  );
  const gain = MIX_LUFS - Number(measured.input_i);
  if (Number(measured.input_tp) + gain > MIX_TRUE_PEAK) {
    throw new Error(`the premix measures ${measured.input_i} LUFS with a ${measured.input_tp} dBTP peak; reaching ${String(MIX_LUFS)} LUFS linearly would exceed ${String(MIX_TRUE_PEAK)} dBTP`);
  }
  const second = [
    `loudnorm=${loudnormTarget(range)}`,
    `measured_I=${measured.input_i}`,
    `measured_TP=${measured.input_tp}`,
    `measured_LRA=${measured.input_lra}`,
    `measured_thresh=${measured.input_thresh}`,
    `offset=${measured.target_offset}`,
    "linear=true",
    "print_format=json",
  ].join(":");
  const applied = loudnormReport(
    await ffmpeg.measure(["-i", ffmpeg.path(premix), "-af", `${second},aresample=${String(SAMPLE_RATE)}`, "-ac", "2", "-y", ffmpeg.path(output)]),
  );
  if (applied.normalization_type !== "linear") throw new Error(`loudnorm fell back to ${applied.normalization_type} normalization; the mix would pump`);
}
