import type { MixQa } from "./audio/mix-qa";
import type { VideoFile } from "./composer";
import type { VoiceTiming } from "./narration-plan";
import type { ProducedLoudness } from "./production";

export interface CutAlignment {
  shot: string;
  at: number;
  beat: number;
  offsetMs: number;
}

export interface VideoReport extends VideoFile {
  id: string;
  title: string;
  wikiPage: string | null;
  contactSheets: string[];
  warnings: string[];
  timing: VoiceTiming[];
  loudness: ProducedLoudness | null;
  cuts: CutAlignment[];
  audioQa: MixQa | null;
}
