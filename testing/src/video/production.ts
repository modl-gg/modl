import type { Logger } from "../lib/log";
import { BACKGROUND_MUSIC, musicCredit, requireMusic } from "./audio/music";
import { MediaProbe, type Loudness } from "./audio/probe";
import { MixInspector, windowTable, type MixQa } from "./audio/mix-qa";
import { MIX_TARGET, narratedQaSections, SoundtrackMixer } from "./audio/soundtrack";
import type { Composer, PreparedVideo, VideoFile } from "./composer";
import type { NarratedVideo, TutorialVideo } from "./definition";
import type { Ffmpeg } from "./ffmpeg";
import type { AudioCredits } from "./manifest";
import { captionOverlays, placedVoices, requireNoOverlap, voiceTimings, type VoiceTiming } from "./narration-plan";
import type { SpokenClip } from "./studio/segment";
import { CAPTION_BAND, CAPTIONED_STAGE, OVERLAY_STAGE, type StageLayout } from "./studio/stage";
import type { Studio } from "./studio/studio";
import { POCKET_TTS, PocketTts } from "./voice/pocket-tts";
import { SpeechRenderer } from "./voice/speech-renderer";
import { NARRATION_VOICE, voiceOrigin } from "./voice/voice";

export interface ProducedLoudness {
  mix: Loudness;
  voice: Loudness | null;
}

export interface ProductionResult {
  file: VideoFile;
  timing: VoiceTiming[];
  loudness: ProducedLoudness | null;
  audio: AudioCredits | null;
  audioQa: MixQa | null;
}

export interface Production {
  readonly stage: StageLayout;
  prepare?(): Promise<void>;
  speech(line: string): SpokenClip;
  finish(prepared: PreparedVideo, composer: Composer, studio: Studio, output: string): Promise<ProductionResult>;
}

const MAX_SECONDS = { labels: 90, narrated: 150 } as const;

class LabelledProduction implements Production {
  readonly stage = OVERLAY_STAGE;

  speech(line: string): SpokenClip {
    throw new Error(`"${line}" was requested, but this video has no narration`);
  }

  async finish(prepared: PreparedVideo, composer: Composer, _studio: Studio, output: string): Promise<ProductionResult> {
    const file = await composer.encode(prepared, output, { overlays: [], audio: null, maxSeconds: MAX_SECONDS.labels });
    return { file, timing: [], loudness: null, audio: null, audioQa: null };
  }
}

function audioCredits(): AudioCredits {
  const source = NARRATION_VOICE.source;
  return {
    narration: {
      engine: POCKET_TTS.pythonPackage,
      model: `${POCKET_TTS.model.repository}@${POCKET_TTS.model.revision}/${POCKET_TTS.model.file}`,
      modelSha256: POCKET_TTS.model.sha256,
      modelLicence: POCKET_TTS.model.licence,
      voice: NARRATION_VOICE.name,
      voiceSource: voiceOrigin(NARRATION_VOICE),
      voiceSha256: source.kind === "embedding" ? source.file.sha256 : null,
      voiceLicence: NARRATION_VOICE.licence,
    },
    music: [musicCredit(BACKGROUND_MUSIC)],
  };
}

export function requireCleanMix(id: string, qa: MixQa): void {
  if (qa.problems.length > 0) throw new Error(`the ${id} mix fails its loudness check: ${qa.problems.join("; ")}\n${windowTable(qa)}`);
}

function requireMixTarget(file: string, loudness: Loudness): void {
  const offTarget = Math.abs(loudness.integratedLufs - MIX_TARGET.integratedLufs) > MIX_TARGET.toleranceLu;
  if (offTarget || loudness.truePeakDbtp > MIX_TARGET.truePeakCeilingDbtp) {
    throw new Error(
      `${file} measures ${String(loudness.integratedLufs)} LUFS and ${String(loudness.truePeakDbtp)} dBTP, outside ${String(MIX_TARGET.integratedLufs)} +/- ${String(MIX_TARGET.toleranceLu)} LUFS with a true peak at most ${String(MIX_TARGET.truePeakCeilingDbtp)} dBTP`,
    );
  }
}

class NarratedProduction implements Production {
  readonly stage = CAPTIONED_STAGE;
  private readonly video: NarratedVideo;
  private readonly ffmpeg: Ffmpeg;
  private readonly workDir: string;
  private readonly logger: Logger;
  private readonly keptNarration: readonly string[];
  private readonly clips = new Map<string, SpokenClip>();
  private renderer: SpeechRenderer | null = null;
  private music: string | null = null;

  constructor(video: NarratedVideo, ffmpeg: Ffmpeg, workDir: string, logger: Logger, keptNarration: readonly string[]) {
    this.video = video;
    this.keptNarration = keptNarration;
    this.ffmpeg = ffmpeg;
    this.workDir = workDir;
    this.logger = logger;
  }

  async prepare(): Promise<void> {
    if (this.video.narration.length === 0) throw new Error(`${this.video.id} is narrated but has no narration`);
    const renderer = new SpeechRenderer(new PocketTts(), NARRATION_VOICE, this.ffmpeg, this.logger);
    for (const line of this.video.narration) this.clips.set(line, await renderer.render(line));
    await renderer.requireEvenLevels([...this.clips.values()]);
    this.renderer = renderer;
    this.music = await requireMusic(BACKGROUND_MUSIC);
  }

  speech(line: string): SpokenClip {
    const clip = this.clips.get(line);
    if (!clip) throw new Error(`no narration was rendered for "${line}"`);
    return clip;
  }

  async finish(prepared: PreparedVideo, composer: Composer, studio: Studio, output: string): Promise<ProductionResult> {
    const timing = voiceTimings(prepared);
    requireNoOverlap(timing);
    const overlays = await captionOverlays(prepared, studio, CAPTION_BAND);
    const mixer = new SoundtrackMixer(this.ffmpeg, this.workDir);
    if (this.music === null || this.renderer === null) throw new Error(`${this.video.id} was not prepared`);
    const soundtrack = await mixer.mix(placedVoices(prepared), this.music, prepared.seconds);
    const audioQa = await new MixInspector(this.ffmpeg).inspect(soundtrack.file, narratedQaSections(prepared.seconds), MIX_TARGET.truePeakCeilingDbtp);
    requireCleanMix(this.video.id, audioQa);
    const file = await composer.encode(prepared, output, { overlays, audio: soundtrack.file, maxSeconds: MAX_SECONDS.narrated });
    const shipped = await new MediaProbe(this.ffmpeg).loudness(file.path);
    requireMixTarget(file.path, shipped);
    const pruned = this.renderer.pruneAllExcept(this.keptNarration);
    if (pruned > 0) this.logger.info(`removed ${String(pruned)} unused narration clips`);
    return { file, timing, loudness: { mix: shipped, voice: soundtrack.voice }, audio: audioCredits(), audioQa };
  }
}

export function productionFor(video: TutorialVideo, ffmpeg: Ffmpeg, workDir: string, logger: Logger, keptNarration: readonly string[]): Production {
  switch (video.presentation) {
    case "labels":
      return new LabelledProduction();
    case "narrated":
      return new NarratedProduction(video, ffmpeg, workDir, logger, keptNarration);
  }
}
