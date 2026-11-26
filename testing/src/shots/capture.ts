import { createHash } from "node:crypto";
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { chromium, type Browser, type BrowserContext, type Page, type Request, type Route } from "playwright-core";
import { SETUP_STEPS } from "../lib/contract";
import { HttpStatus, type SessionCredentials } from "../lib/http";
import type { Logger } from "../lib/log";
import { errorMessage } from "../lib/util";
import type { ShotsBackend } from "./backend";
import { VIRTUAL_NOW } from "./dataset";
import { SHOTS_ASSETS } from "./paths";
import { SCREENS_THAT_POLL, SCREENS_WITH_A_PERMANENT_SPINNER, SETUP_CODE_SCREEN, setupScreenId, shotFile, STABLE_TEXT, VARIANTS, type Screen, type ScreenAccess, type ScreenContext, type StableText, type Variant } from "./screens";

const DESKTOP = { width: 1440, height: 900 } as const;
export const MOBILE = { width: 390, height: 844 } as const;
const SESSION_KEY_STORAGE = "modl.sessionKey";
const HEADS_HOST = "mc-heads.net";
const SETTLE_TIMEOUT_MS = 15_000;
const FINAL_PAUSE_MS = 300;
const NAVIGATION_TIMEOUT_MS = 45_000;
const FREEZE_CSS = [
  "*,*::before,*::after{animation-duration:0s!important;animation-delay:0s!important;animation-iteration-count:1!important;",
  "transition-duration:0s!important;transition-delay:0s!important;caret-color:transparent!important;scroll-behavior:auto!important}",
].join("");
const BUSY_SELECTOR = ".animate-spin, .animate-pulse";

export interface Shot {
  screen: string;
  variant: Variant;
  file: string;
  problems: string[];
}

export interface CaptureOptions {
  outDir: string;
  only: string | null;
}

function initScript(sessionKey: string | null): string {
  const storeKey = sessionKey === null ? "" : `try{localStorage.setItem(${JSON.stringify(SESSION_KEY_STORAGE)},${JSON.stringify(sessionKey)})}catch(e){}`;
  const freeze = `(function(){var s=document.createElement('style');s.textContent=${JSON.stringify(FREEZE_CSS)};(document.head||document.documentElement).appendChild(s);})();`;
  return `${storeKey}if(document.documentElement){${freeze}}else{document.addEventListener('DOMContentLoaded',function(){${freeze}});}`;
}

function headFile(url: string): string {
  return join(SHOTS_ASSETS, "heads", `${createHash("sha256").update(url).digest("hex").slice(0, 24)}.png`);
}

export class ShotBrowser {
  private readonly browser: Browser;
  private readonly backend: ShotsBackend;
  private readonly blocked = new Set<string>();

  private constructor(browser: Browser, backend: ShotsBackend) {
    this.browser = browser;
    this.backend = backend;
  }

  static async open(backend: ShotsBackend): Promise<ShotBrowser> {
    return new ShotBrowser(await chromium.launch({ args: ["--hide-scrollbars", "--font-render-hinting=none"] }), backend);
  }

  blockedHosts(): string[] {
    return [...this.blocked].sort();
  }

  async context(variant: Variant, credentials: SessionCredentials | null): Promise<BrowserContext> {
    const mobile = variant.device === "mobile";
    const context = await this.browser.newContext({
      viewport: mobile ? MOBILE : DESKTOP,
      deviceScaleFactor: mobile ? 2 : 1,
      isMobile: mobile,
      hasTouch: mobile,
      colorScheme: variant.scheme,
      reducedMotion: "reduce",
      locale: "en-US",
      timezoneId: "UTC",
      serviceWorkers: "block",
    });
    await context.clock.setFixedTime(VIRTUAL_NOW);
    await context.addInitScript({ content: initScript(credentials?.sessionKey ?? null) });
    if (credentials !== null) {
      const url = new URL(this.backend.baseUrl);
      await context.addCookies(Object.entries(credentials.cookies).map(([name, value]) => ({ name, value, domain: url.hostname, path: "/" })));
    }
    await context.route("**/*", async (route) => this.handle(route));
    return context;
  }

  private async handle(route: Route): Promise<void> {
    const url = new URL(route.request().url());
    if (url.origin === new URL(this.backend.baseUrl).origin) {
      await route.continue();
      return;
    }
    if (url.hostname === HEADS_HOST) {
      await this.serveHead(route, url.toString());
      return;
    }
    this.blocked.add(url.hostname);
    await route.abort("blockedbyclient");
  }

  private async serveHead(route: Route, url: string): Promise<void> {
    const file = headFile(url);
    if (existsSync(file)) {
      await route.fulfill({ status: 200, contentType: "image/png", body: readFileSync(file) });
      return;
    }
    try {
      const response = await route.fetch();
      const body = await response.body();
      if (response.ok()) {
        mkdirSync(join(SHOTS_ASSETS, "heads"), { recursive: true });
        writeFileSync(file, body);
      }
      await route.fulfill({ response, body });
    } catch {
      await route.abort("failed");
    }
  }

  async close(): Promise<void> {
    await this.browser.close();
  }
}

interface SettleRules {
  spinnerExpected: boolean;
  polls: boolean;
  stableText: readonly StableText[];
}

const POLLING_SETTLE_MS = 2500;

const REPLACE_TEXT = `(rules) => {
  const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
  const patterns = rules.map((rule) => ({ pattern: new RegExp(rule.source, rule.flags), replacement: rule.replacement }));
  for (let node = walker.nextNode(); node !== null; node = walker.nextNode()) {
    const text = node.nodeValue ?? "";
    const trimmed = text.trim();
    for (const rule of patterns) {
      if (rule.pattern.global) node.nodeValue = node.nodeValue.replace(rule.pattern, rule.replacement);
      else if (rule.pattern.test(trimmed)) node.nodeValue = text.replace(trimmed, rule.replacement);
    }
  }
}`;

async function stabilizeText(page: Page, rules: readonly StableText[]): Promise<void> {
  if (rules.length === 0) return;
  const serialized = rules.map((rule) => ({ source: rule.pattern.source, flags: rule.pattern.flags, replacement: rule.replacement }));
  await page.evaluate(`(${REPLACE_TEXT})(${JSON.stringify(serialized)})`);
}

async function settle(page: Page, rules: SettleRules): Promise<string[]> {
  const problems: string[] = [];
  if (rules.polls) await page.waitForTimeout(POLLING_SETTLE_MS);
  else await page.waitForLoadState("networkidle", { timeout: SETTLE_TIMEOUT_MS }).catch(() => problems.push("the network never went quiet"));
  await page.evaluate("document.fonts.ready.then(() => true)");
  if (rules.spinnerExpected) {
    await page.waitForTimeout(FINAL_PAUSE_MS);
    return problems;
  }
  await page
    .waitForFunction(`document.querySelectorAll(${JSON.stringify(BUSY_SELECTOR)}).length === 0`, undefined, { timeout: SETTLE_TIMEOUT_MS })
    .catch(() => problems.push(STALLED));
  await page.waitForTimeout(FINAL_PAUSE_MS);
  return problems;
}

const RATE_LIMIT_RETAKES = 3;

const STALL_RETAKES = 1;
const SETUP_WARM_UP = ["/v1/public/settings", "/v1/public/setup/status", "/v1/panel/auth/me", "/v1/panel/auth/permissions", "/v1/panel/setup"];
const STALLED = "a spinner or loading placeholder was still showing";

async function shoot(page: Page, backend: ShotsBackend, screen: string, path: string, file: string): Promise<string[]> {
  let stalls = 0;
  for (let attempt = 0; ; attempt += 1) {
    const outcome = await shootOnce(page, backend.baseUrl, path, file, {
      spinnerExpected: SCREENS_WITH_A_PERMANENT_SPINNER.has(screen),
      polls: SCREENS_THAT_POLL.has(screen),
      stableText: STABLE_TEXT.get(screen) ?? [],
    });
    if (outcome.rateLimited) {
      if (attempt >= RATE_LIMIT_RETAKES) return [...outcome.problems, "rate limited on every attempt"];
      await backend.skipRateLimitWindow();
      continue;
    }
    if (!outcome.problems.some((problem) => problem.startsWith(STALLED)) || stalls >= STALL_RETAKES) return outcome.problems;
    stalls += 1;
  }
}

async function shootOnce(page: Page, baseUrl: string, path: string, file: string, rules: SettleRules): Promise<{ problems: string[]; rateLimited: boolean }> {
  const problems: string[] = [];
  let rateLimited = false;
  const onError = (error: Error) => problems.push(`page error: ${error.message}`);
  const onConsole = (message: { type(): string; text(): string }) => {
    if (message.type() === "error" && !message.text().startsWith("Failed to load resource")) problems.push(`console error: ${message.text().slice(0, 300)}`);
  };
  const onResponse = (response: { status(): number; url(): string }) => {
    if (response.status() === 429) rateLimited = true;
    const pathname = new URL(response.url()).pathname;
    const missingAsset = response.status() === 404 && !pathname.startsWith("/v1/");
    if (response.status() >= 500 || missingAsset) problems.push(`HTTP ${String(response.status())} from ${pathname}`);
  };
  const pending = new Set<Request>();
  const onRequest = (request: Request) => pending.add(request);
  const onSettled = (request: Request) => pending.delete(request);
  page.on("pageerror", onError);
  page.on("console", onConsole);
  page.on("response", onResponse);
  page.on("request", onRequest);
  page.on("requestfinished", onSettled);
  page.on("requestfailed", onSettled);
  try {
    await page.goto(`${baseUrl}${path}`, { waitUntil: "load", timeout: NAVIGATION_TIMEOUT_MS });
    problems.push(...(await settle(page, rules)).map((problem) => (problem === STALLED ? withPending(problem, pending) : problem)));
    await stabilizeText(page, rules.stableText);
    await page.screenshot({ path: file, animations: "disabled", caret: "hide" });
  } catch (error) {
    problems.push(`capture failed: ${errorMessage(error)}`);
  } finally {
    page.off("pageerror", onError);
    page.off("console", onConsole);
    page.off("response", onResponse);
    page.off("request", onRequest);
    page.off("requestfinished", onSettled);
    page.off("requestfailed", onSettled);
  }
  return { problems: problems.filter((problem) => !problem.includes("ERR_BLOCKED_BY_CLIENT") && !problem.includes("429")), rateLimited };
}

function withPending(problem: string, pending: ReadonlySet<Request>): string {
  const paths = [...pending].map((request) => new URL(request.url()).pathname).filter((path) => path.startsWith("/v1/"));
  return paths.length === 0 ? problem : `${problem} (waiting on ${paths.join(", ")})`;
}

function selected(only: string | null, screen: string): boolean {
  return only === null || screen.includes(only);
}

export interface PanelCaptureInput {
  backend: ShotsBackend;
  credentials: SessionCredentials;
  screens: readonly Screen[];
  context: ScreenContext;
}

const PARALLEL_VARIANTS = 2;

async function withPages<T>(browser: ShotBrowser, credentials: SessionCredentials | null, run: (variant: Variant, page: Page) => Promise<T[]>): Promise<T[]> {
  const results: T[] = [];
  for (let start = 0; start < VARIANTS.length; start += PARALLEL_VARIANTS) {
    const batch = await Promise.all(
      VARIANTS.slice(start, start + PARALLEL_VARIANTS).map(async (variant) => {
        const context = await browser.context(variant, credentials);
        try {
          return await run(variant, await context.newPage());
        } finally {
          await context.close();
        }
      }),
    );
    results.push(...batch.flat());
  }
  return results;
}

export async function capturePanel(input: PanelCaptureInput, options: CaptureOptions, logger: Logger): Promise<Shot[]> {
  const browser = await ShotBrowser.open(input.backend);
  try {
    const byAccess = (access: ScreenAccess) => input.screens.filter((screen) => screen.access === access && selected(options.only, screen.id));
    const shots: Shot[] = [];
    for (const access of ["owner", "anonymous"] as const) {
      const screens = byAccess(access);
      if (screens.length === 0) continue;
      logger.info(`capturing ${String(screens.length)} ${access === "owner" ? "panel" : "public"} screens in ${String(VARIANTS.length)} variants`);
      shots.push(
        ...(await withPages(browser, access === "owner" ? input.credentials : null, async (variant, page) => {
          const taken: Shot[] = [];
          for (const screen of screens) {
            const path = screen.path(input.context);
            if (path === null) {
              taken.push({ screen: screen.id, variant, file: "", problems: ["its data was not found in the dataset"] });
              continue;
            }
            const file = join(options.outDir, shotFile(screen.id, variant));
            taken.push({ screen: screen.id, variant, file, problems: await shoot(page, input.backend, screen.id, path, file) });
          }
          return taken;
        })),
      );
    }
    const blocked = browser.blockedHosts();
    if (blocked.length > 0) logger.info(`blocked outside requests to ${blocked.join(", ")}`);
    return shots;
  } finally {
    await browser.close();
  }
}

export async function captureSetup(backend: ShotsBackend, options: CaptureOptions, logger: Logger): Promise<Shot[]> {
  const wanted = [SETUP_CODE_SCREEN, ...SETUP_STEPS.map(setupScreenId)].filter((screen) => selected(options.only, screen));
  if (wanted.length === 0) return [];
  logger.info(`capturing ${String(wanted.length)} setup screens in ${String(VARIANTS.length)} variants`);
  const browser = await ShotBrowser.open(backend);
  const shots: Shot[] = [];
  const take = async (screen: string, path: string, credentials: SessionCredentials | null) => {
    if (!wanted.includes(screen)) return;
    shots.push(
      ...(await withPages(browser, credentials, async (variant, page) => {
        const file = join(options.outDir, shotFile(screen, variant));
        return [{ screen, variant, file, problems: await shoot(page, backend, screen, path, file) }];
      })),
    );
  };
  try {
    await take(SETUP_CODE_SCREEN, "/setup", null);
    const instance = backend.instance();
    const code = await instance.latestSetupCode();
    if (code === null) throw new Error("the fresh panel printed no setup code");
    const claimed = await instance.submitClaim(code.code);
    if (claimed.status !== HttpStatus.ok) throw new Error(`the setup claim answered ${String(claimed.status)}: ${claimed.text.slice(0, 200)}`);
    await backend.warmUp(SETUP_WARM_UP);
    for (const step of SETUP_STEPS) {
      await take(setupScreenId(step), `/setup/${step}`, instance.http.credentials());
      await backend.withRateLimitRetry(async () => instance.completeStep(step));
    }
    return shots;
  } finally {
    await browser.close();
  }
}
