import { copyFileSync, mkdirSync } from "node:fs";
import { join } from "node:path";
import { cancellable } from "../lib/cancellation";
import type { Logger } from "../lib/log";
import { resolveFfmpeg } from "./ffmpeg";
import { FFMPEG_DIRECTORIES, VIDEO_DIR } from "./output";
import { PocketTts } from "./voice/pocket-tts";
import { SpeechRenderer } from "./voice/speech-renderer";
import { SAMPLE_VOICES, VOICE_SAMPLE_LINE, voiceOrigin } from "./voice/voice";

export interface VoiceSample {
  voice: string;
  licence: string;
  source: string;
  file: string;
  seconds: number;
}

export async function renderVoiceSamples(signal: AbortSignal, logger: Logger): Promise<VoiceSample[]> {
  return cancellable(signal, async () => {
    const ffmpeg = await resolveFfmpeg(logger, FFMPEG_DIRECTORIES);
    const engine = new PocketTts();
    const directory = join(VIDEO_DIR, "voice-samples");
    mkdirSync(directory, { recursive: true });
    const samples: VoiceSample[] = [];
    for (const voice of SAMPLE_VOICES) {
      const clip = await new SpeechRenderer(engine, voice, ffmpeg, logger).render(VOICE_SAMPLE_LINE);
      const file = join(directory, `${voice.name}.wav`);
      copyFileSync(clip.file, file);
      samples.push({ voice: voice.name, licence: voice.licence, source: voiceOrigin(voice), file, seconds: Number(clip.seconds.toFixed(2)) });
    }
    return samples;
  });
}
