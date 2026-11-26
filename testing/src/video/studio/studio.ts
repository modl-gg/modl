import { mkdirSync } from "node:fs";
import { join } from "node:path";
import { chromium, type Browser, type BrowserContext, type Page } from "playwright-core";
import { AddressProxy, type AddressRoute } from "./address-proxy";
import { OVERLAY_SCRIPT } from "./overlay";
import { captionScene } from "../scenes/caption";
import type { StillSegment } from "./segment";
import type { StageLayout, Viewport } from "./stage";
import { Take } from "./take";
import { CSS_VIEWPORT, DEVICE_SCALE } from "./theme";

export class PanelBrowser {
  readonly context: BrowserContext;
  private readonly workDir: string;
  private readonly stage: StageLayout;

  constructor(context: BrowserContext, workDir: string, stage: StageLayout) {
    this.context = context;
    this.workDir = workDir;
    this.stage = stage;
  }

  async page(): Promise<Page> {
    return this.context.newPage();
  }

  async take(name: string): Promise<Take> {
    return this.retake(await this.context.newPage(), name);
  }

  retake(page: Page, name: string): Take {
    return new Take(page, name, this.workDir, this.stage);
  }
}

const SIGNALS_LEFT_TO_THE_HARNESS = { handleSIGINT: false, handleSIGTERM: false, handleSIGHUP: false } as const;

function renderArgs(stage: StageLayout): string[] {
  return [`--force-device-scale-factor=${String(stage.deviceScale)}`, `--window-size=${String(stage.viewport.width)},${String(stage.viewport.height)}`, "--hide-scrollbars"];
}

function proxyAutoConfig(proxyServer: string, authorities: readonly string[]): string {
  const script = [
    "function FindProxyForURL(url, host) {",
    `  var routed = ${JSON.stringify(authorities)};`,
    "  var authority = url.split(\"/\")[2];",
    `  return routed.indexOf(authority) >= 0 ? ${JSON.stringify(`PROXY ${new URL(proxyServer).host}`)} : "DIRECT";`,
    "}",
  ].join("\n");
  return `data:application/x-ns-proxy-autoconfig;base64,${Buffer.from(script).toString("base64")}`;
}

export class Studio {
  readonly stage: StageLayout;
  private readonly browser: Browser;
  private readonly proxy = new AddressProxy();
  private readonly workDir: string;
  private sceneContext: BrowserContext | null = null;
  private stillContext: BrowserContext | null = null;
  private panelBrowserInstance: Browser | null = null;
  private canvasBrowser: Browser | null = null;
  private captionCount = 0;

  private constructor(browser: Browser, workDir: string, stage: StageLayout) {
    this.browser = browser;
    this.workDir = workDir;
    this.stage = stage;
  }

  static async open(workDir: string, stage: StageLayout): Promise<Studio> {
    mkdirSync(workDir, { recursive: true });
    return new Studio(await chromium.launch({ ...SIGNALS_LEFT_TO_THE_HARNESS, args: renderArgs(stage) }), workDir, stage);
  }

  route(route: AddressRoute): void {
    if (this.panelBrowserInstance) throw new Error("add every panel address before the first panel browser opens");
    this.proxy.route(route);
  }

  async panelBrowser(): Promise<PanelBrowser> {
    const browser = await this.panels();
    const context = await browser.newContext({ viewport: null, colorScheme: "dark", acceptDownloads: true });
    await context.addInitScript({ content: OVERLAY_SCRIPT });
    return new PanelBrowser(context, this.workDir, this.stage);
  }

  async scene(name: string, html: string): Promise<Take> {
    const page = await (await this.scenes()).newPage();
    await page.setContent(html, { waitUntil: "load" });
    await page.evaluate("document.fonts.ready");
    return new Take(page, name, this.workDir, this.stage);
  }

  async still(name: string, html: string, seconds: number): Promise<StillSegment> {
    const page = await (await this.stills()).newPage();
    try {
      await page.setContent(html, { waitUntil: "load" });
      await page.evaluate("document.fonts.ready");
      const image = join(this.workDir, `${name}.png`);
      await page.screenshot({ path: image });
      return { kind: "still", name, image, seconds };
    } finally {
      await page.close();
    }
  }

  async caption(text: string): Promise<string> {
    const page = await (await this.stills()).newPage();
    try {
      await page.setContent(captionScene(text), { waitUntil: "load" });
      await page.evaluate("document.fonts.ready");
      this.captionCount += 1;
      const image = join(this.workDir, "captions", `caption-${String(this.captionCount)}.png`);
      mkdirSync(join(this.workDir, "captions"), { recursive: true });
      await page.locator(".caption").screenshot({ path: image, omitBackground: true });
      return image;
    } finally {
      await page.close();
    }
  }

  async captureOpenPages(directory: string): Promise<string[]> {
    mkdirSync(directory, { recursive: true });
    const browsers = [this.browser, ...(this.panelBrowserInstance ? [this.panelBrowserInstance] : [])];
    const pages = browsers.flatMap((browser) => browser.contexts()).flatMap((context) => context.pages());
    const files: string[] = [];
    for (const [index, page] of pages.entries()) {
      const file = join(directory, `page-${String(index)}.png`);
      await page.screenshot({ path: file }).then(() => files.push(file)).catch(() => undefined);
    }
    return files;
  }

  async canvas(viewport: Viewport): Promise<Page> {
    this.canvasBrowser ??= await chromium.launch({ ...SIGNALS_LEFT_TO_THE_HARNESS, args: ["--hide-scrollbars"] });
    const context = await this.canvasBrowser.newContext({ viewport, deviceScaleFactor: 1, colorScheme: "dark" });
    return context.newPage();
  }

  async close(): Promise<void> {
    await this.canvasBrowser?.close();
    await this.panelBrowserInstance?.close();
    await this.browser.close();
    await this.proxy.close();
  }

  private async panels(): Promise<Browser> {
    if (this.panelBrowserInstance) return this.panelBrowserInstance;
    const proxyServer = await this.proxy.listen();
    this.panelBrowserInstance = await chromium.launch({
      ...SIGNALS_LEFT_TO_THE_HARNESS,
      args: [...renderArgs(this.stage), `--proxy-pac-url=${proxyAutoConfig(proxyServer, this.proxy.routedAuthorities())}`],
      downloadsPath: join(this.workDir, "downloads"),
    });
    return this.panelBrowserInstance;
  }

  private async stills(): Promise<BrowserContext> {
    if (this.stillContext) return this.stillContext;
    this.stillContext = await this.browser.newContext({ viewport: CSS_VIEWPORT, deviceScaleFactor: DEVICE_SCALE, colorScheme: "dark" });
    return this.stillContext;
  }

  private async scenes(): Promise<BrowserContext> {
    if (this.sceneContext) return this.sceneContext;
    const context = await this.browser.newContext({ viewport: null, colorScheme: "dark" });
    await context.addInitScript({ content: OVERLAY_SCRIPT });
    this.sceneContext = context;
    return context;
  }
}
