import { existsSync, readFileSync, writeFileSync } from "node:fs";
import { chromium, type Page } from "playwright-core";
import { z } from "zod";

const CHANNEL_TOLERANCE = 24;

const DIFF_SOURCE = `async (before, after, tolerance) => {
  const load = async (src) => { const image = new Image(); image.src = src; await image.decode(); return image; };
  const [left, right] = await Promise.all([load(before), load(after)]);
  const width = Math.max(left.width, right.width);
  const height = Math.max(left.height, right.height);
  const pixels = (image) => {
    const canvas = document.createElement("canvas");
    canvas.width = width;
    canvas.height = height;
    const context = canvas.getContext("2d");
    context.drawImage(image, 0, 0);
    return context.getImageData(0, 0, width, height);
  };
  const a = pixels(left).data;
  const bImage = pixels(right);
  const b = bImage.data;
  const out = new ImageData(width, height);
  let changed = 0;
  for (let index = 0; index < a.length; index += 4) {
    const delta = Math.max(Math.abs(a[index] - b[index]), Math.abs(a[index + 1] - b[index + 1]), Math.abs(a[index + 2] - b[index + 2]), Math.abs(a[index + 3] - b[index + 3]));
    if (delta > tolerance) {
      changed += 1;
      out.data[index] = 255; out.data[index + 1] = 0; out.data[index + 2] = 200; out.data[index + 3] = 255;
    } else {
      const gray = (b[index] * 0.3 + b[index + 1] * 0.59 + b[index + 2] * 0.11) * 0.35 + 140;
      out.data[index] = gray; out.data[index + 1] = gray; out.data[index + 2] = gray; out.data[index + 3] = 255;
    }
  }
  if (changed === 0) return { changed, total: width * height, diff: null };
  const canvas = document.createElement("canvas");
  canvas.width = width;
  canvas.height = height;
  canvas.getContext("2d").putImageData(out, 0, 0);
  return { changed, total: width * height, diff: canvas.toDataURL("image/png") };
}`;

const DIFF_RESULT = z.object({ changed: z.number(), total: z.number(), diff: z.string().nullable() });

export interface Comparison {
  changedPixels: number;
  ratio: number;
}

function dataUrl(file: string): string {
  return `data:image/png;base64,${readFileSync(file).toString("base64")}`;
}

export class ImageComparer {
  private readonly page: Page;
  private readonly closeBrowser: () => Promise<void>;

  private constructor(page: Page, closeBrowser: () => Promise<void>) {
    this.page = page;
    this.closeBrowser = closeBrowser;
  }

  static async open(): Promise<ImageComparer> {
    const browser = await chromium.launch();
    const page = await browser.newPage();
    return new ImageComparer(page, async () => browser.close());
  }

  async compare(baseline: string, current: string, diffFile: string): Promise<Comparison> {
    if (!existsSync(baseline)) return { changedPixels: Number.POSITIVE_INFINITY, ratio: 1 };
    const before = readFileSync(baseline);
    const after = readFileSync(current);
    if (before.equals(after)) return { changedPixels: 0, ratio: 0 };
    const raw: unknown = await this.page.evaluate(`(${DIFF_SOURCE})(${JSON.stringify(dataUrl(baseline))}, ${JSON.stringify(dataUrl(current))}, ${String(CHANNEL_TOLERANCE)})`);
    const result = DIFF_RESULT.parse(raw);
    if (result.diff !== null) writeFileSync(diffFile, Buffer.from(result.diff.replace(/^data:image\/png;base64,/, ""), "base64"));
    return { changedPixels: result.changed, ratio: result.total === 0 ? 0 : result.changed / result.total };
  }

  async close(): Promise<void> {
    await this.closeBrowser();
  }
}
