import { join } from "node:path";
import { CACHE_DIR } from "../../lib/paths";
import type { PinnedFile } from "../assets/pinned-file";
import { KYUTAI_REPOSITORY, KYUTAI_REVISION } from "./kyutai";

export const TTS_HOME = join(CACHE_DIR, "video-tts");
export const TTS_CLIP_DIR = join(TTS_HOME, "clips");

export type VoiceSource = { kind: "builtin" } | { kind: "embedding"; origin: string; file: PinnedFile };

export interface VoiceSetting {
  name: string;
  licence: string;
  source: VoiceSource;
}

function pinnedEmbedding(name: string, sha256: string): VoiceSource {
  const path = `languages/english/embeddings/${name}.safetensors`;
  return {
    kind: "embedding",
    origin: `${KYUTAI_REPOSITORY}@${KYUTAI_REVISION}/${path}`,
    file: {
      url: `https://huggingface.co/${KYUTAI_REPOSITORY}/resolve/${KYUTAI_REVISION}/${path}`,
      sha256,
      path: join(TTS_HOME, "voices", `${name}-${sha256.slice(0, 12)}.safetensors`),
    },
  };
}

export const JUERGEN: VoiceSetting = {
  name: "juergen",
  licence: "used with the permission of the modl maintainers (no published licence)",
  source: pinnedEmbedding("juergen", "9ef4461c8ba702d1928ae5e75272035a7117efccf6c9fa2378b1da92b252f78f"),
};

export const MARIUS: VoiceSetting = {
  name: "marius",
  licence: "CC0 1.0 (kyutai/tts-voices, voice-donations)",
  source: pinnedEmbedding("marius", "05621a1fa88bfe110063dd6fd206d0262891e374a36a04edc309317e961f14c9"),
};

export const PETER_YEARSLEY: VoiceSetting = {
  name: "peter_yearsley",
  licence: "CC0 1.0 (kyutai/tts-voices, voice-zero)",
  source: pinnedEmbedding("peter_yearsley", "f0eb3f23bb8e2ce5203a27ee4369ebeba07eda564cecebd84ccbdb4d9507cbca"),
};

export const NARRATION_VOICE: VoiceSetting = JUERGEN;

export const VOICE_SAMPLE_LINE = "First, turn on modern forwarding in Velocity, so players reach your backend servers only through the proxy.";

export const SAMPLE_VOICES: readonly VoiceSetting[] = [JUERGEN, MARIUS, PETER_YEARSLEY];

export function voiceIdentity(voice: VoiceSetting): string {
  return voice.source.kind === "builtin" ? `builtin:${voice.name}` : `embedding:${voice.name}:${voice.source.file.sha256}`;
}

export function voiceOrigin(voice: VoiceSetting): string {
  return voice.source.kind === "builtin" ? `pocket-tts built-in voice ${voice.name}` : voice.source.origin;
}
