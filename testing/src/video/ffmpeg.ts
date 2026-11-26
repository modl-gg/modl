import { mkdirSync } from "node:fs";
import { isAbsolute, relative, sep } from "node:path";
import { pullIfMissing, runDocker } from "../lib/docker";
import type { Logger } from "../lib/log";
import { runProcess, type CommandResult } from "../lib/process";

const FFMPEG_IMAGE = "linuxserver/ffmpeg:7.1.1@sha256:aea59a11c54291ac456bb2d67000445e5a8994f70bc3d96cdc29f022fbbf89fb";
const CONTAINER_ROOT = "/work";
export const HOST_FFMPEG = "ffmpeg";
const REQUIRED_FILTERS = ["xfade", "fade", "tile", "fps", "scale", "pad", "overlay", "loudnorm", "ebur128", "amix", "atempo", "silencedetect", "silenceremove", "alimiter", "asetnsamples", "volume", "afade", "acompressor", "highpass"] as const;
const REQUIRED_ENCODERS = ["libx264", "aac"] as const;

export interface Mount {
  host: string;
  container: string;
}

function insideOf(root: string, path: string): string | null {
  const inside = relative(root, path);
  return inside === ".." || inside.startsWith(`..${sep}`) || isAbsolute(inside) ? null : inside;
}
const ENCODE_TIMEOUT_MS = 900_000;

export interface Ffmpeg {
  readonly description: string;
  path(hostPath: string): string;
  ffmpeg(args: readonly string[]): Promise<CommandResult>;
  ffprobe(args: readonly string[]): Promise<CommandResult>;
  measure(args: readonly string[]): Promise<string>;
}

class HostFfmpeg implements Ffmpeg {
  readonly description = "ffmpeg on this machine";

  path(hostPath: string): string {
    return hostPath;
  }

  async ffmpeg(args: readonly string[]): Promise<CommandResult> {
    return runProcess(HOST_FFMPEG, ["-hide_banner", "-v", "error", "-y", ...args], { timeoutMs: ENCODE_TIMEOUT_MS });
  }

  async ffprobe(args: readonly string[]): Promise<CommandResult> {
    return runProcess("ffprobe", ["-v", "error", ...args]);
  }

  async measure(args: readonly string[]): Promise<string> {
    return (await runProcess(HOST_FFMPEG, ["-hide_banner", "-nostats", ...args], { timeoutMs: ENCODE_TIMEOUT_MS })).stderr;
  }
}

class DockerFfmpeg implements Ffmpeg {
  readonly description = `ffmpeg in ${FFMPEG_IMAGE}`;
  private readonly mounts: readonly Mount[];

  constructor(hostDirectories: readonly string[]) {
    this.mounts = hostDirectories.map((host, index) => ({ host, container: `${CONTAINER_ROOT}/${String(index)}` }));
  }

  path(hostPath: string): string {
    for (const mount of this.mounts) {
      const inside = insideOf(mount.host, hostPath);
      if (inside !== null) return `${mount.container}/${inside.split(sep).join("/")}`;
    }
    throw new Error(`${hostPath} is outside the folders mounted into the ffmpeg container`);
  }

  async ffmpeg(args: readonly string[]): Promise<CommandResult> {
    return this.run("ffmpeg", ["-hide_banner", "-v", "error", "-y", ...args]);
  }

  async ffprobe(args: readonly string[]): Promise<CommandResult> {
    return this.run("ffprobe", ["-v", "error", ...args]);
  }

  async measure(args: readonly string[]): Promise<string> {
    return (await this.run("ffmpeg", ["-hide_banner", "-nostats", ...args])).stderr;
  }

  private async run(tool: string, args: readonly string[]): Promise<CommandResult> {
    for (const mount of this.mounts) mkdirSync(mount.host, { recursive: true });
    const user = `${String(process.getuid?.() ?? 1000)}:${String(process.getgid?.() ?? 1000)}`;
    return runDocker(
      ["run", "--rm", "--network", "none", "--user", user, ...this.mounts.flatMap((mount) => ["-v", `${mount.host}:${mount.container}`]), "--entrypoint", tool, FFMPEG_IMAGE, ...args],
      { timeoutMs: ENCODE_TIMEOUT_MS },
    );
  }
}

async function hostFfmpegUsable(): Promise<boolean> {
  try {
    const encoders = await runProcess("ffmpeg", ["-hide_banner", "-encoders"]);
    const filters = await runProcess("ffmpeg", ["-hide_banner", "-filters"]);
    await runProcess("ffprobe", ["-version"]);
    const encoderNames = new Set(encoders.stdout.split("\n").map((line) => line.trim().split(/\s+/)[1]));
    const filterNames = new Set(filters.stdout.split("\n").map((line) => line.trim().split(/\s+/)[1]));
    return REQUIRED_ENCODERS.every((encoder) => encoderNames.has(encoder)) && REQUIRED_FILTERS.every((filter) => filterNames.has(filter));
  } catch {
    return false;
  }
}

export async function resolveFfmpeg(logger: Logger, hostDirectories: readonly string[]): Promise<Ffmpeg> {
  if (await hostFfmpegUsable()) return new HostFfmpeg();
  logger.info(`ffmpeg with ${REQUIRED_ENCODERS.join(", ")} and ${REQUIRED_FILTERS.join(", ")} is not installed; using ${FFMPEG_IMAGE}`);
  await pullIfMissing(FFMPEG_IMAGE, ENCODE_TIMEOUT_MS);
  return new DockerFfmpeg(hostDirectories);
}
