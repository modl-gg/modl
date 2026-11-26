import { linkSync, mkdirSync, readdirSync, renameSync, statSync } from "node:fs";
import { basename, dirname, join } from "node:path";
import { removePath, sha256OfFile } from "../lib/util";
import { AUDIO_SAMPLE_RATE } from "./audio/format";
import { MediaProbe } from "./audio/probe";
import type { Ffmpeg } from "./ffmpeg";
import type { FrameSegment, Segment } from "./studio/segment";
import type { CaptionBand } from "./studio/stage";
import { LANDSCAPE, type VideoProfile } from "./studio/profile";
import { CROSSFADE_SECONDS, FRAME_RATE, THEME } from "./studio/theme";

const MAX_VIDEO_BYTES = 9_500_000;
const FADE_OUT_SECONDS = 0.6;
const CAPTION_FADE_SECONDS = 0.2;
const QUALITY_LADDER = [22, 25, 28] as const;
const AUDIO_ENCODING = ["-c:a", "aac", "-b:a", "160k", "-ar", String(AUDIO_SAMPLE_RATE), "-ac", "2"] as const;
const SHEET_FRAMES_PER_SECOND = "2/3";
const SHEET_COLUMNS = 4;
const SHEET_ROWS = 4;

export interface VideoFile {
  path: string;
  seconds: number;
  bytes: number;
  sha256: string;
  crf: number;
}

interface Clip {
  path: string;
  seconds: number;
}

export interface SceneTiming {
  segment: Segment;
  start: number;
  end: number;
}

export interface PreparedVideo {
  clips: readonly Clip[];
  scenes: readonly SceneTiming[];
  seconds: number;
}

export interface TimedOverlay {
  image: string;
  start: number;
  end: number;
  band: CaptionBand;
}

export interface EncodeOptions {
  overlays: readonly TimedOverlay[];
  audio: string | null;
  maxSeconds: number;
}

export function ffmpegColor(hex: string): string {
  return `0x${hex.replace("#", "")}`;
}

function displayFilter(profile: VideoProfile): string {
  return `fps=${String(FRAME_RATE)},scale=trunc(iw/2)*2:trunc(ih/2)*2:flags=lanczos:out_range=tv,pad=${String(profile.width)}:${String(profile.height)}:(ow-iw)/2:0:color=${ffmpegColor(THEME.background)},setsar=1,format=yuv420p`;
}

const SHEET_TILE_LONG_SIDE = 480;

function sheetTile(profile: VideoProfile): string {
  const scale = SHEET_TILE_LONG_SIDE / Math.max(profile.width, profile.height);
  return `${String(Math.round(profile.width * scale))}:${String(Math.round(profile.height * scale))}`;
}

function constantRateSequence(segment: FrameSegment, directory: string): number {
  removePath(directory);
  mkdirSync(directory, { recursive: true });
  const total = segment.frames.reduce((sum, frame) => sum + frame.seconds, 0);
  const count = Math.max(1, Math.round(total * FRAME_RATE));
  let source = 0;
  let sourceEnd = segment.frames[0]?.seconds ?? 0;
  for (let index = 0; index < count; index += 1) {
    const time = index / FRAME_RATE;
    while (time >= sourceEnd && source < segment.frames.length - 1) {
      source += 1;
      sourceEnd += segment.frames[source]?.seconds ?? 0;
    }
    const frame = segment.frames[source];
    if (!frame) throw new Error(`take ${segment.name} has no frames`);
    linkSync(frame.file, join(directory, `${String(index).padStart(6, "0")}.jpg`));
  }
  return count;
}

function sceneTimings(segments: readonly Segment[], clips: readonly Clip[]): { scenes: SceneTiming[]; seconds: number } {
  const scenes: SceneTiming[] = [];
  let start = 0;
  segments.forEach((segment, index) => {
    const seconds = clips[index]?.seconds ?? 0;
    scenes.push({ segment, start, end: start + seconds });
    start += seconds - CROSSFADE_SECONDS;
  });
  const last = scenes.at(-1);
  if (!last) throw new Error("a video needs at least one scene");
  return { scenes, seconds: last.end };
}

function crossfadeGraph(prepared: PreparedVideo): string[] {
  return prepared.scenes.slice(1).map((scene, index) => {
    const input = index === 0 ? "[0:v]" : `[x${String(index)}]`;
    return `${input}[${String(index + 1)}:v]xfade=transition=fade:duration=${String(CROSSFADE_SECONDS)}:offset=${scene.start.toFixed(3)}[x${String(index + 1)}]`;
  });
}

function overlayGraph(overlays: readonly TimedOverlay[], firstInput: number, base: string): { steps: string[]; output: string } {
  const steps: string[] = [];
  let current = base;
  overlays.forEach((overlay, index) => {
    const input = firstInput + index;
    const fadeOut = Math.max(overlay.start, overlay.end - CAPTION_FADE_SECONDS);
    steps.push(
      `[${String(input)}:v]format=rgba,fade=t=in:st=${overlay.start.toFixed(3)}:d=${String(CAPTION_FADE_SECONDS)}:alpha=1,fade=t=out:st=${fadeOut.toFixed(3)}:d=${String(CAPTION_FADE_SECONDS)}:alpha=1[c${String(index)}]`,
      `${current}[c${String(index)}]overlay=x=(W-w)/2:y=${String(overlay.band.top)}+(${String(overlay.band.height)}-h)/2:enable='between(t,${overlay.start.toFixed(3)},${overlay.end.toFixed(3)})'[o${String(index)}]`,
    );
    current = `[o${String(index)}]`;
  });
  return { steps, output: current };
}

export class Composer {
  private readonly ffmpeg: Ffmpeg;
  private readonly probe: MediaProbe;
  private readonly workDir: string;
  private readonly profile: VideoProfile;

  constructor(ffmpeg: Ffmpeg, workDir: string, profile: VideoProfile = LANDSCAPE) {
    this.ffmpeg = ffmpeg;
    this.probe = new MediaProbe(ffmpeg);
    this.workDir = workDir;
    this.profile = profile;
  }

  async prepare(segments: readonly Segment[]): Promise<PreparedVideo> {
    const clips: Clip[] = [];
    for (const segment of segments) clips.push(await this.clip(segment));
    return { clips, ...sceneTimings(segments, clips) };
  }

  async encode(prepared: PreparedVideo, output: string, options: EncodeOptions): Promise<VideoFile> {
    if (prepared.seconds > options.maxSeconds) {
      throw new Error(`${basename(output)} would run ${prepared.seconds.toFixed(1)} s, above ${String(options.maxSeconds)} s; shorten or split it`);
    }
    const lastScene = prepared.scenes.length - 1;
    const joined = lastScene === 0 ? "[0:v]" : `[x${String(lastScene)}]`;
    const overlays = overlayGraph(options.overlays, prepared.clips.length, joined);
    const fade = `fade=t=out:st=${(prepared.seconds - FADE_OUT_SECONDS).toFixed(3)}:d=${String(FADE_OUT_SECONDS)}:color=${ffmpegColor(THEME.background)}`;
    const graph = [...crossfadeGraph(prepared), ...overlays.steps, `${overlays.output}${fade},format=yuv420p[out]`].join(";");
    const overlayInputs = options.overlays.flatMap((overlay) => ["-loop", "1", "-framerate", String(FRAME_RATE), "-t", overlay.end.toFixed(3), "-i", this.ffmpeg.path(overlay.image)]);
    const audioIndex = prepared.clips.length + options.overlays.length;
    const audio = options.audio === null ? ["-an"] : ["-map", `${String(audioIndex)}:a`, ...AUDIO_ENCODING];
    const audioInput = options.audio === null ? [] : ["-i", this.ffmpeg.path(options.audio)];
    return this.encodeWithinBudget(output, (crf) => [
      ...prepared.clips.flatMap((clip) => ["-i", this.ffmpeg.path(clip.path)]),
      ...overlayInputs,
      ...audioInput,
      "-filter_complex", graph,
      "-map", "[out]",
      "-c:v", "libx264", "-preset", "slow", "-tune", "stillimage", "-crf", String(crf),
      "-pix_fmt", "yuv420p", "-color_range", "tv", "-r", String(FRAME_RATE), "-g", String(FRAME_RATE * 2),
      ...audio,
      "-t", prepared.seconds.toFixed(3),
      "-movflags", "+faststart",
    ]);
  }

  async encodeSequence(directory: string, seconds: number, audio: string, output: string, maxSeconds: number): Promise<VideoFile> {
    if (seconds > maxSeconds) throw new Error(`${basename(output)} would run ${seconds.toFixed(1)} s, above ${String(maxSeconds)} s`);
    const fadeOut = `fade=t=out:st=${(seconds - FADE_OUT_SECONDS).toFixed(3)}:d=${String(FADE_OUT_SECONDS)}:color=black`;
    return this.encodeWithinBudget(output, (crf) => [
      "-framerate", String(FRAME_RATE), "-i", this.ffmpeg.path(join(directory, "%06d.jpg")),
      "-i", this.ffmpeg.path(audio),
      "-vf", `scale=out_range=tv,${fadeOut},format=yuv420p`,
      "-map", "0:v", "-map", "1:a",
      "-c:v", "libx264", "-preset", "slow", "-crf", String(crf),
      "-pix_fmt", "yuv420p", "-color_range", "tv", "-r", String(FRAME_RATE), "-g", String(FRAME_RATE),
      ...AUDIO_ENCODING,
      "-t", seconds.toFixed(3),
      "-movflags", "+faststart",
    ]);
  }

  private async encodeWithinBudget(output: string, args: (crf: number) => readonly string[]): Promise<VideoFile> {
    const partial = join(this.workDir, "encoded.mp4");
    for (const crf of QUALITY_LADDER) {
      await this.ffmpeg.ffmpeg([...args(crf), this.ffmpeg.path(partial)]);
      const bytes = statSync(partial).size;
      if (bytes <= MAX_VIDEO_BYTES) {
        renameSync(partial, output);
        return { path: output, seconds: await this.probe.seconds(output), bytes, sha256: await sha256OfFile(output), crf };
      }
    }
    throw new Error(`${output} stays above ${String(MAX_VIDEO_BYTES)} bytes at CRF ${String(QUALITY_LADDER.at(-1))}; split the video`);
  }

  async contactSheets(video: string, prefix: string): Promise<string[]> {
    for (const stale of this.sheetsAt(prefix)) removePath(stale);
    const pattern = `${prefix}-%02d.png`;
    await this.ffmpeg.ffmpeg([
      "-i", this.ffmpeg.path(video),
      "-vf", `fps=${SHEET_FRAMES_PER_SECOND},scale=${sheetTile(this.profile)}:flags=lanczos,tile=${SHEET_COLUMNS}x${SHEET_ROWS}:padding=8:margin=8:color=${ffmpegColor(THEME.background)}`,
      "-fps_mode", "passthrough",
      this.ffmpeg.path(pattern),
    ]);
    return this.sheetsAt(prefix);
  }

  private sheetsAt(prefix: string): string[] {
    const stem = `${basename(prefix)}-`;
    return readdirSync(dirname(prefix))
      .filter((file) => file.startsWith(stem) && /-\d{2}\.png$/.test(file))
      .sort()
      .map((file) => join(dirname(prefix), file));
  }


  private async clip(segment: Segment): Promise<Clip> {
    const path = join(this.workDir, `clip-${segment.name}.mp4`);
    const intermediate = ["-c:v", "libx264", "-preset", "veryfast", "-crf", "10", "-pix_fmt", "yuv420p", "-color_range", "tv", "-r", String(FRAME_RATE), "-an", this.ffmpeg.path(path)];
    switch (segment.kind) {
      case "frames": {
        const sequence = join(this.workDir, "sequences", segment.name);
        constantRateSequence(segment, sequence);
        await this.ffmpeg.ffmpeg(["-framerate", String(FRAME_RATE), "-i", this.ffmpeg.path(join(sequence, "%06d.jpg")), "-vf", displayFilter(this.profile), ...intermediate]);
        removePath(sequence);
        break;
      }
      case "still":
        await this.ffmpeg.ffmpeg([
          "-loop", "1", "-framerate", String(FRAME_RATE), "-t", segment.seconds.toFixed(3), "-i", this.ffmpeg.path(segment.image),
          "-vf", displayFilter(this.profile), ...intermediate,
        ]);
        break;
    }
    return { path, seconds: await this.probe.seconds(path) };
  }
}
