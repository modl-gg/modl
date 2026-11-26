import { readFileSync } from "node:fs";
import { join } from "node:path";
import { removePath } from "../../lib/util";
import type { Ffmpeg } from "../ffmpeg";
import { Spectrum } from "./spectrum";

const ANALYSIS_RATE = 22_050;
const FRAME_SIZE = 2048;
const HOP = 512;
const FRAME_SECONDS = HOP / ANALYSIS_RATE;
const TEMPO_RANGE_BPM = { lowest: 60, highest: 200 } as const;
const TEMPO_PRIOR = { centreBpm: 120, octaves: 1 } as const;
const TIGHTNESS = 100;
const LOCAL_MEAN_SECONDS = 0.5;
const ENERGY_WINDOW_SECONDS = 0.5;
const ONSET_THRESHOLD_STD = 1.5;
const ONSET_SPACING_SECONDS = 0.1;

export interface EnergyWindow {
  start: number;
  db: number;
}

export interface BeatGrid {
  readonly tempoBpm: number;
  readonly beats: readonly number[];
  readonly onsets: readonly number[];
  readonly energy: readonly EnergyWindow[];
  readonly seconds: number;
}

function movingAverage(values: Float64Array, radius: number): Float64Array {
  const sums = new Float64Array(values.length + 1);
  values.forEach((value, index) => {
    sums[index + 1] = (sums[index] ?? 0) + value;
  });
  return values.map((_, index) => {
    const from = Math.max(0, index - radius);
    const to = Math.min(values.length, index + radius + 1);
    return ((sums[to] ?? 0) - (sums[from] ?? 0)) / (to - from);
  });
}

function standardised(values: Float64Array): Float64Array {
  const mean = values.reduce((sum, value) => sum + value, 0) / Math.max(values.length, 1);
  const variance = values.reduce((sum, value) => sum + (value - mean) ** 2, 0) / Math.max(values.length, 1);
  const deviation = Math.sqrt(variance) || 1;
  return values.map((value) => value / deviation);
}

function onsetEnvelope(samples: Float32Array): Float64Array {
  const spectrum = new Spectrum(FRAME_SIZE);
  const bins = FRAME_SIZE / 2 + 1;
  const frames = Math.max(0, Math.floor((samples.length - FRAME_SIZE) / HOP) + 1);
  const flux = new Float64Array(frames);
  let previous = new Float64Array(bins);
  let current = new Float64Array(bins);
  for (let frame = 0; frame < frames; frame += 1) {
    spectrum.magnitudes(samples, frame * HOP, current);
    let sum = 0;
    for (let bin = 0; bin < bins; bin += 1) {
      const level = Math.log1p(1000 * (current[bin] ?? 0));
      const rise = level - (previous[bin] ?? 0);
      if (frame > 0 && rise > 0) sum += rise;
      current[bin] = level;
    }
    flux[frame] = sum;
    [previous, current] = [current, previous];
  }
  const localMean = movingAverage(flux, Math.round(LOCAL_MEAN_SECONDS / FRAME_SECONDS));
  return standardised(flux.map((value, index) => Math.max(0, value - (localMean[index] ?? 0))));
}

function tempoPeriod(envelope: Float64Array): number {
  const shortest = Math.floor(60 / TEMPO_RANGE_BPM.highest / FRAME_SECONDS);
  const longest = Math.ceil(60 / TEMPO_RANGE_BPM.lowest / FRAME_SECONDS);
  let best = shortest;
  let bestScore = Number.NEGATIVE_INFINITY;
  for (let lag = shortest; lag <= longest; lag += 1) {
    let correlation = 0;
    for (let index = lag; index < envelope.length; index += 1) correlation += (envelope[index] ?? 0) * (envelope[index - lag] ?? 0);
    const bpm = 60 / (lag * FRAME_SECONDS);
    const weight = Math.exp(-0.5 * (Math.log2(bpm / TEMPO_PRIOR.centreBpm) / TEMPO_PRIOR.octaves) ** 2);
    const score = (correlation / (envelope.length - lag)) * weight;
    if (score > bestScore) {
      bestScore = score;
      best = lag;
    }
  }
  return best;
}

function trackBeats(envelope: Float64Array, period: number): number[] {
  const scores = new Float64Array(envelope.length);
  const previous = new Int32Array(envelope.length).fill(-1);
  for (let index = 0; index < envelope.length; index += 1) {
    let bestPrevious = -1;
    let bestScore = 0;
    for (let back = Math.round(period / 2); back <= period * 2; back += 1) {
      const candidate = index - back;
      if (candidate < 0) break;
      const score = (scores[candidate] ?? 0) - TIGHTNESS * Math.log(back / period) ** 2;
      if (bestPrevious === -1 || score > bestScore) {
        bestScore = score;
        bestPrevious = candidate;
      }
    }
    scores[index] = (envelope[index] ?? 0) + (bestPrevious === -1 ? 0 : bestScore);
    previous[index] = bestPrevious;
  }
  let last = 0;
  for (let index = Math.max(0, envelope.length - period); index < envelope.length; index += 1) {
    if ((scores[index] ?? 0) > (scores[last] ?? 0)) last = index;
  }
  const beats: number[] = [];
  for (let frame = last; frame >= 0; frame = previous[frame] ?? -1) beats.push(frame);
  return beats.reverse();
}

function pickOnsets(envelope: Float64Array): number[] {
  const spacing = Math.round(ONSET_SPACING_SECONDS / FRAME_SECONDS);
  const onsets: number[] = [];
  for (let index = 1; index < envelope.length - 1; index += 1) {
    const value = envelope[index] ?? 0;
    if (value < ONSET_THRESHOLD_STD || value < (envelope[index - 1] ?? 0) || value < (envelope[index + 1] ?? 0)) continue;
    const last = onsets.at(-1);
    if (last !== undefined && index - last < spacing) continue;
    onsets.push(index);
  }
  return onsets;
}

function energyWindows(samples: Float32Array): EnergyWindow[] {
  const size = Math.round(ENERGY_WINDOW_SECONDS * ANALYSIS_RATE);
  const windows: EnergyWindow[] = [];
  for (let start = 0; start + size <= samples.length; start += size) {
    let sum = 0;
    for (let index = start; index < start + size; index += 1) sum += (samples[index] ?? 0) ** 2;
    windows.push({ start: start / ANALYSIS_RATE, db: 10 * Math.log10(sum / size + 1e-12) });
  }
  return windows;
}

function seconds(frame: number): number {
  return Number(((frame * HOP + FRAME_SIZE / 2) / ANALYSIS_RATE).toFixed(3));
}

export function analyseSamples(samples: Float32Array): BeatGrid {
  const envelope = onsetEnvelope(samples);
  const period = tempoPeriod(envelope);
  return {
    tempoBpm: Number((60 / (period * FRAME_SECONDS)).toFixed(1)),
    beats: trackBeats(envelope, period).map(seconds),
    onsets: pickOnsets(envelope).map(seconds),
    energy: energyWindows(samples),
    seconds: samples.length / ANALYSIS_RATE,
  };
}

export class BeatAnalyzer {
  private readonly ffmpeg: Ffmpeg;
  private readonly workDir: string;

  constructor(ffmpeg: Ffmpeg, workDir: string) {
    this.ffmpeg = ffmpeg;
    this.workDir = workDir;
  }

  async analyse(file: string): Promise<BeatGrid> {
    const raw = join(this.workDir, "beats.f32");
    try {
      await this.ffmpeg.ffmpeg(["-i", this.ffmpeg.path(file), "-ac", "1", "-ar", String(ANALYSIS_RATE), "-f", "f32le", this.ffmpeg.path(raw)]);
      const bytes = readFileSync(raw);
      const length = Math.floor(bytes.byteLength / 4) * 4;
      return analyseSamples(new Float32Array(bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + length)));
    } finally {
      removePath(raw);
    }
  }
}

const GRID_TOLERANCE = 0.25;

function median(values: readonly number[]): number {
  const sorted = [...values].sort((left, right) => left - right);
  return sorted[Math.floor(sorted.length / 2)] ?? 0;
}

export interface RegularGrid {
  readonly period: number;
  readonly anchor: number;
  readonly fitted: number;
  readonly worstOffset: number;
}

export function regularGrid(beats: readonly number[], anchor: number): RegularGrid {
  const period = median(beats.slice(1).map((beat, index) => beat - (beats[index] ?? beat)));
  const indexed = beats
    .map((beat) => ({ beat, index: Math.round((beat - anchor) / period) }))
    .filter((entry) => Math.abs(entry.beat - (anchor + entry.index * period)) < GRID_TOLERANCE * period);
  const count = indexed.length;
  const meanIndex = indexed.reduce((sum, entry) => sum + entry.index, 0) / count;
  const meanBeat = indexed.reduce((sum, entry) => sum + entry.beat, 0) / count;
  const covariance = indexed.reduce((sum, entry) => sum + (entry.index - meanIndex) * (entry.beat - meanBeat), 0);
  const variance = indexed.reduce((sum, entry) => sum + (entry.index - meanIndex) ** 2, 0);
  const fittedPeriod = variance === 0 ? period : covariance / variance;
  const fittedAnchor = meanBeat - fittedPeriod * meanIndex;
  const worstOffset = indexed.reduce((worst, entry) => Math.max(worst, Math.abs(entry.beat - (fittedAnchor + entry.index * fittedPeriod))), 0);
  return { period: fittedPeriod, anchor: fittedAnchor, fitted: count, worstOffset };
}
