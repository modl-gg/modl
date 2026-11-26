import { mkdirSync } from "node:fs";
import { writeFile } from "node:fs/promises";
import { join } from "node:path";
import type { CDPSession, Page } from "playwright-core";
import { removePath } from "../../lib/util";
import { requireNothingCut } from "./cut-text";
import { CaptionTrack, placeCues } from "./caption-track";
import { CinematicPointer, Narration, OverlayLabels, type LabelSink, type Point } from "./pointer";
import type { FrameSegment, SpokenClip, TimedFrame } from "./segment";
import { framePixels, type StageLayout } from "./stage";
import { VoiceTrack } from "./voice-track";

interface CapturedFrame {
  file: string;
  atMs: number;
  receivedMs: number;
}

function frameName(index: number): string {
  return `${String(index).padStart(6, "0")}.jpg`;
}

const JPEG_QUALITY = 94;

function timedFrames(frames: readonly CapturedFrame[], endMs: number): TimedFrame[] {
  return frames.map((frame, index) => ({
    file: frame.file,
    seconds: Math.max(0, ((frames[index + 1]?.atMs ?? endMs) - frame.atMs) / 1000),
  }));
}

export class Take {
  readonly page: Page;
  readonly name: string;
  private readonly framesDir: string;
  private readonly stage: StageLayout;
  private readonly labels: LabelSink;
  private readonly captions: CaptionTrack | null;
  private readonly voice = new VoiceTrack();
  private readonly frames: CapturedFrame[] = [];
  private readonly writes: Promise<void>[] = [];
  private cdp: CDPSession | null = null;
  private stopped = false;

  constructor(page: Page, name: string, workDir: string, stage: StageLayout) {
    this.page = page;
    this.name = name;
    this.framesDir = join(workDir, "frames", name);
    this.stage = stage;
    this.captions = stage.labels === "captions" ? new CaptionTrack() : null;
    this.labels = this.captions ?? new OverlayLabels(page);
  }

  pointer(start: Point): CinematicPointer {
    return new CinematicPointer(this.page, start, this.labels);
  }

  narration(): Narration {
    return new Narration(async (ms) => this.page.waitForTimeout(ms), this.labels);
  }

  async say(clip: SpokenClip): Promise<void> {
    await this.voice.say(clip);
  }

  async untilSilent(): Promise<void> {
    await this.voice.untilSilent();
  }

  async start(): Promise<void> {
    if (this.cdp) throw new Error(`take ${this.name} is already recording`);
    removePath(this.framesDir);
    this.stopped = false;
    mkdirSync(this.framesDir, { recursive: true });
    this.voice.begin();
    const cdp = await this.page.context().newCDPSession(this.page);
    this.cdp = cdp;
    cdp.on("Page.screencastFrame", (frame) => {
      const receivedMs = Date.now();
      cdp.send("Page.screencastFrameAck", { sessionId: frame.sessionId }).catch(() => undefined);
      if (this.stopped) return;
      const file = join(this.framesDir, frameName(this.frames.length));
      this.frames.push({ file, atMs: frame.metadata.timestamp === undefined ? receivedMs : frame.metadata.timestamp * 1000, receivedMs });
      this.writes.push(writeFile(file, Buffer.from(frame.data, "base64")));
    });
    if (this.stage.requireUncut) await requireNothingCut(this.page, `when take ${this.name} starts`);
    const pixels = framePixels(this.stage);
    await cdp.send("Page.startScreencast", { format: "jpeg", quality: JPEG_QUALITY, maxWidth: pixels.width, maxHeight: pixels.height, everyNthFrame: 1 });
  }

  async stop(): Promise<FrameSegment> {
    const cdp = this.cdp;
    if (!cdp) throw new Error(`take ${this.name} was never started`);
    const spoken = await this.voice.finish();
    if (this.stage.requireUncut) await requireNothingCut(this.page, `when take ${this.name} ends`);
    const stoppedMs = Date.now();
    this.stopped = true;
    await cdp.send("Page.stopScreencast");
    await cdp.detach();
    this.cdp = null;
    await Promise.all(this.writes);
    const first = this.frames[0];
    const last = this.frames.at(-1);
    if (!first || !last) throw new Error(`take ${this.name} captured no frames`);
    const endMs = last.atMs + (stoppedMs - last.receivedMs);
    const frames = timedFrames(this.frames, endMs);
    const cues = placeCues(this.captions?.drain() ?? [], spoken, first.atMs);
    this.frames.length = 0;
    this.writes.length = 0;
    return { kind: "frames", name: this.name, frames, ...cues };
  }
}
