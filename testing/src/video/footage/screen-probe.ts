import { mkdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { runProcess } from "../../lib/process";
import { sleep } from "../../lib/util";
import { HOST_FFMPEG } from "../ffmpeg";
import type { Point } from "../studio/pointer";
import type { GuiLayout, Rect } from "./gui-layout";

const PNG_WIDTH_OFFSET = 16;
const WORLD_SAMPLE = { left: 0.03, top: 0.55, width: 0.22, height: 0.15, columns: 600, rows: 200 } as const;
const EDGE_STEP = 4;
const SHARP_EDGES_PER_MILLE = 5;
const BUTTON_PROBE_HALF_WIDTH = 40;
const BUTTON_GAP_OFFSET = 52;
const OUTLINE_MAX_GRAY = 40;
const GAP_MIN_GRAY = 55;
const BUTTON_HEIGHT = { min: 18, max: 22 } as const;
const CHEST_FILL_GRAY = { min: 183, max: 213 } as const;
const MENU_CHANGE_MEAN_DIFF = 2;
const MENU_POLL_MS = 80;

interface Run {
  start: number;
  end: number;
}

function edgesPerMille(gray: Buffer): number {
  let edges = 0;
  for (let row = 0; row < WORLD_SAMPLE.rows; row += 1) {
    for (let column = 1; column < WORLD_SAMPLE.columns; column += 1) {
      const index = row * WORLD_SAMPLE.columns + column;
      if (Math.abs((gray[index] ?? 0) - (gray[index - 1] ?? 0)) >= EDGE_STEP) edges += 1;
    }
  }
  return (1000 * edges) / (WORLD_SAMPLE.rows * (WORLD_SAMPLE.columns - 1));
}

function outlineRuns(gray: Buffer, width: number, inner: { from: number; to: number }, gapWidth: number): Run[] {
  const runs: Run[] = [];
  const rows = Math.floor(gray.length / width);
  for (let row = 0; row < rows; row += 1) {
    const line = gray.subarray(row * width, (row + 1) * width);
    const outline = Math.max(...line.subarray(inner.from, inner.to)) <= OUTLINE_MAX_GRAY;
    const gaps = Math.min(...line.subarray(0, gapWidth), ...line.subarray(width - gapWidth)) >= GAP_MIN_GRAY;
    if (!outline || !gaps) continue;
    const last = runs.at(-1);
    if (last?.end === row - 1) last.end = row;
    else runs.push({ start: row, end: row });
  }
  return runs;
}

function meanDifference(left: Buffer, right: Buffer): number {
  if (left.length !== right.length || left.length === 0) return Number.POSITIVE_INFINITY;
  let total = 0;
  for (let index = 0; index < left.length; index += 1) total += Math.abs((left[index] ?? 0) - (right[index] ?? 0));
  return total / left.length;
}

function regionMean(gray: Buffer, width: number, region: Rect): number {
  let total = 0;
  for (let row = region.y; row < region.y + region.height; row += 1) {
    for (let column = region.x; column < region.x + region.width; column += 1) total += gray[row * width + column] ?? 0;
  }
  return total / (region.width * region.height);
}

async function capture(rect: Rect, file: string): Promise<void> {
  await runProcess("screencapture", ["-x", "-R", [rect.x, rect.y, rect.width, rect.height].map(String).join(","), file]);
}

export class ScreenProbe {
  readonly pixelScale: number;
  readonly directory: string;
  private readonly content: Rect;
  private shots = 0;

  private constructor(content: Rect, pixelScale: number, directory: string) {
    this.content = content;
    this.pixelScale = pixelScale;
    this.directory = directory;
  }

  static async open(content: Rect, directory: string): Promise<ScreenProbe> {
    mkdirSync(directory, { recursive: true });
    const file = join(directory, "000-scale.png");
    await capture(content, file);
    const pixels = readFileSync(file).readUInt32BE(PNG_WIDTH_OFFSET);
    const scale = pixels / content.width;
    if (!Number.isInteger(scale) || scale < 1) throw new Error(`a ${String(content.width)} point wide capture is ${String(pixels)} pixels wide; expected a whole pixel scale`);
    return new ScreenProbe(content, scale, directory);
  }

  async shot(name: string): Promise<string> {
    this.shots += 1;
    const file = join(this.directory, `${String(this.shots).padStart(3, "0")}-${name}.png`);
    await capture(this.content, file);
    return file;
  }

  async showsWorld(): Promise<boolean> {
    const sample = WORLD_SAMPLE;
    const crop = `crop=iw*${String(sample.width)}:ih*${String(sample.height)}:iw*${String(sample.left)}:ih*${String(sample.top)}`;
    const gray = await this.gray(await this.shot("state"), `${crop},scale=${String(sample.columns)}:${String(sample.rows)}:flags=neighbor`);
    return edgesPerMille(gray) >= SHARP_EDGES_PER_MILLE;
  }

  async dialogButton(layout: GuiLayout): Promise<Point | null> {
    const shot = await this.shot("dialog");
    const unit = layout.scale * this.pixelScale;
    const width = (2 * BUTTON_GAP_OFFSET + 1) * unit;
    const left = (this.content.width * this.pixelScale) / 2 - BUTTON_GAP_OFFSET * unit;
    const inner = { from: (BUTTON_GAP_OFFSET - BUTTON_PROBE_HALF_WIDTH) * unit, to: (BUTTON_GAP_OFFSET + BUTTON_PROBE_HALF_WIDTH) * unit };
    const runs = outlineRuns(await this.gray(shot, `crop=${String(width)}:ih:${String(left)}:0`), width, inner, unit);
    const button = runs.flatMap((top, index) => {
      const bottom = runs[index + 1];
      if (bottom === undefined) return [];
      const height = (bottom.end + 1 - top.start) / unit;
      return height >= BUTTON_HEIGHT.min && height <= BUTTON_HEIGHT.max ? [{ top, bottom }] : [];
    })[0];
    if (!button) return null;
    const centre = (button.top.start + button.bottom.end) / 2 / this.pixelScale;
    return { x: Math.round(this.content.x + this.content.width / 2), y: Math.round(this.content.y + centre) };
  }

  async chestImage(layout: GuiLayout, rows: number): Promise<Buffer> {
    return this.guiRegion(layout, layout.chest(rows));
  }

  async awaitMenu(layout: GuiLayout, rows: number, before: Buffer, timeoutMs: number): Promise<void> {
    const chest = layout.chest(rows);
    const fill = layout.chestFill(rows);
    const fillInChest = { x: fill.x - chest.x, y: fill.y - chest.y, width: fill.width, height: fill.height };
    const deadline = Date.now() + timeoutMs;
    for (;;) {
      const image = await this.guiRegion(layout, chest);
      const fillGray = regionMean(image, chest.width, fillInChest);
      const open = fillGray >= CHEST_FILL_GRAY.min && fillGray <= CHEST_FILL_GRAY.max;
      if (open && meanDifference(image, before) > MENU_CHANGE_MEAN_DIFF) return;
      if (Date.now() > deadline) throw new Error(`the ${String(rows)}-row report menu did not appear within ${String(timeoutMs / 1000)} s; see ${this.regionFile}`);
      await sleep(MENU_POLL_MS);
    }
  }

  private get regionFile(): string {
    return join(this.directory, "region.png");
  }

  private async guiRegion(layout: GuiLayout, region: Rect): Promise<Buffer> {
    const corner = layout.point(region.x, region.y);
    await capture({ x: corner.x, y: corner.y, width: region.width * layout.scale, height: region.height * layout.scale }, this.regionFile);
    return this.gray(this.regionFile, `scale=${String(region.width)}:${String(region.height)}:flags=area`);
  }

  private async gray(shot: string, filter: string): Promise<Buffer> {
    const raw = `${shot}.gray`;
    await runProcess(HOST_FFMPEG, ["-hide_banner", "-v", "error", "-y", "-i", shot, "-vf", `${filter},format=gray`, "-f", "rawvideo", raw]);
    return readFileSync(raw);
  }
}
