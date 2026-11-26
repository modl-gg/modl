import type { Ffmpeg } from "../ffmpeg";

export interface Loudness {
  integratedLufs: number;
  rangeLu: number;
  truePeakDbtp: number;
  summary: string;
}

export interface Interval {
  start: number;
  end: number;
}

function numberAfter(text: string, pattern: RegExp, what: string): number {
  const raw = pattern.exec(text)?.[1];
  const value = raw === "-inf" ? Number.NEGATIVE_INFINITY : Number(raw);
  if (Number.isNaN(value)) throw new Error(`ffmpeg reported no ${what}`);
  return value;
}

export class MediaProbe {
  private readonly ffmpeg: Ffmpeg;

  constructor(ffmpeg: Ffmpeg) {
    this.ffmpeg = ffmpeg;
  }

  async loudness(file: string, range: Interval | null = null): Promise<Loudness> {
    const window = range === null ? [] : ["-ss", range.start.toFixed(3), "-t", (range.end - range.start).toFixed(3)];
    const log = await this.ffmpeg.measure([...window, "-i", this.ffmpeg.path(file), "-af", "ebur128=peak=true", "-f", "null", "-"]);
    const tail = log.slice(log.lastIndexOf("Summary:"));
    const end = tail.indexOf("\n[");
    const summary = end === -1 ? tail : tail.slice(0, end);
    return {
      integratedLufs: numberAfter(summary, /I:\s+(-?[\d.]+) LUFS/, "integrated loudness"),
      rangeLu: numberAfter(summary, /LRA:\s+(-?[\d.]+) LU/, "loudness range"),
      truePeakDbtp: numberAfter(summary, /True peak:\s+Peak:\s+(-?[\d.]+|-inf) dBFS/, "true peak"),
      summary: summary.trim(),
    };
  }

  async peakDb(file: string): Promise<number> {
    const log = await this.ffmpeg.measure(["-i", this.ffmpeg.path(file), "-af", "volumedetect", "-f", "null", "-"]);
    return numberAfter(log, /max_volume:\s+(-?[\d.]+) dB/, "peak level");
  }

  async silences(file: string, thresholdDb: number, minimumSeconds: number): Promise<Interval[]> {
    const log = await this.ffmpeg.measure([
      "-i", this.ffmpeg.path(file), "-af", `silencedetect=noise=${thresholdDb}dB:d=${minimumSeconds}`, "-f", "null", "-",
    ]);
    const starts = [...log.matchAll(/silence_start:\s+(-?[\d.]+)/g)].map((match) => Number(match[1]));
    const ends = [...log.matchAll(/silence_end:\s+(-?[\d.]+)/g)].map((match) => Number(match[1]));
    return starts.map((start, index) => ({ start, end: ends[index] ?? Number.POSITIVE_INFINITY }));
  }

  async seconds(file: string): Promise<number> {
    const result = await this.ffmpeg.ffprobe(["-show_entries", "format=duration", "-of", "csv=p=0", this.ffmpeg.path(file)]);
    const seconds = Number(result.stdout.trim());
    if (!Number.isFinite(seconds) || seconds <= 0) throw new Error(`ffprobe reported no duration for ${file}`);
    return seconds;
  }
}
