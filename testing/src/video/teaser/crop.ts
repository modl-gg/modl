import { crossesHorizontalLine, crossesVerticalLine, type TextBox } from "../studio/cut-text";
import type { Footage, Region } from "./edit";
import { clamp } from "./motion";

const FOCUS_MARGIN = 1.12;
const FOCUS_PADDING = 32;
const POSITION_STEPS = 24;
const WIDTH_STEPS = 12;
const ASPECT_STEP = 0.06;
const SMALLEST_STEP = 4;
const CONTENT_LINES = 4;
const EDGE_CLEARANCE = 6;
const WIDEST_SPANNING_ASPECT = 1;

function overlaps(box: TextBox, from: number, to: number, start: (box: TextBox) => number, size: (box: TextBox) => number): boolean {
  return start(box) < to && start(box) + size(box) > from;
}

function padded(box: TextBox): TextBox {
  return { ...box, x: box.x - EDGE_CLEARANCE, y: box.y - EDGE_CLEARANCE, width: box.width + EDGE_CLEARANCE * 2, height: box.height + EDGE_CLEARANCE * 2 };
}

function inside(box: TextBox, region: Region): boolean {
  return box.x >= region.x && box.y >= region.y && box.x + box.width <= region.x + region.width && box.y + box.height <= region.y + region.height;
}

export function slicedText(footage: Footage, region: Region): TextBox[] {
  const { viewport } = footage;
  const right = region.x + region.width;
  const bottom = region.y + region.height;
  return footage.text.filter((box) => {
    const inRows = overlaps(box, region.y, bottom, (entry) => entry.y, (entry) => entry.height);
    const inColumns = overlaps(box, region.x, right, (entry) => entry.x, (entry) => entry.width);
    const left = region.x > 0.5 && inRows && crossesVerticalLine(box, region.x);
    const rightEdge = right < viewport.width - 0.5 && inRows && crossesVerticalLine(box, right);
    const top = region.y > 0.5 && inColumns && crossesHorizontalLine(box, region.y);
    const bottomEdge = bottom < viewport.height - 0.5 && inColumns && crossesHorizontalLine(box, bottom);
    return left || rightEdge || top || bottomEdge;
  });
}

export function requireWholeText(footage: Footage, region: Region): void {
  const sliced = slicedText(footage, region);
  if (sliced.length > 0) throw new Error(`the crop of footage ${footage.name} slices ${sliced.map((box) => `"${box.text}"`).join(", ")}`);
}

function ordered(ideal: number, low: number, high: number): number[] {
  if (high < low) return [];
  const step = Math.max(SMALLEST_STEP, (high - low) / POSITION_STEPS);
  const start = clamp(ideal, low, high);
  const values = [start];
  for (let distance = step; start - distance >= low || start + distance <= high; distance += step) {
    if (start - distance >= low) values.push(start - distance);
    if (start + distance <= high) values.push(start + distance);
  }
  return values;
}

function placements(focusStart: number, focusSize: number, size: number, limit: number, contentStart: number | null): number[] {
  const focusEnd = focusStart + focusSize;
  const centred = focusStart + focusSize / 2 - size / 2;
  if (focusSize <= size) return ordered(centred, Math.max(0, focusEnd - size), Math.min(limit - size, focusStart));
  const latest = Math.min(limit - size, focusEnd - size, contentStart ?? Number.POSITIVE_INFINITY);
  return ordered(contentStart === null ? centred : contentStart - FOCUS_PADDING / 2, Math.max(0, focusStart), latest);
}

function widthsFor(footage: Footage, aspect: number, narrowest: number): number[] {
  const { focus, viewport } = footage;
  const widest = Math.min(viewport.width, viewport.height * aspect);
  const smallest = clamp(focus.width * FOCUS_MARGIN + FOCUS_PADDING, Math.min(narrowest, widest), widest);
  const step = (widest - smallest) / WIDTH_STEPS;
  if (step <= 0) return [widest];
  return Array.from({ length: WIDTH_STEPS + 1 }, (_, index) => smallest + index * step);
}

class CropSearch {
  private readonly footage: Footage;
  private readonly cleared: Footage;
  private readonly focusText: readonly TextBox[];
  private readonly contentLines: number;
  private readonly contentLeft: number | null;
  private readonly contentRight: number | null;
  private readonly contentTop: number | null;

  constructor(footage: Footage) {
    this.footage = footage;
    this.cleared = { ...footage, text: footage.text.map(padded) };
    const focusText = footage.text.filter((box) => inside(box, footage.focus));
    this.focusText = focusText;
    this.contentLines = Math.min(CONTENT_LINES, focusText.length);
    this.contentLeft = focusText.length === 0 ? null : Math.min(...focusText.map((box) => box.x));
    this.contentRight = focusText.length === 0 ? null : Math.max(...focusText.map((box) => box.x + box.width));
    this.contentTop = focusText.length === 0 ? null : Math.min(...focusText.map((box) => box.y));
  }

  spanning(aspect: number): Region | null {
    if (this.contentLeft === null || this.contentRight === null) return null;
    const { focus, viewport } = this.footage;
    const from = this.contentLeft - EDGE_CLEARANCE;
    const to = this.contentRight + EDGE_CLEARANCE;
    const widest = Math.min(viewport.width, viewport.height * aspect);
    if (to - from > widest) return null;
    const height = widest / aspect;
    for (const y of placements(focus.y, focus.height, height, viewport.height, this.contentTop)) {
      for (const x of ordered((from + to) / 2 - widest / 2, Math.max(0, to - widest), Math.min(viewport.width - widest, from))) {
        const region = { x, y, width: widest, height };
        if (this.usable(region, this.cleared)) return region;
      }
    }
    return null;
  }

  focused(aspect: number, narrowest: number): Region | null {
    return this.focusedAgainst(aspect, narrowest, this.cleared) ?? this.focusedAgainst(aspect, narrowest, this.footage);
  }

  private focusedAgainst(aspect: number, narrowest: number, text: Footage): Region | null {
    const { focus, viewport } = this.footage;
    for (const width of widthsFor(this.footage, aspect, narrowest)) {
      const height = width / aspect;
      if (height > viewport.height + 0.5) continue;
      for (const y of placements(focus.y, focus.height, height, viewport.height, this.contentTop)) {
        for (const x of placements(focus.x, focus.width, width, viewport.width, this.contentLeft)) {
          const region = { x, y, width, height };
          if (this.usable(region, text)) return region;
        }
      }
    }
    return null;
  }

  loose(aspect: number, narrowest: number): Region | null {
    const { focus, viewport } = this.footage;
    for (const width of widthsFor(this.footage, aspect, narrowest)) {
      const height = width / aspect;
      if (height > viewport.height + 0.5) continue;
      for (const y of ordered(focus.y + focus.height / 2 - height / 2, 0, viewport.height - height)) {
        for (const x of ordered(focus.x + focus.width / 2 - width / 2, 0, viewport.width - width)) {
          const region = { x, y, width, height };
          if (slicedText(this.footage, region).length === 0) return region;
        }
      }
    }
    return null;
  }

  private usable(region: Region, text: Footage): boolean {
    if (slicedText(text, region).length > 0) return false;
    return this.focusText.filter((box) => inside(box, region)).length >= this.contentLines;
  }
}

export function screenCrop(footage: Footage, tallest: number, narrowest: number): Region {
  const { viewport } = footage;
  if (footage.wholeFrame) return { x: 0, y: 0, ...viewport };
  const search = new CropSearch(footage);
  for (let aspect = tallest; aspect <= WIDEST_SPANNING_ASPECT; aspect += ASPECT_STEP) {
    const crop = search.spanning(aspect);
    if (crop !== null) return crop;
  }
  for (let aspect = tallest; aspect < viewport.width / viewport.height; aspect += ASPECT_STEP) {
    const crop = search.focused(aspect, narrowest);
    if (crop !== null) return crop;
  }
  return { x: 0, y: 0, ...viewport };
}

export function boxCrop(footage: Footage, aspect: number, narrowest: number): Region {
  const search = new CropSearch(footage);
  const crop = search.focused(aspect, narrowest) ?? search.loose(aspect, narrowest);
  if (crop === null) throw new Error(`no ${aspect.toFixed(2)} crop of footage ${footage.name} keeps every visible text line whole`);
  return crop;
}

export function pushInTarget(footage: Footage, crop: Region, zoom: number): Region {
  const { focus } = footage;
  const aspect = crop.width / crop.height;
  const centreX = focus.x + focus.width / 2;
  const centreY = focus.y + focus.height / 2;
  for (let width = crop.width / zoom; width < crop.width; width += SMALLEST_STEP * 2) {
    const height = width / aspect;
    for (const y of ordered(centreY - height / 2, crop.y, crop.y + crop.height - height)) {
      for (const x of ordered(centreX - width / 2, crop.x, crop.x + crop.width - width)) {
        const region = { x, y, width, height };
        if (slicedText(footage, region).length === 0) return region;
      }
    }
  }
  return crop;
}
