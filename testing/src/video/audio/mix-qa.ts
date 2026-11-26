import type { Ffmpeg } from "../ffmpeg";
import { MediaProbe } from "./probe";

const SHORT_TERM_SECONDS = 3;
const MOMENTARY_SECONDS = 0.4;
const MOMENTARY_JUMP_LU = 6;
const SILENT_LUFS = -70;
const FRAME_LINE = /t:\s*([\d.]+)\s+TARGET:.*?M:\s*(-?[\d.]+|-inf|nan)\s+S:\s*(-?[\d.]+|-inf|nan)/;

export interface QaSection {
  readonly name: string;
  readonly from: number;
  readonly to: number;
  readonly lowLufs: number;
  readonly highLufs: number;
}

export interface LoudnessWindow {
  readonly start: number;
  readonly end: number;
  readonly shortTermLufs: number;
  readonly section: string | null;
  readonly verdict: "ok" | "low" | "high" | "unchecked";
}

export interface MomentaryJump {
  readonly at: number;
  readonly fromLufs: number;
  readonly toLufs: number;
}

export interface MixQa {
  readonly windows: readonly LoudnessWindow[];
  readonly jumps: readonly MomentaryJump[];
  readonly truePeakDbtp: number;
  readonly truePeakCeilingDbtp: number;
  readonly problems: readonly string[];
  readonly warnings: readonly string[];
}

export interface Frame {
  readonly time: number;
  readonly momentary: number;
  readonly shortTerm: number;
}

function level(raw: string | undefined): number {
  const value = Number(raw);
  return Number.isFinite(value) ? value : Number.NEGATIVE_INFINITY;
}

function frames(log: string): Frame[] {
  return log.split("\n").flatMap((line) => {
    const match = FRAME_LINE.exec(line);
    return match ? [{ time: Number(match[1]), momentary: level(match[2]), shortTerm: level(match[3]) }] : [];
  });
}

function nearest(all: readonly Frame[], time: number): Frame | undefined {
  let best: Frame | undefined;
  for (const frame of all) if (best === undefined || Math.abs(frame.time - time) < Math.abs(best.time - time)) best = frame;
  return best;
}

function sectionOf(sections: readonly QaSection[], start: number, end: number): QaSection | undefined {
  return sections.find((section) => start >= section.from - 0.01 && end <= section.to + 0.01);
}

export function windowTable(qa: MixQa): string {
  return qa.windows.map((window) => `${window.start.toFixed(0).padStart(4)}-${window.end.toFixed(0).padEnd(4)} ${window.shortTermLufs.toFixed(1).padStart(6)} LUFS ${window.verdict}${window.section === null ? "" : ` (${window.section})`}`).join("\n");
}

export interface MeasuredMix {
  readonly frames: readonly Frame[];
  readonly seconds: number;
  readonly truePeakDbtp: number;
}

export function medianShortTerm(mix: MeasuredMix, from: number, to: number): number {
  const values = mix.frames.filter((frame) => frame.time >= from && frame.time <= to && frame.shortTerm > SILENT_LUFS).map((frame) => frame.shortTerm).sort((left, right) => left - right);
  return values[Math.floor(values.length / 2)] ?? Number.NEGATIVE_INFINITY;
}

export function judgeMix(mix: MeasuredMix, sections: readonly QaSection[], truePeakCeilingDbtp: number, hopSeconds = SHORT_TERM_SECONDS): MixQa {
  const { frames: all, seconds, truePeakDbtp: truePeak } = mix;
  const windows: LoudnessWindow[] = [];
  for (let end = SHORT_TERM_SECONDS; end <= seconds + 0.05; end += hopSeconds) {
    const start = end - SHORT_TERM_SECONDS;
    const shortTerm = nearest(all, end)?.shortTerm ?? Number.NEGATIVE_INFINITY;
    const section = sectionOf(sections, start, end);
    const verdict = section === undefined ? "unchecked" : shortTerm < section.lowLufs ? "low" : shortTerm > section.highLufs ? "high" : "ok";
    windows.push({ start, end, shortTermLufs: shortTerm, section: section?.name ?? null, verdict });
  }
  const jumps: MomentaryJump[] = [];
  let previous: Frame | undefined;
  for (let time = MOMENTARY_SECONDS; time <= seconds; time += MOMENTARY_SECONDS) {
    const frame = nearest(all, time);
    if (frame && previous && sectionOf(sections, previous.time, frame.time) && frame.momentary > SILENT_LUFS && previous.momentary > SILENT_LUFS && Math.abs(frame.momentary - previous.momentary) > MOMENTARY_JUMP_LU) {
      jumps.push({ at: Number(frame.time.toFixed(1)), fromLufs: previous.momentary, toLufs: frame.momentary });
    }
    previous = frame;
  }
  const problems = [
    ...windows.filter((window) => window.verdict === "low" || window.verdict === "high").map((window) => `${window.start.toFixed(1)}-${window.end.toFixed(1)} s is ${window.shortTermLufs.toFixed(1)} LUFS (${window.verdict}, ${window.section ?? ""})`),
    ...(truePeak > truePeakCeilingDbtp ? [`true peak ${truePeak.toFixed(1)} dBTP is above ${String(truePeakCeilingDbtp)} dBTP`] : []),
  ];
  const largest = jumps.reduce<MomentaryJump | null>((worst, jump) => (worst === null || Math.abs(jump.toLufs - jump.fromLufs) > Math.abs(worst.toLufs - worst.fromLufs) ? jump : worst), null);
  const warnings = largest === null ? [] : [`${String(jumps.length)} momentary (400 ms) jumps above ${String(MOMENTARY_JUMP_LU)} LU; the largest goes from ${largest.fromLufs.toFixed(1)} to ${largest.toLufs.toFixed(1)} LUFS at ${largest.at.toFixed(1)} s`];
  return { windows, jumps, truePeakDbtp: truePeak, truePeakCeilingDbtp, problems, warnings };
}

export class MixInspector {
  private readonly ffmpeg: Ffmpeg;
  private readonly probe: MediaProbe;

  constructor(ffmpeg: Ffmpeg) {
    this.ffmpeg = ffmpeg;
    this.probe = new MediaProbe(ffmpeg);
  }

  async measure(file: string): Promise<MeasuredMix> {
    const log = await this.ffmpeg.measure(["-i", this.ffmpeg.path(file), "-map", "0:a", "-af", "ebur128=peak=true", "-f", "null", "-"]);
    return { frames: frames(log), seconds: await this.probe.seconds(file), truePeakDbtp: (await this.probe.loudness(file)).truePeakDbtp };
  }

  async inspect(file: string, sections: readonly QaSection[], truePeakCeilingDbtp: number): Promise<MixQa> {
    return judgeMix(await this.measure(file), sections, truePeakCeilingDbtp);
  }
}
