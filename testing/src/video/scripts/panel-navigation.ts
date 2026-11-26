import type { Locator, Page } from "playwright-core";
import type { Hands, Point } from "../studio/pointer";

const NAVIGATION_TIMEOUT_MS = 20_000;
const SETTLE_MS = 600;
const REVEAL_MARGIN_PX = 24;

export async function openPanelHome(page: Page, origin: string): Promise<void> {
  await page.goto(`${origin}/panel`);
  await page.getByRole("heading", { name: "Dashboard" }).waitFor({ timeout: NAVIGATION_TIMEOUT_MS });
}

export async function openRenderedSettings(page: Page, origin: string, path: string, heading: string): Promise<void> {
  await page.goto(`${origin}/panel/settings/${path}`);
  await page.getByRole("heading", { name: heading, exact: true }).waitFor({ timeout: NAVIGATION_TIMEOUT_MS });
  await page.getByRole("status").waitFor({ state: "detached", timeout: NAVIGATION_TIMEOUT_MS }).catch(() => undefined);
  await page.waitForTimeout(SETTLE_MS);
}

export async function openRenderedPage(page: Page, url: string, ready: Locator): Promise<void> {
  await page.goto(url);
  await ready.waitFor({ timeout: NAVIGATION_TIMEOUT_MS });
  await page.getByRole("status").waitFor({ state: "detached", timeout: NAVIGATION_TIMEOUT_MS }).catch(() => undefined);
  await page.waitForTimeout(SETTLE_MS);
}

export async function revealBottomOf(target: Locator): Promise<void> {
  const page = target.page();
  const box = await target.boundingBox();
  const viewportHeight: unknown = await page.evaluate("window.innerHeight");
  if (!box || typeof viewportHeight !== "number") throw new Error("the target to reveal has no visible box");
  const distance = Math.ceil(box.y + box.height + REVEAL_MARGIN_PX - viewportHeight);
  if (distance <= 0) return;
  await page.evaluate(`window.scrollBy({ top: ${String(distance)}, behavior: "instant" })`);
  await page.waitForTimeout(SETTLE_MS);
}

export async function centreOf(target: Locator): Promise<Point> {
  const box = await target.boundingBox();
  if (!box) throw new Error("the target has no visible box");
  return { x: Math.round(box.x + box.width / 2), y: Math.round(box.y + box.height / 2) };
}

export async function dismissForwardingNotice(page: Page): Promise<void> {
  const dismiss = page.getByRole("button", { name: "Dismiss", exact: true });
  await dismiss.waitFor({ timeout: NAVIGATION_TIMEOUT_MS });
  await dismiss.click();
  await dismiss.waitFor({ state: "detached", timeout: NAVIGATION_TIMEOUT_MS });
}

const NOTICE_WAIT_MS = 6000;

export async function dismissNoticeIfShown(page: Page): Promise<void> {
  const dismiss = page.getByRole("button", { name: "Dismiss", exact: true });
  if (!(await dismiss.waitFor({ timeout: NOTICE_WAIT_MS }).then(() => true, () => false))) return;
  await dismiss.click();
  await dismiss.waitFor({ state: "detached", timeout: NAVIGATION_TIMEOUT_MS });
}

export async function openSettingsPage(page: Page, hands: Hands, name: string): Promise<void> {
  await hands.click(page.getByRole("button", { name: "Settings", exact: true }));
  await page.waitForURL("**/panel/settings/**", { timeout: NAVIGATION_TIMEOUT_MS });
  const link = page.getByRole("link", { name, exact: true });
  await link.waitFor({ timeout: NAVIGATION_TIMEOUT_MS });
  if ((await link.getAttribute("aria-current")) === "page") return;
  await hands.click(link);
  await page.getByRole("heading", { name, exact: true }).waitFor({ timeout: NAVIGATION_TIMEOUT_MS });
}
