import { runProcess } from "../../lib/process";
import { sleep } from "../../lib/util";
import type { Point } from "../studio/pointer";
import type { GameClient } from "./game-client";
import type { GuiLayout, Rect } from "./gui-layout";

export type GameKey = "return" | "esc" | "openChat" | "openCommand";

const TITLE_BAR_POINTS = 28;
const CLICLICK = "cliclick";
const SETTLE_AFTER_FOCUS_MS = 4000;
const FOCUS_TIMEOUT_MS = 3000;
const KEY_CODES: Readonly<Record<GameKey, number>> = { return: 36, esc: 53, openChat: 17, openCommand: 44 };
const FOCUS_LOST = "the Minecraft window lost focus";
const WINDOW = 'first window of (first process whose unix id is pidArg) whose name starts with "Minecraft"';
const REQUIRE_FRONT = `if unix id of (first process whose frontmost is true) is not pidArg then error "${FOCUS_LOST}"`;

const PLACE_SCRIPT = [
  "on run argv",
  "set pidArg to (item 1 of argv) as integer",
  'tell application "System Events"',
  `set w to ${WINDOW}`,
  "set position of w to {(item 2 of argv) as integer, (item 3 of argv) as integer}",
  "set size of w to {(item 4 of argv) as integer, (item 5 of argv) as integer}",
  "end tell",
  "end run",
] as const;

const STATE_SCRIPT = [
  "on run argv",
  "set pidArg to (item 1 of argv) as integer",
  'tell application "System Events"',
  `set w to ${WINDOW}`,
  "return {unix id of (first process whose frontmost is true)} & (position of w) & (size of w)",
  "end tell",
  "end run",
] as const;

const KEY_SCRIPT = [
  "on run argv",
  "set pidArg to (item 1 of argv) as integer",
  'tell application "System Events"',
  REQUIRE_FRONT,
  "key code ((item 2 of argv) as integer)",
  "end tell",
  "end run",
] as const;

const TYPE_SCRIPT = [
  "on run argv",
  "set pidArg to (item 1 of argv) as integer",
  "set stepSeconds to ((item 3 of argv) as number) / 1000",
  'tell application "System Events"',
  "repeat with typed in characters of (item 2 of argv)",
  REQUIRE_FRONT,
  "keystroke typed",
  "delay stepSeconds",
  "end repeat",
  "end tell",
  "end run",
] as const;

async function osascript(lines: readonly string[], args: readonly (string | number)[]): Promise<string> {
  return (await runProcess("osascript", [...lines.flatMap((line) => ["-e", line]), ...args.map(String)])).stdout.trim();
}

interface WindowState {
  frontmostPid: number;
  bounds: Rect;
}

function parseState(text: string): WindowState {
  const numbers = text.split(",").map((part) => Number(part.trim()));
  const [frontmostPid, x, y, width, height] = numbers;
  if (numbers.length !== 5 || frontmostPid === undefined || x === undefined || y === undefined || width === undefined || height === undefined || numbers.some(Number.isNaN)) {
    throw new Error(`unexpected window state "${text}"`);
  }
  return { frontmostPid, bounds: { x, y, width, height } };
}

function sameRect(left: Rect, right: Rect): boolean {
  return left.x === right.x && left.y === right.y && left.width === right.width && left.height === right.height;
}

export function windowPlacement(content: { width: number; height: number }): Rect {
  return { x: 150, y: 60, width: content.width, height: content.height + TITLE_BAR_POINTS };
}

export class GameWindow {
  readonly content: Rect;
  private readonly client: GameClient;
  private readonly placement: Rect;

  private constructor(client: GameClient, placement: Rect) {
    this.client = client;
    this.placement = placement;
    this.content = { x: placement.x, y: placement.y + TITLE_BAR_POINTS, width: placement.width, height: placement.height - TITLE_BAR_POINTS };
  }

  static async place(client: GameClient, placement: Rect): Promise<GameWindow> {
    const window = new GameWindow(client, placement);
    await window.restorePlacement();
    return window;
  }

  async isFrontmost(): Promise<boolean> {
    return (await this.state()).frontmostPid === this.client.pid;
  }

  async bringToFront(): Promise<void> {
    await this.restorePlacement();
    if (await this.isFrontmost()) return;
    await runProcess("osascript", ["-e", `tell application "System Events" to set frontmost of (first process whose unix id is ${String(this.client.pid)}) to true`]);
    const deadline = Date.now() + FOCUS_TIMEOUT_MS;
    while (!(await this.isFrontmost())) {
      if (Date.now() > deadline) throw new Error("the Minecraft window did not come to the front");
      await sleep(100);
    }
    await sleep(SETTLE_AFTER_FOCUS_MS);
  }

  async typeText(text: string, stepMs: number): Promise<void> {
    await this.requireReady();
    await osascript(TYPE_SCRIPT, [this.client.pid, text, stepMs]);
  }

  async press(key: GameKey): Promise<void> {
    await this.requireReady();
    await osascript(KEY_SCRIPT, [this.client.pid, KEY_CODES[key]]);
  }

  async moveTo(layout: GuiLayout, point: Point, easing: number): Promise<void> {
    await this.pointAt(layout, point, ["-e", String(easing), `m:${String(point.x)},${String(point.y)}`]);
  }

  async click(layout: GuiLayout, point: Point): Promise<void> {
    await this.pointAt(layout, point, [`c:${String(point.x)},${String(point.y)}`]);
  }

  private async pointAt(layout: GuiLayout, point: Point, commands: readonly string[]): Promise<void> {
    if (!sameRect(layout.content, this.content) || !layout.contains(point)) {
      throw new Error(`(${String(point.x)}, ${String(point.y)}) is outside the game area ${JSON.stringify(this.content)}`);
    }
    await this.requireReady();
    await runProcess(CLICLICK, [...commands]);
  }

  private async requireReady(): Promise<void> {
    const state = await this.state();
    if (state.frontmostPid !== this.client.pid) throw new Error(`${FOCUS_LOST}; no input was sent (keep the game in front while a video records)`);
    if (!sameRect(state.bounds, this.placement)) throw new Error(`the Minecraft window moved to ${JSON.stringify(state.bounds)}; no input was sent`);
  }

  private async state(): Promise<WindowState> {
    return parseState(await osascript(STATE_SCRIPT, [this.client.pid]));
  }

  private async restorePlacement(): Promise<void> {
    const { x, y, width, height } = this.placement;
    await osascript(PLACE_SCRIPT, [this.client.pid, x, y, width, height]);
    const bounds = (await this.state()).bounds;
    if (!sameRect(bounds, this.placement)) {
      throw new Error(`the Minecraft window stays at ${JSON.stringify(bounds)} instead of ${JSON.stringify(this.placement)}; leave fullscreen and try again`);
    }
  }
}
