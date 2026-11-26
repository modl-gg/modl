import { mkdirSync } from "node:fs";
import { join } from "node:path";
import { setTimeout as sleep } from "node:timers/promises";
import type { Locator, Page } from "playwright-core";
import { z } from "zod";
import { visibleTextBoxes, type TextBox } from "../studio/cut-text";
import type { TimedFrame } from "../studio/segment";
import type { Take } from "../studio/take";
import type { Footage, Region } from "./edit";

const VIEWPORT = z.object({ width: z.number(), height: z.number() });
const STILL_SECONDS = 60;
const TEXT_SAMPLE_MS = 250;

async function viewportOf(page: Page): Promise<{ width: number; height: number }> {
  return VIEWPORT.parse(await page.evaluate("({ width: window.innerWidth, height: window.innerHeight })"));
}

async function regionOf(page: Page, focus: Locator | null): Promise<Region> {
  const viewport = await viewportOf(page);
  const box = focus === null ? null : await focus.boundingBox();
  if (focus !== null && box === null) throw new Error("the footage focus has no visible box");
  if (box === null) return { x: 0, y: 0, ...viewport };
  const x = Math.max(0, box.x);
  const y = Math.max(0, box.y);
  return { x, y, width: Math.min(box.width, viewport.width - x), height: Math.min(box.height, viewport.height - y) };
}

export interface FootageDirector {
  omitFromNow(): void;
  keepFromNow(): void;
  sampleText(): Promise<void>;
}

class FootageCuts {
  private startedAt = 0;
  private openedAt: number | null = null;
  private readonly spans: { from: number; to: number }[] = [];

  begin(): void {
    this.startedAt = Date.now();
  }

  omitFromNow(): void {
    this.openedAt ??= (Date.now() - this.startedAt) / 1000;
  }

  elapsed(): number {
    return (Date.now() - this.startedAt) / 1000;
  }

  keeps(seconds: number): boolean {
    if (this.openedAt !== null && seconds >= this.openedAt) return false;
    return !this.spans.some((span) => seconds >= span.from && seconds < span.to);
  }

  keepFromNow(): void {
    if (this.openedAt === null) return;
    this.spans.push({ from: this.openedAt, to: (Date.now() - this.startedAt) / 1000 });
    this.openedAt = null;
  }

  apply(frames: readonly TimedFrame[]): TimedFrame[] {
    let time = 0;
    return frames.filter((frame) => {
      const start = time;
      time += frame.seconds;
      return this.keeps(start);
    });
  }
}

interface TextSample {
  readonly at: number;
  readonly boxes: readonly TextBox[];
}

function textKey(box: TextBox): string {
  return [box.text, Math.round(box.x), Math.round(box.y), Math.round(box.width), Math.round(box.height)].join("|");
}

async function readableTextBoxes(page: Page): Promise<TextBox[] | null> {
  try {
    return await visibleTextBoxes(page);
  } catch {
    return null;
  }
}

class TextSampler {
  private readonly page: Page;
  private readonly cuts: FootageCuts;
  private readonly samples: TextSample[] = [];
  private running = true;
  private readonly loop: Promise<void>;

  private constructor(page: Page, cuts: FootageCuts) {
    this.page = page;
    this.cuts = cuts;
    this.loop = this.run();
  }

  static async during(page: Page, cuts: FootageCuts, action: (director: FootageDirector) => Promise<void>): Promise<TextBox[]> {
    const sampler = new TextSampler(page, cuts);
    const director: FootageDirector = {
      omitFromNow: () => {
        cuts.omitFromNow();
      },
      keepFromNow: () => {
        cuts.keepFromNow();
      },
      sampleText: async () => sampler.sample(),
    };
    try {
      await action(director);
    } finally {
      sampler.running = false;
      await sampler.loop;
    }
    return sampler.keptText();
  }

  private keptText(): TextBox[] {
    const kept = new Map<string, TextBox>();
    for (const sample of this.samples) {
      if (!this.cuts.keeps(sample.at)) continue;
      for (const box of sample.boxes) kept.set(textKey(box), box);
    }
    return [...kept.values()];
  }

  private async sample(): Promise<void> {
    const at = this.cuts.elapsed();
    const boxes = await readableTextBoxes(this.page);
    if (boxes !== null) this.samples.push({ at, boxes });
  }

  private async run(): Promise<void> {
    while (this.running) {
      await this.sample();
      await sleep(TEXT_SAMPLE_MS);
    }
  }
}

export class FootageRoll {
  private readonly directory: string;

  constructor(workDir: string) {
    this.directory = join(workDir, "footage");
    mkdirSync(this.directory, { recursive: true });
  }

  async still(page: Page, name: string, focus: Locator | null): Promise<Footage> {
    const file = join(this.directory, `${name}.jpg`);
    const focusRegion = await regionOf(page, focus);
    const text = await visibleTextBoxes(page);
    await page.screenshot({ path: file, type: "jpeg", quality: 94 });
    return { name, frames: [{ file, seconds: STILL_SECONDS }], viewport: await viewportOf(page), focus: focusRegion, text, wholeFrame: false };
  }

  async live(take: Take, focus: Locator | null, action: (director: FootageDirector) => Promise<void>): Promise<Footage> {
    const focusRegion = await regionOf(take.page, focus);
    const cuts = new FootageCuts();
    await take.start();
    cuts.begin();
    const text = await TextSampler.during(take.page, cuts, action);
    const segment = await take.stop();
    return { name: take.name, frames: cuts.apply(segment.frames), viewport: await viewportOf(take.page), focus: focusRegion, text, wholeFrame: false };
  }

  async element(name: string, target: Locator): Promise<Footage> {
    const file = join(this.directory, `${name}.jpg`);
    const box = await target.boundingBox();
    if (!box) throw new Error(`the footage ${name} has no visible element`);
    await target.screenshot({ path: file, type: "jpeg", quality: 94 });
    const viewport = { width: box.width, height: box.height };
    return { name, frames: [{ file, seconds: STILL_SECONDS }], viewport, focus: { x: 0, y: 0, ...viewport }, text: [], wholeFrame: true };
  }
}
