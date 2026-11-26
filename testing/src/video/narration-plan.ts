import type { PlacedVoice } from "./audio/soundtrack";
import type { PreparedVideo, SceneTiming, TimedOverlay } from "./composer";
import type { FrameSegment } from "./studio/segment";
import type { CaptionBand } from "./studio/stage";

const SHORTEST_CAPTION_SECONDS = 0.3;

export interface CaptionImages {
  caption(text: string): Promise<string>;
}

export interface VoiceTiming {
  scene: string;
  sceneStart: number;
  sceneEnd: number;
  nextSceneStart: number | null;
  clipStart: number;
  clipSeconds: number;
  clipEnd: number;
  text: string;
}

function frameScenes(prepared: PreparedVideo): { scene: SceneTiming & { segment: FrameSegment }; next: SceneTiming | undefined }[] {
  return prepared.scenes.flatMap((scene, index) =>
    scene.segment.kind === "frames" ? [{ scene: { ...scene, segment: scene.segment }, next: prepared.scenes[index + 1] }] : [],
  );
}

export async function captionOverlays(prepared: PreparedVideo, images: CaptionImages, band: CaptionBand): Promise<TimedOverlay[]> {
  const rendered = new Map<string, string>();
  const overlays: TimedOverlay[] = [];
  for (const { scene, next } of frameScenes(prepared)) {
    const boundary = next?.start ?? scene.end;
    const cues = scene.segment.captions;
    for (const [index, cue] of cues.entries()) {
      if (cue.text === null) continue;
      const start = scene.start + cue.at;
      const end = Math.min(boundary, scene.start + (cues[index + 1]?.at ?? Number.POSITIVE_INFINITY));
      if (end - start < SHORTEST_CAPTION_SECONDS) continue;
      const image = rendered.get(cue.text) ?? (await images.caption(cue.text));
      rendered.set(cue.text, image);
      overlays.push({ image, start, end, band });
    }
  }
  return overlays;
}

export function voiceTimings(prepared: PreparedVideo): VoiceTiming[] {
  return frameScenes(prepared).flatMap(({ scene, next }) =>
    scene.segment.voices.map((cue) => {
      const clipStart = scene.start + cue.at;
      return {
        scene: scene.segment.name,
        sceneStart: scene.start,
        sceneEnd: scene.end,
        nextSceneStart: next?.start ?? null,
        clipStart,
        clipSeconds: cue.clip.seconds,
        clipEnd: clipStart + cue.clip.seconds,
        text: cue.clip.text,
      };
    }),
  );
}

export function requireNoOverlap(timings: readonly VoiceTiming[]): void {
  const overlapping = timings.filter((timing, index) => {
    const following = timings[index + 1];
    const overlapsNextClip = following !== undefined && following.clipStart < timing.clipEnd;
    return overlapsNextClip || timing.clipEnd > (timing.nextSceneStart ?? timing.sceneEnd);
  });
  if (overlapping.length > 0) {
    throw new Error(`narration overlaps the next clip or scene: ${overlapping.map((timing) => `${timing.scene} ends ${timing.clipEnd.toFixed(2)} s`).join(", ")}`);
  }
}

export function placedVoices(prepared: PreparedVideo): PlacedVoice[] {
  return frameScenes(prepared).flatMap(({ scene }) => scene.segment.voices.map((cue) => ({ file: cue.clip.file, start: scene.start + cue.at, seconds: cue.clip.seconds })));
}
