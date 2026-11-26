import type { LabelSink } from "./pointer";
import type { CaptionCue, VoiceCue } from "./segment";
import type { TimedClip } from "./voice-track";

export interface TimedCaption {
  text: string | null;
  atMs: number;
}

export class CaptionTrack implements LabelSink {
  private readonly events: TimedCaption[] = [];

  show(text: string | null): void {
    this.events.push({ text, atMs: Date.now() });
  }

  drain(): TimedCaption[] {
    return this.events.splice(0);
  }
}

export interface PlacedCues {
  captions: CaptionCue[];
  voices: VoiceCue[];
}

export function placeCues(captions: readonly TimedCaption[], spoken: readonly TimedClip[], firstFrameAtMs: number): PlacedCues {
  const offset = (atMs: number) => Math.max(0, (atMs - firstFrameAtMs) / 1000);
  return {
    captions: captions.map((event) => ({ text: event.text, at: offset(event.atMs) })),
    voices: spoken.map((cue) => ({ clip: cue.clip, at: offset(cue.atMs) })),
  };
}
