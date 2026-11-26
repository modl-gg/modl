import type { VideoProfile } from "../studio/profile";
import type { Viewport } from "../studio/stage";
import type { ConsoleLine } from "../scenes/console";
import { boxCrop, pushInTarget, requireWholeText, screenCrop } from "./crop";
import type { Footage, Pane, Region } from "./edit";
import { clamp, lerp } from "./motion";

export type Box = Viewport;

export type SplitDirection = "row" | "column";

const LANDSCAPE_SCREEN: Box = { width: 1640, height: 922 };
const VERTICAL_WIDTH = 1000;
const VERTICAL_TALLEST = 1560;
const VERTICAL_NARROWEST_CROP = 420;
export const SPLIT_GAP = 28;
const CONSOLE_CHARACTERS = { landscape: 104, vertical: 61 } as const;
const CONSOLE_ROW = { landscape: 40, vertical: 39 } as const;
const CONSOLE_CHROME = { landscape: 140, vertical: 160 } as const;

const LANDSCAPE_PANES: Record<"console" | "game", Box> = {
  console: { width: 1640, height: 900 },
  game: { width: 1640, height: 922 },
};

const VERTICAL_PANES: Record<"console" | "game", Box> = {
  console: { width: VERTICAL_WIDTH, height: 1500 },
  game: { width: VERTICAL_WIDTH, height: 1300 },
};

export interface Framing {
  readonly start: Region;
  readonly end: Region;
}

export function framingAt(framing: Framing, progress: number): Region {
  return {
    x: lerp(framing.start.x, framing.end.x, progress),
    y: lerp(framing.start.y, framing.end.y, progress),
    width: lerp(framing.start.width, framing.end.width, progress),
    height: lerp(framing.start.height, framing.end.height, progress),
  };
}

export class TeaserLayout {
  readonly profile: VideoProfile;
  private readonly framings = new Map<string, Framing>();
  private readonly verticalCrops = new Map<string, Region>();

  constructor(profile: VideoProfile) {
    this.profile = profile;
  }

  get vertical(): boolean {
    return this.profile.id === "vertical";
  }

  splitDirection(): SplitDirection {
    return this.vertical ? "column" : "row";
  }

  single(pane: Pane): Box {
    switch (pane.kind) {
      case "folder":
        return this.folderBox(pane.entries.length);
      case "console":
        return this.consoleBox(pane.lines, pane.typed !== null);
      case "game":
        return (this.vertical ? VERTICAL_PANES : LANDSCAPE_PANES)[pane.kind];
      case "screen": {
        if (!this.vertical) return LANDSCAPE_SCREEN;
        const crop = this.verticalCrop(pane.footage);
        return { width: VERTICAL_WIDTH, height: Math.round((VERTICAL_WIDTH * crop.height) / crop.width) };
      }
    }
  }

  splitBox(): Box {
    return this.vertical ? { width: VERTICAL_WIDTH, height: 880 } : { width: (this.profile.width - SPLIT_GAP - 24) / 2, height: Math.round(((this.profile.width - SPLIT_GAP - 24) / 2) * (9 / 16)) };
  }

  screenFraming(footage: Footage, box: Box, zoom: number): Framing {
    const key = `${footage.name}:${String(box.width)}x${String(box.height)}:${String(zoom)}`;
    const cached = this.framings.get(key);
    if (cached) return cached;
    const crop = this.vertical ? this.verticalCropFor(footage, box) : { x: 0, y: 0, ...footage.viewport };
    requireWholeText(footage, crop);
    const end = pushInTarget(footage, crop, zoom);
    requireWholeText(footage, end);
    const framing = { start: crop, end };
    this.framings.set(key, framing);
    return framing;
  }

  private verticalCrop(footage: Footage): Region {
    const cached = this.verticalCrops.get(footage.name);
    if (cached) return cached;
    const crop = screenCrop(footage, VERTICAL_WIDTH / VERTICAL_TALLEST, VERTICAL_NARROWEST_CROP);
    this.verticalCrops.set(footage.name, crop);
    return crop;
  }

  private verticalCropFor(footage: Footage, box: Box): Region {
    const single = this.verticalCrop(footage);
    const singleBox = Math.round((VERTICAL_WIDTH * single.height) / single.width);
    if (box.width === VERTICAL_WIDTH && box.height === singleBox) return single;
    return boxCrop(footage, box.width / box.height, VERTICAL_NARROWEST_CROP);
  }

  private consoleBox(lines: readonly ConsoleLine[], typed: boolean): Box {
    const largest = (this.vertical ? VERTICAL_PANES : LANDSCAPE_PANES).console;
    const perRow = this.vertical ? CONSOLE_CHARACTERS.vertical : CONSOLE_CHARACTERS.landscape;
    const rows = lines.reduce((sum, line) => sum + Math.max(1, Math.ceil((line.meta.length + 1 + line.text.length) / perRow)), typed ? 1 : 0);
    const chrome = this.vertical ? CONSOLE_CHROME.vertical : CONSOLE_CHROME.landscape;
    return { width: largest.width, height: Math.round(clamp(chrome + rows * (this.vertical ? CONSOLE_ROW.vertical : CONSOLE_ROW.landscape), 320, largest.height)) };
  }

  private folderBox(entries: number): Box {
    return this.vertical ? { width: VERTICAL_WIDTH, height: 76 + 48 + entries * 92 } : { width: 1400, height: 60 + 40 + entries * 76 };
  }
}
