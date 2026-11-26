import { join } from "node:path";
import { MusicBed } from "../audio/bed";
import { BeatAnalyzer, regularGrid, type BeatGrid, type RegularGrid } from "../audio/beats";
import { musicCredit, PIGSTEP, requireMusic, SWEDEN, type MusicCredit, type MusicTrack } from "../audio/music";
import { judgeMix, medianShortTerm, MixInspector, type MixQa, type QaSection } from "../audio/mix-qa";
import { MIX_TARGET, mixQaRange, normalizeWithinPeak } from "../audio/soundtrack";
import { AUDIO_SAMPLE_RATE as SAMPLE_RATE } from "../audio/format";
import { MediaProbe } from "../audio/probe";
import type { Ffmpeg } from "../ffmpeg";

export interface Score {
  readonly opening: MusicTrack;
  readonly drop: MusicTrack;
  readonly dropBeatNear: number;
  readonly dropEntry: number;
  readonly dropAt: number;
  readonly crossfadeSeconds: number;
  readonly openingRise: { readonly from: number; readonly to: number };
  readonly dropLufs: number;
  readonly endFadeSeconds: number;
}

export const MINECRAFT_SCORE: Score = {
  opening: SWEDEN,
  drop: PIGSTEP,
  dropBeatNear: 23.15,
  dropEntry: 21.0,
  dropAt: 26.0,
  crossfadeSeconds: 1.8,
  openingRise: { from: -24, to: -20 },
  dropLufs: -15,
  endFadeSeconds: 3,
};

const BEAT_SNAP_SECONDS = 0.12;

function nearestIndex(beats: readonly number[], time: number): number {
  let best = -1;
  beats.forEach((beat, index) => {
    if (best === -1 || Math.abs(beat - time) < Math.abs((beats[best] ?? 0) - time)) best = index;
  });
  return best;
}

export class BeatClock {
  readonly dropAt: number;
  readonly dropEntryAt: number;
  readonly dropGrid: RegularGrid;
  private readonly openingBeats: readonly number[];
  private readonly offset: number;

  constructor(score: Score, opening: BeatGrid, drop: BeatGrid) {
    const index = nearestIndex(drop.beats, score.dropBeatNear);
    const dropBeat = drop.beats[index];
    if (dropBeat === undefined || Math.abs(dropBeat - score.dropBeatNear) > BEAT_SNAP_SECONDS) {
      throw new Error(`${score.drop.title} has no detected beat within ${String(BEAT_SNAP_SECONDS)} s of ${String(score.dropBeatNear)} s`);
    }
    this.dropGrid = regularGrid(drop.beats, dropBeat);
    this.offset = score.dropAt - this.dropGrid.anchor;
    this.dropAt = score.dropAt;
    this.dropEntryAt = score.dropEntry + this.offset;
    this.openingBeats = opening.beats.filter((beat) => beat < this.dropEntryAt);
  }

  opening(beat: number): number {
    const time = this.openingBeats[beat];
    if (time === undefined) throw new Error(`the opening has no beat ${String(beat)} before ${this.dropEntryAt.toFixed(2)} s`);
    return time;
  }

  drop(beat: number): number {
    return Number((this.dropAt + beat * this.dropGrid.period).toFixed(3));
  }

  nearest(time: number): number {
    if (time >= this.dropEntryAt) return this.drop(Math.round((time - this.dropAt) / this.dropGrid.period));
    return this.openingBeats.reduce((best, beat) => (Math.abs(beat - time) < Math.abs(best - time) ? beat : best), this.openingBeats[0] ?? 0);
  }

  report(): { dropAt: number; dropEntryAt: number; openingBeats: readonly number[]; dropGrid: RegularGrid } {
    return { dropAt: this.dropAt, dropEntryAt: this.dropEntryAt, openingBeats: this.openingBeats, dropGrid: this.dropGrid };
  }
}

export interface PreparedScore {
  readonly clock: BeatClock;
  readonly credits: readonly MusicCredit[];
  readonly files: { readonly opening: string; readonly drop: string };
}

const TRACK_PEAK_DBTP = -3;
const OPENING_CEILING_DB = -6;
const QA = { openingSpreadLu: 4, crossfadeDipLu: 2, crossfadeBumpLu: 3, edgeSeconds: 3, hopSeconds: 1 } as const;

export class ScoreMixer {
  private readonly ffmpeg: Ffmpeg;
  private readonly probe: MediaProbe;
  private readonly workDir: string;
  private readonly score: Score;

  constructor(ffmpeg: Ffmpeg, workDir: string, score: Score) {
    this.ffmpeg = ffmpeg;
    this.probe = new MediaProbe(ffmpeg);
    this.workDir = workDir;
    this.score = score;
  }

  async prepare(): Promise<PreparedScore> {
    const opening = await requireMusic(this.score.opening);
    const drop = await requireMusic(this.score.drop);
    const analyzer = new BeatAnalyzer(this.ffmpeg, this.workDir);
    const clock = new BeatClock(this.score, await analyzer.analyse(opening), await analyzer.analyse(drop));
    return { clock, credits: [musicCredit(this.score.opening), musicCredit(this.score.drop)], files: { opening, drop } };
  }

  private async gainFor(file: string, range: { start: number; end: number }, lufs: number): Promise<number> {
    const measured = await this.probe.loudness(file, range);
    return Math.min(lufs - measured.integratedLufs, TRACK_PEAK_DBTP - measured.truePeakDbtp);
  }

  async inspect(prepared: PreparedScore, file: string, seconds: number): Promise<MixQa> {
    const { clock } = prepared;
    const measured = await new MixInspector(this.ffmpeg).measure(file);
    const opening = medianShortTerm(measured, QA.edgeSeconds * 2, clock.dropEntryAt);
    const drop = medianShortTerm(measured, clock.dropAt + QA.edgeSeconds, seconds - this.score.endFadeSeconds - QA.edgeSeconds);
    const range = mixQaRange();
    const sections: QaSection[] = [
      { name: "opening", from: QA.edgeSeconds, to: clock.dropEntryAt - 0.5, lowLufs: opening - QA.openingSpreadLu, highLufs: opening + QA.openingSpreadLu },
      { name: "crossfade and drop", from: clock.dropEntryAt - 2, to: clock.dropAt + 2.5, lowLufs: Math.min(opening, drop) - QA.crossfadeDipLu, highLufs: drop + QA.crossfadeBumpLu },
      { name: "drop", from: clock.dropAt + 2.5, to: seconds - this.score.endFadeSeconds, lowLufs: range.lowLufs, highLufs: range.highLufs },
    ];
    return judgeMix(measured, sections, MIX_TARGET.truePeakCeilingDbtp, QA.hopSeconds);
  }

  async mix(prepared: PreparedScore, seconds: number): Promise<string> {
    const { clock, files } = prepared;
    const score = this.score;
    const premix = join(this.workDir, "score-premix.wav");
    const entry = clock.dropEntryAt;
    const dropLength = seconds - entry;
    const rise = score.openingRise;
    const opening = await new MusicBed(this.ffmpeg, this.workDir).level(files.opening, entry + score.crossfadeSeconds, {
      lufsAt: (time) => rise.from + (rise.to - rise.from) * Math.min(1, Math.max(0, time / entry)),
      ceilingDb: OPENING_CEILING_DB,
    }, "opening");
    const dropGain = await this.gainFor(files.drop, { start: score.dropEntry, end: score.dropEntry + dropLength }, score.dropLufs);
    const fadeOut = Math.max(0, dropLength - score.endFadeSeconds);
    const graph = [
      `[0:a]afade=t=out:st=${entry.toFixed(3)}:d=${String(score.crossfadeSeconds)}:curve=qsin[opening]`,
      `[1:a]atrim=${String(score.dropEntry)}:${(score.dropEntry + dropLength).toFixed(3)},asetpts=N/SR/TB,aresample=${String(SAMPLE_RATE)},aformat=channel_layouts=stereo,volume=${dropGain.toFixed(2)}dB,afade=t=in:st=0:d=${String(score.crossfadeSeconds)}:curve=qsin,afade=t=out:st=${fadeOut.toFixed(3)}:d=${String(score.endFadeSeconds)},adelay=${String(Math.round(entry * 1000))}:all=1[drop]`,
      `[opening][drop]amix=inputs=2:normalize=0:duration=longest,atrim=0:${seconds.toFixed(3)}[mix]`,
    ].join(";");
    await this.ffmpeg.ffmpeg([
      "-i", this.ffmpeg.path(opening), "-i", this.ffmpeg.path(files.drop),
      "-filter_complex", graph, "-map", "[mix]", "-t", seconds.toFixed(3), "-ar", String(SAMPLE_RATE), "-c:a", "pcm_f32le", this.ffmpeg.path(premix),
    ]);
    const output = join(this.workDir, "score.wav");
    await normalizeWithinPeak(this.ffmpeg, premix, output);
    return output;
  }
}
