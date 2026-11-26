export interface TimedFrame {
  file: string;
  seconds: number;
}

export interface SpokenClip {
  text: string;
  file: string;
  seconds: number;
}

export interface CaptionCue {
  text: string | null;
  at: number;
}

export interface VoiceCue {
  clip: SpokenClip;
  at: number;
}

export interface FrameSegment {
  kind: "frames";
  name: string;
  frames: readonly TimedFrame[];
  captions: readonly CaptionCue[];
  voices: readonly VoiceCue[];
}

export interface StillSegment {
  kind: "still";
  name: string;
  image: string;
  seconds: number;
}

export type Segment = FrameSegment | StillSegment;
