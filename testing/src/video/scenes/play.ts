import type { FrameSegment, SpokenClip } from "../studio/segment";
import type { Take } from "../studio/take";
import { sceneCall } from "./document";

export interface RevealBeat {
  say?: SpokenClip;
  focus?: readonly number[];
  label?: string | null;
  reveal?: readonly number[];
  mark?: boolean;
  stepMs?: number;
  holdMs: number;
}

const DEFAULT_STEP_MS = 380;
const LEAD_MS = 450;

export function indexes(count: number): number[] {
  return Array.from({ length: count }, (_, index) => index);
}

export async function recordReveal(take: Take, beats: readonly RevealBeat[], shownAtStart: readonly number[] = []): Promise<FrameSegment> {
  const narration = take.narration();
  for (const index of shownAtStart) await take.page.evaluate(sceneCall("reveal", index));
  await take.start();
  await narration.hold(LEAD_MS);
  for (const beat of beats) {
    if (beat.say) await take.say(beat.say);
    if (beat.label !== undefined) await narration.label(beat.label);
    for (const index of beat.reveal ?? []) {
      await take.page.evaluate(sceneCall("reveal", index));
      await narration.hold(beat.stepMs ?? DEFAULT_STEP_MS);
    }
    if (beat.focus) await take.page.evaluate(sceneCall("focus", ...beat.focus));
    if (beat.mark) await take.page.evaluate(sceneCall("mark"));
    await narration.hold(beat.holdMs);
  }
  await narration.settleLabel();
  const segment = await take.stop();
  await take.page.close();
  return segment;
}
