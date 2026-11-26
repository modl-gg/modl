import { join } from "node:path";
import { cancellable } from "../../lib/cancellation";
import type { Environment } from "../../lib/environment";
import type { Logger } from "../../lib/log";
import { runProcess } from "../../lib/process";
import { entryGamePort } from "../../lib/state";
import { sleep } from "../../lib/util";
import type { VideoContext } from "../definition";
import type { Narrator, Point } from "../studio/pointer";
import type { FrameSegment } from "../studio/segment";
import { framePixels, type StageLayout } from "../studio/stage";
import { copiedText } from "./clipboard";
import { effectiveGuiScale, readClientOptions } from "./client-options";
import type { ChatLine } from "./client-log";
import { FootageTake } from "./footage-take";
import { findGameClient, type GameClient } from "./game-client";
import { GameWindow, windowPlacement, type GameKey } from "./game-window";
import { GuiLayout } from "./gui-layout";
import { ScreenProbe } from "./screen-probe";
import { screenDevice } from "./screen-recorder";

const CONTENT_WIDTH = 1500;
const JOIN_TIMEOUT_MS = 20 * 60_000;
const JOIN_POLL_MS = 2000;
const JOIN_REMINDER_MS = 60_000;
const MENU_CLOSE_MS = 800;
const IN_GAME_ATTEMPTS = 3;
const MENU_TIMEOUT_MS = 8000;
const TYPE_STEP_MS = 70;
const MOUSE_EASING = 520;
const CHAT_LINK_OFFSET = 24;
const DIALOG_ATTEMPTS = 2;
const DIALOG_TIMEOUT_MS = 2000;
const DIALOG_POLL_MS = 150;
const GROUND_Y = -60;
const VIEW_PITCH = 12;
const RCON_REFUSAL = /Unknown|Incorrect|Invalid|<--\[HERE\]|No (?:player|entity) was found/i;
const WORLD_RULES = ["time set noon", "weather clear", "gamerule advance_time false", "gamerule advance_weather false"] as const;

export interface Placement {
  readonly player: string;
  readonly x: number;
  readonly y: number;
  readonly z: number;
  readonly yaw: number;
  readonly pitch: number;
}

export interface Filmed<T> {
  readonly segment: FrameSegment;
  readonly value: T;
}

export interface CommandRun {
  readonly reply: ChatLine;
  readonly since: number;
}

export function viewpoint(player: string): Placement {
  return { player, x: 0.5, y: GROUND_Y, z: 0.5, yaw: 0, pitch: VIEW_PITCH };
}

export function standingAt(player: string, z: number, yaw: number): Placement {
  return { player, x: 0.5, y: GROUND_Y, z, yaw, pitch: 0 };
}

function onlinePlayers(listing: string): string[] {
  const names = /online:\s*(.*)$/s.exec(listing)?.[1] ?? "";
  return names.split(",").map((name) => name.trim()).filter((name) => name.length > 0);
}

function teleport(placement: Placement): string {
  return `tp ${placement.player} ${[placement.x, placement.y, placement.z, placement.yaw, placement.pitch].map(String).join(" ")}`;
}

function contentSize(stage: StageLayout): { width: number; height: number } {
  const pixels = framePixels(stage);
  return { width: CONTENT_WIDTH, height: Math.floor((CONTENT_WIDTH * pixels.height) / pixels.width) };
}

export async function requireRealClientReady(): Promise<GameClient> {
  const client = await findGameClient();
  readClientOptions(client.gameDir);
  await runProcess("cliclick", ["-V"]).catch((error: unknown) => {
    throw new Error("cliclick is not installed (brew install cliclick)", { cause: error });
  });
  await screenDevice();
  return client;
}

export class RealClient {
  readonly client: GameClient;
  readonly layout: GuiLayout;
  private readonly window: GameWindow;
  private readonly probe: ScreenProbe;
  private readonly context: VideoContext;

  private constructor(context: VideoContext, client: GameClient, window: GameWindow, probe: ScreenProbe) {
    this.context = context;
    this.client = client;
    this.window = window;
    this.probe = probe;
    this.layout = new GuiLayout(window.content, effectiveGuiScale(readClientOptions(client.gameDir), window.content.width, window.content.height));
  }

  static async attach(context: VideoContext): Promise<RealClient> {
    const client = await requireRealClientReady();
    const window = await GameWindow.place(client, windowPlacement(contentSize(context.studio.stage)));
    const probe = await ScreenProbe.open(window.content, join(context.workDir, "failure", "game-window"));
    const game = new RealClient(context, client, window, probe);
    context.logger.info(`filming ${client.username} in the Minecraft client (pid ${String(client.pid)}) at ${String(window.content.width)}x${String(window.content.height)} points, GUI scale ${String(game.layout.scale)}`);
    return game;
  }

  get username(): string {
    return this.client.username;
  }

  async waitUntilOnline(environment: Environment, logger: Logger): Promise<void> {
    const address = `localhost:${String(entryGamePort(environment.state))}`;
    const deadline = Date.now() + JOIN_TIMEOUT_MS;
    let remindAt = 0;
    while (!onlinePlayers(await environment.console(environment.state.coreServer).rcon("list")).includes(this.username)) {
      if (Date.now() > deadline) throw new Error(`${this.username} did not join ${address} within ${String(JOIN_TIMEOUT_MS / 60_000)} minutes`);
      if (Date.now() >= remindAt) {
        logger.info(`waiting for ${this.username} to join ${address} from the Minecraft client (Multiplayer > Direct Connection > ${address} > Join Server)`);
        remindAt = Date.now() + JOIN_REMINDER_MS;
      }
      await sleep(JOIN_POLL_MS);
    }
    logger.info(`${this.username} is online`);
  }

  async stageWorld(environment: Environment, placements: readonly Placement[]): Promise<void> {
    const server = environment.console(environment.state.coreServer);
    for (const command of [...WORLD_RULES, ...placements.map(teleport)]) {
      const reply = await server.rcon(command);
      if (RCON_REFUSAL.test(reply)) throw new Error(`the server refused "${command}": ${reply}`);
    }
  }

  async film<T>(name: string, scene: (take: FootageTake) => Promise<T>): Promise<Filmed<T>> {
    await this.window.bringToFront();
    await this.ensureInGame();
    const setup = { content: this.window.content, pixelScale: this.probe.pixelScale, ffmpeg: this.context.ffmpeg, workDir: this.context.workDir, stage: this.context.studio.stage };
    const take = new FootageTake(setup, name);
    const scenes = new AbortController();
    try {
      await take.start();
      const value = await take.whileRecording(cancellable(scenes.signal, async () => scene(take)));
      return { segment: await take.stop(), value };
    } catch (error) {
      scenes.abort(error);
      await take.abort();
      await this.probe.shot(`failed-${name}`).catch(() => undefined);
      throw error;
    }
  }

  async runCommand(narrator: Narrator, command: string, reply: RegExp, timeoutMs: number): Promise<CommandRun> {
    await this.typeCommand(narrator, command);
    const since = this.client.log.mark();
    await this.window.press("return");
    return { reply: await this.client.log.waitForChat(reply, since, timeoutMs), since };
  }

  async runCommandIntoMenu(narrator: Narrator, command: string, rows: number): Promise<number> {
    await this.typeCommand(narrator, command);
    const since = this.client.log.mark();
    await this.revealingMenu(rows, async () => this.window.press("return"));
    return since;
  }

  async hover(point: Point): Promise<void> {
    await this.window.moveTo(this.layout, point, MOUSE_EASING);
  }

  async click(point: Point): Promise<void> {
    await this.window.click(this.layout, point);
  }

  async clickRevealingMenu(point: Point, rows: number): Promise<void> {
    await this.revealingMenu(rows, async () => this.click(point));
  }

  async press(key: GameKey): Promise<void> {
    await this.window.press(key);
  }

  async copyNewestChatLink(narrator: Narrator, linkText: RegExp, since: number): Promise<string> {
    await this.press("openChat");
    await narrator.hold(500);
    const link = this.layout.newestChatLine(CHAT_LINK_OFFSET);
    await this.hover(link);
    await narrator.hold(1300);
    const newest = this.client.log.latestChat(since);
    if (newest === null || !linkText.test(newest)) throw new Error(`the newest chat line is "${String(newest)}", not the link to click`);
    const copy = await this.openDialogAt(link);
    await narrator.hold(1000);
    await this.hover(copy);
    await narrator.hold(700);
    const copied = await copiedText(async () => this.click(copy));
    await narrator.hold(400);
    await this.press("esc");
    return copied;
  }

  private async openDialogAt(point: Point): Promise<Point> {
    for (let attempt = 1; attempt <= DIALOG_ATTEMPTS; attempt += 1) {
      await this.click(point);
      const deadline = Date.now() + DIALOG_TIMEOUT_MS;
      while (Date.now() < deadline) {
        const button = await this.probe.dialogButton(this.layout);
        if (button) return button;
        await sleep(DIALOG_POLL_MS);
      }
    }
    throw new Error(`clicking the chat link opened no dialog; see ${this.probe.directory}`);
  }

  private async typeCommand(narrator: Narrator, command: string): Promise<void> {
    await this.press("openCommand");
    await narrator.hold(350);
    await this.window.typeText(command.replace(/^\//, ""), TYPE_STEP_MS);
    await narrator.hold(450);
  }

  private async revealingMenu(rows: number, action: () => Promise<void>): Promise<void> {
    const before = await this.probe.chestImage(this.layout, rows);
    await action();
    await this.probe.awaitMenu(this.layout, rows, before, MENU_TIMEOUT_MS);
  }

  private async ensureInGame(): Promise<void> {
    for (let attempt = 1; attempt <= IN_GAME_ATTEMPTS; attempt += 1) {
      if (await this.probe.showsWorld()) return;
      await this.press("esc");
      await sleep(MENU_CLOSE_MS);
    }
    throw new Error(`the Minecraft client still shows a menu after ${String(IN_GAME_ATTEMPTS)} Escape presses; see ${this.probe.directory}`);
  }
}
