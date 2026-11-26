import type { Locator, Page } from "playwright-core";
import { ConsoleCommands, ConsolePatterns, REPLAY_CAPTURE_FAILED, replayCaptured } from "../../lib/contract";
import type { Environment } from "../../lib/environment";
import { sleep } from "../../lib/util";

const VIEWER_TIMEOUT_MS = 120_000;
const CAPTURE_TIMEOUT_MS = 90_000;
const SETTLE_MS = 2500;
const REPLAY_PARAM = "replay";
const BACK_OFF_MS = 450;
const CAMERA_SETTLE_MS = 400;
const HIDE_OVERLAYS_SCRIPT = `(() => {
  const world = document.querySelector('[role="dialog"] canvas[aria-label="Replay world"]');
  if (!world || !world.parentElement) throw new Error("the replay world is not open");
  for (const layer of world.parentElement.children) if (layer !== world) layer.style.visibility = "hidden";
})()`;

export function replayWorld(page: Page): Locator {
  return page.getByRole("dialog").getByLabel("Replay world", { exact: true });
}

export function replayControl(page: Page, name: string | RegExp): Locator {
  return page.getByRole("dialog").getByRole("button", { name, exact: typeof name === "string" });
}

export async function openReplay(page: Page, origin: string, replayId: string): Promise<void> {
  await page.goto(`${origin}/panel?${REPLAY_PARAM}=${encodeURIComponent(replayId)}`);
  await waitForReplay(page);
}

export async function waitForReplay(page: Page): Promise<void> {
  await replayWorld(page).waitFor({ timeout: VIEWER_TIMEOUT_MS });
  await replayControl(page, "Play").waitFor({ timeout: VIEWER_TIMEOUT_MS });
}

export async function playReplay(page: Page): Promise<void> {
  await replayControl(page, "Play").click();
  await sleep(SETTLE_MS);
}

export async function captureReplayOf(environment: Environment, server: string, player: string): Promise<string> {
  const outcome = new RegExp(`${replayCaptured(player).source}|${REPLAY_CAPTURE_FAILED.source}`, "i");
  const result = await environment.console(server).run(ConsoleCommands.replayCapture(player), { expect: outcome, timeoutMs: CAPTURE_TIMEOUT_MS });
  if (!replayCaptured(player).test(result.matched ?? "")) throw new Error(`the replay capture of ${player} failed: ${String(result.matched)}`);
  const line = await environment.log(server).waitFor(ConsolePatterns.replayLink, { since: result.since, timeoutMs: CAPTURE_TIMEOUT_MS });
  const id = ConsolePatterns.replayLink.exec(line.text)?.[1];
  if (id === undefined) throw new Error(`no replay id in ${line.text}`);
  return id;
}

export async function viewWithoutHud(page: Page): Promise<void> {
  await replayControl(page, "Follow camera").click();
  await replayControl(page, "First person").click();
  await page.keyboard.down("s");
  await sleep(BACK_OFF_MS);
  await page.keyboard.up("s");
  await page.evaluate(HIDE_OVERLAYS_SCRIPT);
  await sleep(CAMERA_SETTLE_MS);
}
