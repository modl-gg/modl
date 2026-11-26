import type { Locator, Page } from "playwright-core";
import { overlayCall as call } from "./overlay";

export interface Point {
  x: number;
  y: number;
}

export interface Hands {
  click(target: Locator): Promise<void>;
  type(target: Locator, text: string): Promise<void>;
  replace(target: Locator, text: string): Promise<void>;
}

export interface Narrator {
  label(text: string | null): Promise<void>;
  settleLabel(): Promise<void>;
  hold(ms: number): Promise<void>;
}

export interface LabelSink {
  show(text: string | null): void | Promise<void>;
}

export class OverlayLabels implements LabelSink {
  private readonly page: Page;

  constructor(page: Page) {
    this.page = page;
  }

  async show(text: string | null): Promise<void> {
    await this.page.evaluate(call("label", text ?? ""));
  }
}

const MIN_LABEL_MS = 1600;
const MOVE_MIN_MS = 420;
const MOVE_MAX_MS = 820;
const PARK_OFFSET: Point = { x: 34, y: 26 };
const TARGET_INSET_PX = 46;

function moveDuration(from: Point, to: Point): number {
  const distance = Math.hypot(to.x - from.x, to.y - from.y);
  return Math.round(Math.min(MOVE_MAX_MS, Math.max(MOVE_MIN_MS, 300 + distance * 0.7)));
}

function typingDelay(text: string): number {
  return Math.round(Math.min(80, Math.max(28, 1500 / Math.max(text.length, 1))));
}

async function targetPoint(target: Locator): Promise<Point> {
  await target.scrollIntoViewIfNeeded();
  const box = await target.boundingBox();
  if (!box) throw new Error("a target to click has no visible box");
  return { x: Math.round(box.x + Math.min(box.width / 2, TARGET_INSET_PX)), y: Math.round(box.y + box.height / 2) };
}

export type Pause = (ms: number) => Promise<void>;

export class Narration implements Narrator {
  private readonly pause: Pause;
  private readonly sink: LabelSink;
  private labelText: string | null = null;
  private labelShownAt = 0;

  constructor(pause: Pause, sink: LabelSink) {
    this.pause = pause;
    this.sink = sink;
  }

  async label(text: string | null): Promise<void> {
    if (text === this.labelText) return;
    await this.settleLabel();
    await this.sink.show(text);
    this.labelText = text;
    this.labelShownAt = Date.now();
  }

  async settleLabel(): Promise<void> {
    if (this.labelText === null) return;
    const remaining = this.labelShownAt + MIN_LABEL_MS - Date.now();
    if (remaining > 0) await this.pause(remaining);
  }

  async hold(ms: number): Promise<void> {
    await this.pause(ms);
  }
}

export class CinematicPointer implements Hands, Narrator {
  private readonly page: Page;
  private readonly narration: Narration;
  private position: Point;

  constructor(page: Page, start: Point, sink: LabelSink) {
    this.page = page;
    this.narration = new Narration(async (ms) => page.waitForTimeout(ms), sink);
    this.position = start;
  }

  async appear(): Promise<void> {
    await this.page.evaluate(call("place", this.position.x, this.position.y));
  }

  async moveTo(point: Point): Promise<void> {
    const ms = moveDuration(this.position, point);
    await this.page.evaluate(call("move", point.x, point.y, ms));
    await this.page.waitForTimeout(ms + 80);
    await this.page.mouse.move(point.x, point.y);
    this.position = point;
  }

  async clickAt(point: Point): Promise<void> {
    await this.moveTo(point);
    await this.page.evaluate(call("ripple", point.x, point.y));
    await this.page.mouse.click(point.x, point.y);
    await this.page.waitForTimeout(160);
  }

  async click(target: Locator): Promise<void> {
    await this.clickAt(await targetPoint(target));
    await this.park();
  }

  async type(target: Locator, text: string): Promise<void> {
    await this.clickAt(await targetPoint(target));
    await this.park();
    await this.page.keyboard.type(text, { delay: typingDelay(text) });
    await this.page.waitForTimeout(250);
  }

  async replace(target: Locator, text: string): Promise<void> {
    await this.clickAt(await targetPoint(target));
    await this.park();
    await this.page.keyboard.press("ControlOrMeta+A");
    await this.page.keyboard.type(text, { delay: typingDelay(text) });
    await this.page.waitForTimeout(250);
  }

  async label(text: string | null): Promise<void> {
    await this.narration.label(text);
  }

  async settleLabel(): Promise<void> {
    await this.narration.settleLabel();
  }

  async hold(ms: number): Promise<void> {
    await this.narration.hold(ms);
  }

  async hide(): Promise<void> {
    await this.page.evaluate(call("hideCursor"));
  }

  private async park(): Promise<void> {
    const parked = { x: this.position.x + PARK_OFFSET.x, y: this.position.y + PARK_OFFSET.y };
    await this.page.evaluate(call("move", parked.x, parked.y, 320));
    await this.page.waitForTimeout(340);
    this.position = parked;
  }
}

export class DirectHands implements Hands {
  async click(target: Locator): Promise<void> {
    await target.click();
  }

  async type(target: Locator, text: string): Promise<void> {
    await target.fill(text);
  }

  async replace(target: Locator, text: string): Promise<void> {
    await target.fill(text);
  }
}
