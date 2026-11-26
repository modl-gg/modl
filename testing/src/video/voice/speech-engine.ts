import type { VoiceSetting } from "./voice";

export interface SpeechEngine {
  readonly id: string;
  speak(text: string, voice: VoiceSetting, output: string): Promise<void>;
}
