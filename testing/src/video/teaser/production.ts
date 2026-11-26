import { join } from "node:path";
import type { Logger } from "../../lib/log";
import { removePath } from "../../lib/util";
import { MediaProbe } from "../audio/probe";
import { Composer } from "../composer";
import type { TeaserVideo } from "../definition";
import type { Ffmpeg } from "../ffmpeg";
import type { ManifestEntry } from "../manifest";
import { requireCleanMix } from "../production";
import { contactSheetPrefix, videoFile } from "../output";
import type { CutAlignment, VideoReport } from "../report";
import type { VideoProfile } from "../studio/profile";
import type { Studio } from "../studio/studio";
import { FRAME_RATE } from "../studio/theme";
import { TeaserCompositor } from "./compositor";
import type { TeaserEdit } from "./edit";
import { TeaserLayout } from "./layout";
import { ScoreMixer, type PreparedScore } from "./score";

const MAX_SECONDS = 75;
const FRAME_SECONDS = 1 / FRAME_RATE;

export interface ProducedCut {
  readonly report: VideoReport;
  readonly manifest: ManifestEntry;
}

export class TeaserProducer {
  private readonly video: TeaserVideo;
  private readonly ffmpeg: Ffmpeg;
  private readonly workDir: string;
  private readonly logger: Logger;
  private readonly mixer: ScoreMixer;

  constructor(video: TeaserVideo, ffmpeg: Ffmpeg, workDir: string, logger: Logger) {
    this.video = video;
    this.ffmpeg = ffmpeg;
    this.workDir = workDir;
    this.logger = logger;
    this.mixer = new ScoreMixer(ffmpeg, workDir, video.score);
  }

  async prepare(): Promise<PreparedScore> {
    const prepared = await this.mixer.prepare();
    const grid = prepared.clock.dropGrid;
    this.logger.info(`${this.video.score.drop.title}: ${(60 / grid.period).toFixed(2)} bpm, ${String(grid.fitted)} beats on the grid, worst ${(grid.worstOffset * 1000).toFixed(0)} ms off`);
    return prepared;
  }

  async produce(prepared: PreparedScore, edit: TeaserEdit, studio: Studio, version: string): Promise<ProducedCut[]> {
    const soundtrack = await this.mixer.mix(prepared, edit.seconds);
    const audioQa = await this.mixer.inspect(prepared, soundtrack, edit.seconds);
    requireCleanMix(this.video.id, audioQa);
    const loudness = await new MediaProbe(this.ffmpeg).loudness(soundtrack);
    const cuts = alignment(edit, prepared);
    const produced: ProducedCut[] = [];
    for (const profile of this.video.cuts) {
      const id = `${this.video.id}${profile.fileSuffix}`;
      const frames = join(this.workDir, `frames-${profile.id}`);
      const page = await studio.canvas(profile);
      try {
        const compositor = new TeaserCompositor(page, edit, new TeaserLayout(profile));
        await compositor.load();
        await compositor.render(frames);
      } finally {
        await page.context().close();
      }
      const composer = new Composer(this.ffmpeg, this.workDir, profile);
      const file = await composer.encodeSequence(frames, edit.seconds, soundtrack, videoFile(id), MAX_SECONDS);
      removePath(frames);
      const contactSheets = await composer.contactSheets(file.path, contactSheetPrefix(id));
      produced.push({
        report: { ...file, id, title: titleOf(this.video, profile), wikiPage: null, contactSheets, warnings: [...warnings(cuts), ...audioQa.warnings], timing: [], loudness: { mix: loudness, voice: null }, cuts, audioQa },
        manifest: {
          id,
          title: titleOf(this.video, profile),
          wikiPage: null,
          modlVersion: version,
          seconds: Number(file.seconds.toFixed(2)),
          bytes: file.bytes,
          sha256: file.sha256,
          audio: { narration: null, music: [...prepared.credits] },
        },
      });
    }
    return produced;
  }
}

function titleOf(video: TeaserVideo, profile: VideoProfile): string {
  return profile.fileSuffix === "" ? video.title : `${video.title} (${profile.id})`;
}

function alignment(edit: TeaserEdit, prepared: PreparedScore): CutAlignment[] {
  return edit.shots.slice(1).map((shot) => {
    const beat = prepared.clock.nearest(shot.start);
    return { shot: shot.id, at: shot.start, beat, offsetMs: Math.round((shot.start - beat) * 1000) };
  });
}

function warnings(cuts: readonly CutAlignment[]): string[] {
  return cuts.filter((cut) => Math.abs(cut.offsetMs) > FRAME_SECONDS * 1000).map((cut) => `${cut.shot} starts ${String(cut.offsetMs)} ms off the nearest beat`);
}
