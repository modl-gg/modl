import { spawn, type ChildProcessWithoutNullStreams } from "node:child_process";
import { cancellableDelay, currentSignal } from "../../lib/cancellation";
import { runProcess } from "../../lib/process";
import { HOST_FFMPEG } from "../ffmpeg";
import { FRAME_RATE } from "../studio/theme";
import type { Rect } from "./gui-layout";

const SCREEN_DEVICE = /\[(\d+)\] Capture screen 0$/m;
const FIRST_FRAME_TIMEOUT_MS = 10_000;
const STOP_TIMEOUT_MS = 30_000;
const LONGEST_TAKE_SECONDS = 300;
const BITRATE = "40M";
const PROGRESS_LINE = /^(\w+)=(.*)$/;
const REQUIRED_ENCODER = "h264_videotoolbox";

export interface Recording {
  readonly file: string;
  readonly firstFrameAtMs: number;
}

let deviceLookup: Promise<string> | null = null;

async function findScreenDevice(): Promise<string> {
  const encoders = await runProcess(HOST_FFMPEG, ["-hide_banner", "-encoders"]);
  if (!encoders.stdout.includes(REQUIRED_ENCODER)) throw new Error(`the host ffmpeg has no ${REQUIRED_ENCODER} encoder`);
  const listing = await runProcess(HOST_FFMPEG, ["-hide_banner", "-f", "avfoundation", "-list_devices", "true", "-i", ""], { allowFailure: true });
  const index = SCREEN_DEVICE.exec(listing.stderr.split("\n").map((line) => line.replace(/^\[[^\]]+\] /, "")).join("\n"))?.[1];
  if (index === undefined) throw new Error("the host ffmpeg lists no \"Capture screen 0\" device; give the terminal Screen Recording access in System Settings");
  return index;
}

export async function screenDevice(): Promise<string> {
  deviceLookup ??= findScreenDevice();
  try {
    return await deviceLookup;
  } catch (error) {
    deviceLookup = null;
    throw error;
  }
}

function signalOption(): { signal?: AbortSignal } {
  const signal = currentSignal();
  return signal ? { signal } : {};
}

interface Running {
  readonly child: ChildProcessWithoutNullStreams;
  readonly exited: Promise<number>;
  readonly failure: Promise<never>;
  readonly control: { stopping: boolean };
}

export class ScreenRecorder {
  private readonly rect: Rect;
  private readonly scale: number;
  private running: Running | null = null;
  private firstFrameAtMs = Number.POSITIVE_INFINITY;
  private stderr = "";

  constructor(rect: Rect, scale: number) {
    this.rect = rect;
    this.scale = scale;
  }

  async start(file: string): Promise<void> {
    if (this.running) throw new Error("the screen recorder is already running");
    const device = await screenDevice();
    const crop = [this.rect.width, this.rect.height, this.rect.x, this.rect.y].map((value) => String(Math.round(value * this.scale)));
    const child = spawn(HOST_FFMPEG, [
      "-hide_banner", "-nostats", "-v", "error",
      "-f", "avfoundation", "-capture_cursor", "1", "-pixel_format", "uyvy422", "-framerate", String(FRAME_RATE), "-i", `${device}:none`,
      "-t", String(LONGEST_TAKE_SECONDS),
      "-vf", `crop=${crop.join(":")}`,
      "-fps_mode", "cfr", "-r", String(FRAME_RATE),
      "-c:v", "h264_videotoolbox", "-b:v", BITRATE,
      "-progress", "pipe:1", "-stats_period", "0.1",
      "-y", file,
    ], signalOption());
    this.firstFrameAtMs = Number.POSITIVE_INFINITY;
    this.stderr = "";
    child.on("error", (error) => this.note(error.message));
    child.stdin.on("error", (error) => this.note(error.message));
    child.stderr.on("data", (chunk: Buffer) => this.note(chunk.toString("utf8")));
    const exited = new Promise<number>((resolve) => child.once("close", (code) => resolve(code ?? -1)));
    const control = { stopping: false };
    const failure = exited.then((code): never => {
      throw new Error(control.stopping ? "the screen recording was stopped" : `the screen recording ended during the take (exit ${String(code)}): ${this.stderr.trim()}`);
    });
    failure.catch(() => undefined);
    const running: Running = { child, exited, failure, control };
    this.running = running;
    this.followProgress(child);
    await this.awaitFirstFrame(running);
  }

  async failure(): Promise<never> {
    if (!this.running) throw new Error("the screen recorder is not running");
    return this.running.failure;
  }

  async stop(file: string): Promise<Recording> {
    const running = this.running;
    if (!running) throw new Error("the screen recorder was never started");
    running.control.stopping = true;
    if (running.child.exitCode === null && running.child.signalCode === null) running.child.stdin.end("q");
    const timer = setTimeout(() => running.child.kill("SIGKILL"), STOP_TIMEOUT_MS);
    const code = await running.exited;
    clearTimeout(timer);
    this.running = null;
    if (code !== 0) throw new Error(`the screen recording exited ${String(code)}: ${this.stderr.trim()}`);
    return { file, firstFrameAtMs: this.firstFrameAtMs };
  }

  async abort(): Promise<void> {
    const running = this.running;
    if (!running) return;
    running.control.stopping = true;
    running.child.kill("SIGKILL");
    await running.exited;
    this.running = null;
  }

  private note(text: string): void {
    this.stderr = `${this.stderr}${text}`.slice(-4000);
  }

  private followProgress(child: ChildProcessWithoutNullStreams): void {
    let pending = "";
    let frames = 0;
    child.stdout.on("data", (chunk: Buffer) => {
      const receivedMs = Date.now();
      pending += chunk.toString("utf8");
      const lines = pending.split("\n");
      pending = lines.pop() ?? "";
      for (const line of lines) {
        const [, key, value] = PROGRESS_LINE.exec(line.trim()) ?? [];
        if (key === "frame") frames = Number(value);
        if (key === "out_time_us" && frames > 0 && value !== undefined && /^\d+$/.test(value)) {
          this.firstFrameAtMs = Math.min(this.firstFrameAtMs, receivedMs - Number(value) / 1000);
        }
      }
    });
  }

  private async awaitFirstFrame(running: Running): Promise<void> {
    const deadline = Date.now() + FIRST_FRAME_TIMEOUT_MS;
    try {
      while (!Number.isFinite(this.firstFrameAtMs)) {
        const ended = running.child.exitCode !== null || running.child.signalCode !== null;
        if (ended || Date.now() > deadline) throw new Error(`the screen recording produced no frame: ${this.stderr.trim() || "no output"}`);
        await cancellableDelay(50);
      }
    } catch (error) {
      await this.abort();
      throw error;
    }
  }
}
