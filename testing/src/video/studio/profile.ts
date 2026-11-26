import type { Viewport } from "./stage";
import { VIDEO_SIZE } from "./theme";

export type ProfileId = "landscape" | "vertical";

export interface VideoProfile extends Viewport {
  readonly id: ProfileId;
  readonly fileSuffix: string;
}

export const LANDSCAPE: VideoProfile = { id: "landscape", width: VIDEO_SIZE.width, height: VIDEO_SIZE.height, fileSuffix: "" };

export const VERTICAL: VideoProfile = { id: "vertical", width: VIDEO_SIZE.height, height: VIDEO_SIZE.width, fileSuffix: "-vertical" };
