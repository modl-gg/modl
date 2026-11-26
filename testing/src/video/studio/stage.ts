import { CSS_VIEWPORT, DEVICE_SCALE, VIDEO_SIZE } from "./theme";

export type LabelPlacement = "overlay" | "captions";

export interface Viewport {
  readonly width: number;
  readonly height: number;
}

export interface CaptionBand {
  readonly top: number;
  readonly height: number;
}

export interface StageLayout {
  readonly viewport: Viewport;
  readonly deviceScale: number;
  readonly labels: LabelPlacement;
  readonly requireUncut: boolean;
}

const CAPTIONED_VIEWPORT: Viewport = { width: CSS_VIEWPORT.width, height: 644 };
const CAPTIONED_CONTENT_HEIGHT = Math.round(CAPTIONED_VIEWPORT.height * DEVICE_SCALE);
const FOOTAGE_SCALE = 2;

export const CAPTION_BAND: CaptionBand = { top: CAPTIONED_CONTENT_HEIGHT, height: VIDEO_SIZE.height - CAPTIONED_CONTENT_HEIGHT };

export const OVERLAY_STAGE: StageLayout = { viewport: CSS_VIEWPORT, deviceScale: DEVICE_SCALE, labels: "overlay", requireUncut: true };

export const CAPTIONED_STAGE: StageLayout = { viewport: CAPTIONED_VIEWPORT, deviceScale: DEVICE_SCALE, labels: "captions", requireUncut: true };

export const FOOTAGE_STAGE: StageLayout = { viewport: CSS_VIEWPORT, deviceScale: FOOTAGE_SCALE, labels: "captions", requireUncut: false };

export function framePixels(stage: StageLayout): Viewport {
  return { width: Math.round(stage.viewport.width * stage.deviceScale), height: Math.round(stage.viewport.height * stage.deviceScale) };
}
