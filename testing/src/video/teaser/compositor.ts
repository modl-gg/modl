import { mkdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import type { Page } from "playwright-core";
import { removePath } from "../../lib/util";
import { FRAME_RATE } from "../studio/theme";
import type { Footage, Pane, Shot, TeaserEdit } from "./edit";
import type { TeaserLayout } from "./layout";
import { window01 } from "./motion";
import { teaserPage } from "./page";
import type { FrameSource, NodeState } from "./panes";
import { hiddenShot, shotStates, type ShotMoment } from "./shots";

const ORIGIN = "http://teaser.modl";
const JPEG_QUALITY = 92;

interface IndexedFootage {
  readonly index: number;
  readonly starts: readonly number[];
  readonly files: readonly string[];
}

class FootageServer implements FrameSource {
  private readonly footage = new Map<string, IndexedFootage>();

  constructor(edit: TeaserEdit) {
    for (const shot of edit.shots) {
      for (const footage of panesOf(shot).flatMap(footageOf)) {
        if (this.footage.has(footage.name)) continue;
        this.footage.set(footage.name, indexed(footage, this.footage.size));
      }
    }
  }

  urlAt(footage: Footage, seconds: number): string {
    const entry = this.footage.get(footage.name);
    if (!entry) throw new Error(`footage ${footage.name} is not part of the edit`);
    let low = 0;
    let high = entry.starts.length - 1;
    while (low < high) {
      const middle = Math.ceil((low + high) / 2);
      if ((entry.starts[middle] ?? 0) <= seconds) low = middle;
      else high = middle - 1;
    }
    return `${ORIGIN}/f/${String(entry.index)}/${String(low)}`;
  }

  file(path: string): string | null {
    const match = /^\/f\/(\d+)\/(\d+)$/.exec(path);
    if (!match) return null;
    const [, footageIndex, frameIndex] = match;
    for (const entry of this.footage.values()) {
      if (String(entry.index) === footageIndex) return entry.files[Number(frameIndex)] ?? null;
    }
    return null;
  }
}

function panesOf(shot: Shot): readonly Pane[] {
  const content = shot.content;
  switch (content.kind) {
    case "single":
      return [content.pane];
    case "split":
      return [content.first, content.second];
    case "brand":
    case "list":
      return [];
  }
}

function footageOf(pane: Pane): Footage[] {
  switch (pane.kind) {
    case "screen":
      return [pane.footage];
    case "game":
      return [pane.world];
    case "console":
    case "folder":
      return [];
  }
}

function indexed(footage: Footage, index: number): IndexedFootage {
  if (footage.frames.length === 0) throw new Error(`footage ${footage.name} has no frames`);
  const starts: number[] = [];
  let start = 0;
  for (const frame of footage.frames) {
    starts.push(start);
    start += frame.seconds;
  }
  return { index, starts, files: footage.frames.map((frame) => frame.file) };
}

export function shotMoment(shots: readonly Shot[], index: number, time: number): ShotMoment | null {
  const shot = shots[index];
  if (!shot) return null;
  const next = shots[index + 1];
  const enterHalf = shot.enter.kind === "cut" ? 0 : shot.enter.seconds / 2;
  const exitHalf = next === undefined || next.enter.kind === "cut" ? 0 : next.enter.seconds / 2;
  const from = shot.start - enterHalf;
  const until = shot.end + exitHalf;
  if (time < from || time >= until) return null;
  return {
    time,
    local: time - shot.start,
    progress: (time - from) / (until - from),
    entering: shot.enter.kind === "cut" ? 1 : window01(time, from, shot.enter.seconds),
    leaving: next === undefined || next.enter.kind === "cut" ? 0 : window01(time, shot.end - exitHalf, next.enter.seconds),
    exit: next?.enter.kind ?? "cut",
    layer: index + 1,
  };
}

export class TeaserCompositor {
  private readonly page: Page;
  private readonly edit: TeaserEdit;
  private readonly layout: TeaserLayout;
  private readonly footage: FootageServer;
  private readonly sent = new Map<string, string>();

  constructor(page: Page, edit: TeaserEdit, layout: TeaserLayout) {
    this.page = page;
    this.edit = edit;
    this.layout = layout;
    this.footage = new FootageServer(edit);
  }

  async load(): Promise<void> {
    const html = teaserPage(this.edit, this.layout);
    await this.page.route(`${ORIGIN}/**`, async (route) => {
      const path = new URL(route.request().url()).pathname;
      if (path === "/") {
        await route.fulfill({ status: 200, contentType: "text/html; charset=utf-8", body: html });
        return;
      }
      const file = this.footage.file(path);
      if (file === null) {
        await route.fulfill({ status: 404, body: "" });
        return;
      }
      await route.fulfill({ status: 200, contentType: "image/jpeg", body: readFileSync(file) });
    });
    await this.page.goto(`${ORIGIN}/`, { waitUntil: "load" });
    await this.page.evaluate("document.fonts.ready");
  }

  async render(directory: string): Promise<number> {
    removePath(directory);
    mkdirSync(directory, { recursive: true });
    const count = Math.round(this.edit.seconds * FRAME_RATE);
    for (let frame = 0; frame < count; frame += 1) {
      const changed = this.changedStates(frame / FRAME_RATE);
      if (changed.length > 0) await this.page.evaluate(`window.teaser.apply(${JSON.stringify(changed)})`);
      await this.page.screenshot({ path: join(directory, `${String(frame).padStart(6, "0")}.jpg`), type: "jpeg", quality: JPEG_QUALITY });
    }
    return count;
  }

  private changedStates(time: number): NodeState[] {
    const shots = this.edit.shots;
    const states = shots.flatMap((shot, index) => {
      const moment = shotMoment(shots, index, time);
      return moment === null ? [hiddenShot(shot)] : shotStates(shot, moment, this.layout, this.footage);
    });
    return states.filter((state) => {
      const key = JSON.stringify(state);
      const id = `${state.id}:${Object.keys(state).sort().join(",")}`;
      if (this.sent.get(id) === key) return false;
      this.sent.set(id, key);
      return true;
    });
  }
}
