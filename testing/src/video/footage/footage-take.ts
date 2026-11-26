import { mkdirSync, readdirSync } from "node:fs";
import { join } from "node:path";
import { removePath, sleep } from "../../lib/util";
import { ffmpegColor } from "../composer";
import type { Ffmpeg } from "../ffmpeg";
import { CaptionTrack, placeCues } from "../studio/caption-track";
import { Narration } from "../studio/pointer";
import type { FrameSegment, SpokenClip, TimedFrame } from "../studio/segment";
import { framePixels, type StageLayout } from "../studio/stage";
import { FRAME_RATE, THEME } from "../studio/theme";
import { VoiceTrack } from "../studio/voice-track";
import type { Rect } from "./gui-layout";
import { ScreenRecorder } from "./screen-recorder";

const JPEG_QUALITY = "2";

export interface FootageSetup {
  readonly content: Rect;
  readonly pixelScale: number;
  readonly ffmpeg: Ffmpeg;
  readonly workDir: string;
  readonly stage: StageLayout;
}

export class FootageTake {
  readonly name: string;
  private readonly setup: FootageSetup;
  private readonly recorder: ScreenRecorder;
  private readonly captions = new CaptionTrack();
  private readonly voice = new VoiceTrack();
  private readonly recordingFile: string;
  private readonly framesDir: string;
  private recording = false;

  constructor(setup: FootageSetup, name: string) {
    if (setup.stage.labels !== "captions") throw new Error(`footage take ${name} needs a captioned stage`);
    this.setup = setup;
    this.name = name;
    this.recorder = new ScreenRecorder(setup.content, setup.pixelScale);
    this.recordingFile = join(setup.workDir, "footage", `${name}.mp4`);
    this.framesDir = join(setup.workDir, "frames", name);
  }

  narration(): Narration {
    return new Narration(sleep, this.captions);
  }

  async say(clip: SpokenClip): Promise<void> {
    await this.voice.say(clip);
  }

  async untilSilent(): Promise<void> {
    await this.voice.untilSilent();
  }

  async start(): Promise<void> {
    if (this.recording) throw new Error(`footage take ${this.name} is already recording`);
    mkdirSync(join(this.setup.workDir, "footage"), { recursive: true });
    this.captions.drain();
    await this.recorder.start(this.recordingFile);
    this.recording = true;
    this.voice.begin();
  }

  async whileRecording<T>(scene: Promise<T>): Promise<T> {
    return Promise.race([scene, this.recorder.failure()]);
  }

  async stop(): Promise<FrameSegment> {
    if (!this.recording) throw new Error(`footage take ${this.name} was never started`);
    const spoken = await this.whileRecording(this.voice.finish());
    const recording = await this.recorder.stop(this.recordingFile);
    this.recording = false;
    const frames = await this.extractFrames(recording.file);
    removePath(recording.file);
    return { kind: "frames", name: this.name, frames, ...placeCues(this.captions.drain(), spoken, recording.firstFrameAtMs) };
  }

  async abort(): Promise<void> {
    this.recording = false;
    await this.recorder.abort();
    removePath(this.recordingFile);
  }

  private async extractFrames(recording: string): Promise<TimedFrame[]> {
    const { ffmpeg, stage } = this.setup;
    const pixels = framePixels(stage);
    const size = `${String(pixels.width)}:${String(pixels.height)}`;
    removePath(this.framesDir);
    mkdirSync(this.framesDir, { recursive: true });
    await ffmpeg.ffmpeg([
      "-i", ffmpeg.path(recording),
      "-vf", `fps=${String(FRAME_RATE)},scale=${size}:force_original_aspect_ratio=decrease:flags=lanczos,pad=${size}:(ow-iw)/2:(oh-ih)/2:color=${ffmpegColor(THEME.background)}`,
      "-q:v", JPEG_QUALITY,
      ffmpeg.path(join(this.framesDir, "%06d.jpg")),
    ]);
    const files = readdirSync(this.framesDir).filter((file) => file.endsWith(".jpg")).sort();
    if (files.length === 0) throw new Error(`footage take ${this.name} has no frames`);
    return files.map((file) => ({ file: join(this.framesDir, file), seconds: 1 / FRAME_RATE }));
  }
}
