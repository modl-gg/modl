import { mkdirSync, readdirSync } from "node:fs";
import { join } from "node:path";
import { cancellable, cancellationError } from "../lib/cancellation";
import { Environment } from "../lib/environment";
import { upOptions } from "../lib/inspect";
import { down, up } from "../lib/lifecycle";
import type { Logger } from "../lib/log";
import { productVersion } from "../lib/product";
import { readState } from "../lib/state";
import { errorMessage, removePath } from "../lib/util";
import { Composer } from "./composer";
import type { TeaserVideo, TutorialVideo, VideoContext, VideoDefinition } from "./definition";
import { resolveFfmpeg, type Ffmpeg } from "./ffmpeg";
import { requireRealClientReady } from "./footage/real-client";
import { labelProblems, labelText } from "./labels";
import { recordInManifest } from "./manifest";
import { contactSheetPrefix, FFMPEG_DIRECTORIES, videoFile, workDirFor } from "./output";
import { productionFor, type Production } from "./production";
import type { VideoReport } from "./report";
import { titleScene } from "./scenes/title";
import { importPlugins } from "./scripts/import-plugins";
import { linkAccount } from "./scripts/link-account";
import { paperInstall } from "./scripts/paper-install";
import { publicPages } from "./scripts/public-pages";
import { reportToReplay } from "./scripts/report-to-replay";
import { staffTeam } from "./scripts/staff-team";
import { teaserV3 } from "./scripts/teaser-v3";
import { velocityNetwork } from "./scripts/velocity-network";
import type { Segment } from "./studio/segment";
import { FOOTAGE_STAGE, type StageLayout } from "./studio/stage";
import { Studio } from "./studio/studio";
import { TeaserProducer } from "./teaser/production";

export type { VideoReport } from "./report";

const TITLE_SECONDS = 2.4;
const SHORTEST_EXPECTED_SECONDS = 40;
const FAILURE_DIR = "failure";

export const VIDEOS: readonly VideoDefinition[] = [paperInstall, velocityNetwork, linkAccount, reportToReplay, importPlugins, staffTeam, publicPages, teaserV3];

const NARRATION_IN_USE: readonly string[] = VIDEOS.flatMap((video) => (video.presentation === "narrated" ? video.narration : []));

interface Recording<T extends VideoDefinition> {
  video: T;
  workDir: string;
  version: string;
  signal: AbortSignal;
  logger: Logger;
}

function selectVideos(selection: readonly string[]): VideoDefinition[] {
  if (selection.length === 0) throw new Error(`usage: video <${[...VIDEOS.map((video) => video.id), "all"].join("|")}...>`);
  if (selection.includes("all")) return [...VIDEOS];
  return selection.map((id) => {
    const video = VIDEOS.find((candidate) => candidate.id === id);
    if (!video) throw new Error(`unknown video '${id}' (${VIDEOS.map((candidate) => candidate.id).join(", ")})`);
    return video;
  });
}

function stepLabelProblems(video: VideoDefinition): string[] {
  switch (video.presentation) {
    case "labels":
    case "narrated":
      return labelProblems(video.labels).map((problem) => `${video.id}: ${problem}`);
    case "teaser":
      return [];
  }
}

function requireLabels(videos: readonly VideoDefinition[]): void {
  const problems = videos.flatMap(stepLabelProblems);
  if (problems.length > 0) throw new Error(`step labels do not match the wiki:\n${problems.join("\n")}`);
}

function requireNoEnvironment(): void {
  const running = readState();
  if (running) throw new Error(`the ${running.topology} topology is up; run down first (a video boots its own topology fresh and tears it down)`);
}

async function requireRealClient(videos: readonly VideoDefinition[]): Promise<void> {
  const needing = videos.filter((video) => video.realClient === true);
  if (needing.length === 0) return;
  const unsupported = needing.filter((video) => video.presentation !== "narrated" || video.topology !== "standalone").map((video) => video.id);
  if (unsupported.length > 0) throw new Error(`${unsupported.join(", ")} cannot film the real client: only narrated standalone videos can`);
  try {
    await requireRealClientReady();
  } catch (error) {
    throw new Error(`${needing.map((video) => video.id).join(", ")} film the real Minecraft client: ${errorMessage(error)}`, { cause: error });
  }
}

async function onTopology<T>(recording: Recording<VideoDefinition>, work: () => Promise<T>): Promise<T> {
  const { video, logger, signal } = recording;
  try {
    return await cancellable(signal, async () => {
      await up(upOptions({ topology: video.topology, fresh: true, deferSetup: true, realClient: video.realClient === true }), logger);
      return work();
    });
  } finally {
    const report = await down({ keepData: false }, logger);
    if (!report.clean) logger.warn(`the ${video.topology} topology left resources behind: ${JSON.stringify(report.leftovers)}`);
  }
}

async function inStudio<T>(recording: Recording<VideoDefinition>, stage: StageLayout, work: (studio: Studio) => Promise<T>): Promise<T> {
  const studio = await Studio.open(recording.workDir, stage);
  try {
    return await work(studio);
  } catch (error) {
    const pages = await studio.captureOpenPages(join(recording.workDir, FAILURE_DIR));
    recording.logger.error(`recording ${recording.video.id} failed; open pages: ${pages.join(", ") || "none"}`);
    throw error;
  } finally {
    await studio.close().catch((error: unknown) => recording.logger.warn(`closing the browser failed: ${errorMessage(error)}`));
  }
}

async function recordSegments(recording: Recording<TutorialVideo>, production: Production, studio: Studio, ffmpeg: Ffmpeg): Promise<Segment[]> {
  const { video, workDir, version, logger } = recording;
  const title = await studio.still("title", titleScene(video.title), TITLE_SECONDS);
  const scenes = await onTopology(recording, async () => {
    const context: VideoContext = {
      environment: Environment.current(logger),
      studio,
      logger,
      workDir,
      ffmpeg,
      text: (label) => labelText(label, version),
      speech: (line) => production.speech(line),
    };
    return video.record(context);
  });
  return [title, ...scenes];
}

function keepOnlyFailureEvidence(workDir: string): void {
  for (const entry of readdirSync(workDir)) {
    if (entry !== FAILURE_DIR) removePath(join(workDir, entry));
  }
}

async function inWorkDir<T>(recording: Recording<VideoDefinition>, work: () => Promise<T>): Promise<T> {
  const { video, workDir, logger } = recording;
  removePath(workDir);
  mkdirSync(workDir, { recursive: true });
  logger.info(`recording ${video.id}: ${video.title}`);
  try {
    const result = await work();
    removePath(workDir);
    return result;
  } catch (error) {
    keepOnlyFailureEvidence(workDir);
    throw error;
  }
}

async function recordTutorial(recording: Recording<TutorialVideo>, ffmpeg: Ffmpeg): Promise<VideoReport[]> {
  const { video, workDir, version, logger, signal } = recording;
  const production = productionFor(video, ffmpeg, workDir, logger, NARRATION_IN_USE);
  return inWorkDir(recording, async () => {
    await cancellable(signal, async () => production.prepare?.());
    const composer = new Composer(ffmpeg, workDir);
    const result = await inStudio(recording, production.stage, async (studio) => {
      const segments = await recordSegments(recording, production, studio, ffmpeg);
      return cancellable(signal, async () => production.finish(await composer.prepare(segments), composer, studio, videoFile(video.id)));
    });
    const { file } = result;
    const contactSheets = await composer.contactSheets(file.path, contactSheetPrefix(video.id));
    recordInManifest({
      id: video.id,
      title: video.title,
      wikiPage: video.wikiPage,
      modlVersion: version,
      seconds: Number(file.seconds.toFixed(2)),
      bytes: file.bytes,
      sha256: file.sha256,
      ...(result.audio ? { audio: result.audio } : {}),
    });
    const warnings = [
      ...(file.seconds < SHORTEST_EXPECTED_SECONDS ? [`${file.seconds.toFixed(1)} s is shorter than ${String(SHORTEST_EXPECTED_SECONDS)} s`] : []),
      ...(result.audioQa?.warnings ?? []),
    ];
    return [{ ...file, id: video.id, title: video.title, wikiPage: video.wikiPage, contactSheets, warnings, timing: result.timing, loudness: result.loudness, cuts: [], audioQa: result.audioQa }];
  });
}

async function recordTeaser(recording: Recording<TeaserVideo>, ffmpeg: Ffmpeg): Promise<VideoReport[]> {
  const { video, workDir, version, logger, signal } = recording;
  const producer = new TeaserProducer(video, ffmpeg, workDir, logger);
  return inWorkDir(recording, async () => {
    const prepared = await cancellable(signal, async () => producer.prepare());
    const produced = await inStudio(recording, FOOTAGE_STAGE, async (studio) => {
      const edit = await onTopology(recording, async () => video.record({ environment: Environment.current(logger), studio, logger, workDir, clock: prepared.clock }));
      return cancellable(signal, async () => producer.produce(prepared, edit, studio, version));
    });
    for (const cut of produced) recordInManifest(cut.manifest);
    return produced.map((cut) => cut.report);
  });
}

async function recordVideo(video: VideoDefinition, ffmpeg: Ffmpeg, base: Omit<Recording<VideoDefinition>, "video" | "workDir">): Promise<VideoReport[]> {
  const workDir = workDirFor(video.id);
  switch (video.presentation) {
    case "labels":
    case "narrated":
      return recordTutorial({ ...base, video, workDir }, ffmpeg);
    case "teaser":
      return recordTeaser({ ...base, video, workDir }, ffmpeg);
  }
}

export async function recordVideos(selection: readonly string[], signal: AbortSignal, logger: Logger): Promise<VideoReport[]> {
  const videos = selectVideos(selection);
  requireLabels(videos);
  requireNoEnvironment();
  await requireRealClient(videos);
  const ffmpeg = await resolveFfmpeg(logger, FFMPEG_DIRECTORIES);
  logger.info(`composing with ${ffmpeg.description}`);
  const version = productVersion();
  const reports: VideoReport[] = [];
  for (const video of videos) {
    if (signal.aborted) throw cancellationError(signal);
    reports.push(...(await recordVideo(video, ffmpeg, { version, signal, logger })));
  }
  return reports;
}
