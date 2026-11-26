import type { Environment } from "../lib/environment";
import type { Ffmpeg } from "./ffmpeg";
import type { Logger } from "../lib/log";
import type { TopologyName } from "../lib/state";
import type { StepLabel } from "./labels";
import type { VideoProfile } from "./studio/profile";
import type { Segment, SpokenClip } from "./studio/segment";
import type { Studio } from "./studio/studio";
import type { TeaserEdit } from "./teaser/edit";
import type { BeatClock, Score } from "./teaser/score";

interface RecordingContext {
  readonly environment: Environment;
  readonly studio: Studio;
  readonly logger: Logger;
  readonly workDir: string;
}

export interface VideoContext extends RecordingContext {
  readonly ffmpeg: Ffmpeg;
  text(label: StepLabel): string;
  speech(line: string): SpokenClip;
}

export interface TeaserContext extends RecordingContext {
  readonly clock: BeatClock;
}

interface VideoBase {
  readonly id: string;
  readonly title: string;
  readonly topology: TopologyName;
  readonly realClient?: boolean;
}

interface TutorialBase extends VideoBase {
  readonly wikiPage: string;
  readonly labels: readonly StepLabel[];
  record(context: VideoContext): Promise<Segment[]>;
}

export interface LabelledVideo extends TutorialBase {
  readonly presentation: "labels";
}

export interface NarratedVideo extends TutorialBase {
  readonly presentation: "narrated";
  readonly narration: readonly string[];
}

export interface TeaserVideo extends VideoBase {
  readonly presentation: "teaser";
  readonly score: Score;
  readonly cuts: readonly VideoProfile[];
  record(context: TeaserContext): Promise<TeaserEdit>;
}

export type TutorialVideo = LabelledVideo | NarratedVideo;

export type VideoDefinition = TutorialVideo | TeaserVideo;
