import { createHash } from "node:crypto";
import { copyFileSync, existsSync, mkdirSync, readdirSync } from "node:fs";
import { join } from "node:path";
import type { Logger } from "../../lib/log";
import { errorMessage, removePath } from "../../lib/util";
import { writeAtomically } from "../assets/atomic-file";
import { AUDIO_SAMPLE_RATE } from "../audio/format";
import { MediaProbe } from "../audio/probe";
import type { Ffmpeg } from "../ffmpeg";
import type { SpokenClip } from "../studio/segment";
import { wordCount } from "../text";
import { sentencesOf, spokenText } from "./pronunciation";
import type { SpeechEngine } from "./speech-engine";
import { TTS_CLIP_DIR, voiceIdentity, type VoiceSetting } from "./voice";

const TAKE_RULES = {
  maxTakes: 3,
  nativeWordsPerSecond: 3.2,
  lengthRatio: { lowest: 0.5, highest: 1.9 },
  clippingDb: -0.1,
  silenceDb: -45,
  longestPauseSeconds: 0.9,
  edgeSeconds: 0.2,
} as const;

const PACING = {
  sentencePauseSeconds: 0.5,
  targetWordsPerSecond: 2.5,
  slowestTempo: 0.78,
} as const;

const TRIM_SILENCE = [
  "silenceremove=start_periods=1:start_threshold=-50dB:start_silence=0.05",
  "areverse",
  "silenceremove=start_periods=1:start_threshold=-50dB:start_silence=0.05",
  "areverse",
].join(",");

const SHAPING = ["highpass=f=80", "acompressor=threshold=-18dB:ratio=2:attack=10:release=150"].join(",");

export const CLIP_LEVEL = { lufs: -18, ceilingDb: -6.5, edgeSeconds: 0.008, spreadLu: 1 } as const;

function limiting(gainDb: number): string {
  return [`volume=${gainDb.toFixed(2)}dB`, `alimiter=limit=${(10 ** (CLIP_LEVEL.ceilingDb / 20)).toFixed(4)}:attack=5:release=50:level=0`].join(",");
}

function trimming(gainDb: number, seconds: number): string {
  const fadeOut = Math.max(0, seconds - CLIP_LEVEL.edgeSeconds);
  return [
    `volume=${gainDb.toFixed(2)}dB`,
    `afade=t=in:st=0:d=${String(CLIP_LEVEL.edgeSeconds)}`,
    `afade=t=out:st=${fadeOut.toFixed(3)}:d=${String(CLIP_LEVEL.edgeSeconds)}`,
    `aresample=${String(AUDIO_SAMPLE_RATE)}`,
  ].join(",");
}

function sentenceInput(index: number, last: boolean): string {
  const pause = last ? "" : `,apad=pad_dur=${String(PACING.sentencePauseSeconds)}`;
  return `[${String(index)}:a]aresample=${String(AUDIO_SAMPLE_RATE)},aformat=channel_layouts=mono${pause}[s${String(index)}]`;
}

function sha256(value: unknown): string {
  return createHash("sha256").update(JSON.stringify(value)).digest("hex");
}

interface Sentence {
  key: string;
  text: string;
}

export class SpeechRenderer {
  private readonly engine: SpeechEngine;
  private readonly voice: VoiceSetting;
  private readonly ffmpeg: Ffmpeg;
  private readonly probe: MediaProbe;
  private readonly logger: Logger;

  constructor(engine: SpeechEngine, voice: VoiceSetting, ffmpeg: Ffmpeg, logger: Logger) {
    this.engine = engine;
    this.voice = voice;
    this.ffmpeg = ffmpeg;
    this.probe = new MediaProbe(ffmpeg);
    this.logger = logger;
    mkdirSync(TTS_CLIP_DIR, { recursive: true });
  }

  async render(text: string): Promise<SpokenClip> {
    const { sentences, file } = this.plan(text);
    if (!existsSync(file)) {
      for (const sentence of sentences) await this.renderSentence(sentence);
      await this.assemble(sentences, file);
    }
    return { text, file, seconds: await this.probe.seconds(file) };
  }

  async requireEvenLevels(clips: readonly SpokenClip[]): Promise<void> {
    const levels: { text: string; lufs: number }[] = [];
    for (const clip of clips) levels.push({ text: clip.text, lufs: (await this.probe.loudness(clip.file)).integratedLufs });
    const quietest = Math.min(...levels.map((level) => level.lufs));
    const loudest = Math.max(...levels.map((level) => level.lufs));
    if (loudest - quietest > CLIP_LEVEL.spreadLu) {
      throw new Error(`narration clips differ by ${(loudest - quietest).toFixed(2)} LU (at most ${String(CLIP_LEVEL.spreadLu)}): ${levels.map((level) => `${level.lufs.toFixed(1)} "${level.text.slice(0, 32)}"`).join("; ")}`);
    }
  }

  pruneAllExcept(lines: readonly string[]): number {
    const kept = new Set(
      lines.flatMap((line) => {
        const { sentences, file } = this.plan(line);
        return [file, ...sentences.map((sentence) => this.sentenceFile(sentence))];
      }),
    );
    const stale = readdirSync(TTS_CLIP_DIR)
      .map((name) => join(TTS_CLIP_DIR, name))
      .filter((path) => /\/(sentence|scene)-[0-9a-f]{64}\.wav$/.test(path) && !kept.has(path));
    for (const path of stale) removePath(path);
    return stale.length;
  }

  private plan(text: string): { sentences: Sentence[]; file: string } {
    const sentences = sentencesOf(spokenText(text)).map((sentence) => ({
      text: sentence,
      key: sha256({ engine: this.engine.id, voice: voiceIdentity(this.voice), rules: TAKE_RULES, trim: TRIM_SILENCE, text: sentence }),
    }));
    const file = join(TTS_CLIP_DIR, `scene-${sha256({ sentences: sentences.map((sentence) => sentence.key), PACING, SHAPING, CLIP_LEVEL })}.wav`);
    return { sentences, file };
  }

  private sentenceFile(sentence: Sentence): string {
    return join(TTS_CLIP_DIR, `sentence-${sentence.key}.wav`);
  }

  private async renderSentence(sentence: Sentence): Promise<void> {
    const file = this.sentenceFile(sentence);
    if (existsSync(file)) return;
    const problems: string[] = [];
    for (let take = 1; take <= TAKE_RULES.maxTakes; take += 1) {
      const raw = join(TTS_CLIP_DIR, `take-${sentence.key}-${String(take)}.wav`);
      const trimmed = join(TTS_CLIP_DIR, `take-${sentence.key}-${String(take)}-trimmed.wav`);
      try {
        await this.engine.speak(sentence.text, this.voice, raw);
        await this.ffmpeg.ffmpeg(["-i", this.ffmpeg.path(raw), "-af", TRIM_SILENCE, this.ffmpeg.path(trimmed)]);
        const rejected = await this.rejection(trimmed, sentence.text).catch((error: unknown) => `unreadable take (${errorMessage(error)})`);
        if (rejected === null) {
          await writeAtomically(file, (staged) => {
            copyFileSync(trimmed, staged);
          }, ".wav");
          return;
        }
        problems.push(`take ${String(take)}: ${rejected}`);
        this.logger.info(`rejected a ${this.voice.name} take of "${sentence.text}": ${rejected}`);
      } finally {
        removePath(raw);
        removePath(trimmed);
      }
    }
    throw new Error(`no clean take of "${sentence.text}" in ${String(TAKE_RULES.maxTakes)} tries (${problems.join("; ")})`);
  }

  private async rejection(file: string, text: string): Promise<string | null> {
    const seconds = await this.probe.seconds(file);
    const expected = wordCount(text) / TAKE_RULES.nativeWordsPerSecond;
    const ratio = seconds / expected;
    if (ratio < TAKE_RULES.lengthRatio.lowest || ratio > TAKE_RULES.lengthRatio.highest) {
      return `length ${seconds.toFixed(2)} s for about ${expected.toFixed(2)} s of text`;
    }
    const peak = await this.probe.peakDb(file);
    if (peak >= TAKE_RULES.clippingDb) return `clipping (peak ${peak.toFixed(1)} dBFS)`;
    const pause = (await this.probe.silences(file, TAKE_RULES.silenceDb, TAKE_RULES.longestPauseSeconds)).find(
      (silence) => silence.start > TAKE_RULES.edgeSeconds && silence.end < seconds - TAKE_RULES.edgeSeconds,
    );
    if (pause) return `internal silence of ${(pause.end - pause.start).toFixed(2)} s at ${pause.start.toFixed(2)} s`;
    return null;
  }

  private async assemble(sentences: readonly Sentence[], output: string): Promise<void> {
    const inputs = sentences.flatMap((sentence) => ["-i", this.ffmpeg.path(this.sentenceFile(sentence))]);
    const padded = sentences.map((_, index) => sentenceInput(index, index === sentences.length - 1));
    const joined = `${sentences.map((_, index) => `[s${String(index)}]`).join("")}concat=n=${String(sentences.length)}:v=0:a=1[joined]`;
    const words = wordCount(sentences.map((sentence) => sentence.text).join(" "));
    await writeAtomically(output, async (finished) => {
      const joinedFile = `${finished}.joined.wav`;
      const shapedFile = `${finished}.shaped.wav`;
      const limitedFile = `${finished}.limited.wav`;
      try {
        await this.ffmpeg.ffmpeg([...inputs, "-filter_complex", [...padded, joined].join(";"), "-map", "[joined]", this.ffmpeg.path(joinedFile)]);
        const spokenRate = words / (await this.probe.seconds(joinedFile));
        const tempo = Math.min(1, Math.max(PACING.slowestTempo, PACING.targetWordsPerSecond / spokenRate));
        await this.ffmpeg.ffmpeg(["-i", this.ffmpeg.path(joinedFile), "-af", `atempo=${tempo.toFixed(3)},${SHAPING}`, "-ac", "1", "-c:a", "pcm_f32le", this.ffmpeg.path(shapedFile)]);
        const gain = CLIP_LEVEL.lufs - (await this.probe.loudness(shapedFile)).integratedLufs;
        await this.ffmpeg.ffmpeg(["-i", this.ffmpeg.path(shapedFile), "-af", limiting(gain), "-ac", "1", "-c:a", "pcm_f32le", this.ffmpeg.path(limitedFile)]);
        const trim = CLIP_LEVEL.lufs - (await this.probe.loudness(limitedFile)).integratedLufs;
        const seconds = await this.probe.seconds(limitedFile);
        await this.ffmpeg.ffmpeg(["-i", this.ffmpeg.path(limitedFile), "-af", trimming(trim, seconds), "-ac", "1", this.ffmpeg.path(finished)]);
      } finally {
        removePath(joinedFile);
        removePath(shapedFile);
        removePath(limitedFile);
      }
    }, ".wav");
  }
}
