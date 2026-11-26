import mineflayer, { type Bot, type BotOptions, type ScoreBoard } from "mineflayer";
import type { Logger } from "../log";
import { plainText, sleep } from "../util";
import { z } from "zod";
import { ComponentRenderer } from "./render";

const DUEL_STRAFE_MS = 1100;
const DUEL_JUMP_CHANCE = 0.3;
const HOTBAR_SIZE = 9;
const ATTACK_REACH = 3.2;

const WORLD_STATE_PACKET = z.object({ worldState: z.object({ dimension: z.unknown(), name: z.string() }) });
const LEGACY_WORLD_PACKET = z.object({ dimension: z.union([z.number(), z.string(), z.record(z.string(), z.unknown())]) });

function worldOf(packet: unknown): string | null {
  const modern = WORLD_STATE_PACKET.safeParse(packet);
  if (modern.success) return JSON.stringify([modern.data.worldState.dimension, modern.data.worldState.name]);
  const legacy = LEGACY_WORLD_PACKET.safeParse(packet);
  return legacy.success ? JSON.stringify(legacy.data.dimension) : null;
}

const WINDOW_ITEMS = z.object({ windowId: z.number(), items: z.array(z.unknown()) });

export interface BotTarget {
  host: string;
  port: number;
  version: string;
  joinTimeoutMs: number;
}

export interface CapturedMessage {
  seq: number;
  at: number;
  position: string;
  text: string;
  json: string;
}

export interface WindowSlot {
  slot: number;
  name: string;
  displayName: string;
  count: number;
  customName: string | null;
  lore: string[];
}

export interface StyledWindowSlot {
  slot: number;
  item: string;
  name: string;
  lore: string[];
}

export interface StyledWindowView {
  title: string;
  containerSlots: number;
  slots: StyledWindowSlot[];
}

export interface WindowView {
  id: number;
  type: string;
  title: string;
  containerSlots: number;
  slots: WindowSlot[];
}

export interface PlayerInfo {
  username: string;
  uuid: string;
  ping: number;
}

export interface ScoreboardView {
  title: string;
  entries: { name: string; value: number }[];
}

export interface BotStatus {
  username: string;
  connected: boolean;
  health: number | null;
  food: number | null;
  gameMode: string | null;
  position: { x: number; y: number; z: number } | null;
  serverSwitches: number;
  kickReason: string | null;
  kickReasonStyled: string | null;
  disconnectReason: string | null;
}

interface BotVitals {
  health?: number;
  food?: number;
  game?: { gameMode?: string };
  entity?: { position?: { x: number; y: number; z: number } };
}

interface SidebarSlot {
  sidebar?: ScoreBoard;
}

export interface RunCommandOptions {
  expect?: RegExp | undefined;
  settleMs?: number | undefined;
  timeoutMs?: number | undefined;
}

const MAX_MESSAGES = 400;
const ONCE_PER_CONFIGURATION = new Set(["select_known_packs", "accept_code_of_conduct"]);
const MENU_REJECTED_CLICK = "Server rejected transaction for clicking on slot ";

export class JoinRefusedError extends Error {
  readonly username: string;
  readonly reason: string;

  constructor(username: string, reason: string) {
    super(`${username} could not join: ${reason}`);
    this.username = username;
    this.reason = reason;
  }
}

export class TestBot {
  private bot: Bot | null = null;
  private readonly windowItemCounts = new Map<number, number>();
  private renderer: ComponentRenderer;
  private readonly messages: CapturedMessage[] = [];
  private sequence = 0;
  private online = false;
  private worldEntries = 0;
  private world: string | null = null;
  private respawnedIntoAnotherWorld = false;
  private kick: string | null = null;
  private kickStyled: string | null = null;
  private endReason: string | null = null;
  private readonly closeWaiters = new Set<(reason: string) => void>();
  private readonly windowWaiters = new Set<() => void>();
  private readonly switchWaiters = new Set<() => void>();
  readonly username: string;
  private readonly target: BotTarget;
  private readonly logger: Logger;

  constructor(username: string, target: BotTarget, logger: Logger) {
    this.username = username;
    this.target = target;
    this.logger = logger;
    this.renderer = new ComponentRenderer(target.version);
  }

  get connected(): boolean {
    return this.online;
  }

  get serverSwitches(): number {
    return Math.max(0, this.worldEntries - 1);
  }

  get lastSeq(): number {
    return this.sequence;
  }

  async join(): Promise<BotStatus> {
    if (this.bot) throw new Error(`bot ${this.username} was already started; create a new bot to rejoin`);
    const options: BotOptions = {
      host: this.target.host,
      port: this.target.port,
      username: this.username,
      version: this.target.version,
      auth: "offline",
      hideErrors: true,
      checkTimeoutInterval: 120_000,
    };
    const bot = mineflayer.createBot(options);
    this.bot = bot;
    this.answerConfigurationOncePerPhase(bot);
    this.wire(bot);
    await this.awaitSpawn(bot);
    this.online = true;
    this.logger.info(`bot ${this.username} joined ${this.target.host}:${this.target.port}`);
    return this.status();
  }

  private async awaitSpawn(bot: Bot): Promise<void> {
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => fail(`no spawn within ${this.target.joinTimeoutMs}ms`), this.target.joinTimeoutMs);
      const detach = () => {
        clearTimeout(timer);
        bot.off("spawn", onSpawn);
        bot.off("kicked", onKicked);
        bot.off("end", onEnd);
        bot.off("error", onError);
      };
      const fail = (reason: string) => {
        detach();
        this.online = false;
        try {
          bot.end();
        } catch {
          this.logger.debug(`bot ${this.username} was already closed`);
        }
        reject(new JoinRefusedError(this.username, reason));
      };
      const onSpawn = () => {
        detach();
        resolve();
      };
      const onKicked = (reason: string) => fail(this.renderer.render(reason));
      const onEnd = (reason: string) => fail(`disconnected before spawn (${reason})`);
      const onError = (error: Error) => fail(error.message);
      bot.once("spawn", onSpawn);
      bot.once("kicked", onKicked);
      bot.once("end", onEnd);
      bot.once("error", onError);
    });
  }

  private answerConfigurationOncePerPhase(bot: Bot): void {
    const client = bot._client;
    const answered = new Set<string>();
    client.on("state", (next: string) => {
      if (next === "configuration") answered.clear();
    });
    const write = client.write.bind(client);
    client.write = (name: string, params: unknown) => {
      if (ONCE_PER_CONFIGURATION.has(name)) {
        if (answered.has(name)) return;
        answered.add(name);
      }
      write(name, params);
    };
  }

  private wire(bot: Bot): void {
    bot.once("inject_allowed", () => {
      this.renderer = new ComponentRenderer(bot.version);
      if (bot.supportFeature("sendsClientTickEndPacket")) bot.on("physicsTick", () => this.sendTickEnd(bot));
    });
    bot.on("message", (message, position: string | undefined) => this.capture(plainText(message.toString()), position, message.json));
    bot.on("windowOpen", () => {
      for (const waiter of [...this.windowWaiters]) waiter();
    });
    bot._client.on("window_items", (packet: unknown) => this.recordWindowItems(packet));
    bot._client.on("login", (packet: unknown) => {
      this.world = worldOf(packet);
      this.respawnedIntoAnotherWorld = false;
      this.recordWorldEntry();
    });
    bot._client.on("respawn", (packet: unknown) => {
      const world = worldOf(packet);
      if (world === null || world === this.world) return;
      this.world = world;
      this.respawnedIntoAnotherWorld = true;
    });
    bot._client.on("position", () => {
      if (!this.respawnedIntoAnotherWorld) return;
      this.respawnedIntoAnotherWorld = false;
      this.recordWorldEntry();
    });
    bot.on("kicked", (reason) => {
      this.kick = this.renderer.render(reason);
      this.kickStyled = this.renderer.renderStyled(reason);
      this.logger.info(`bot ${this.username} kicked: ${this.kick}`);
      this.close(`kicked: ${this.kick}`);
    });
    bot.on("end", (reason: string | undefined) => {
      this.endReason = reason ?? null;
      this.close(reason ? `disconnected: ${reason}` : "disconnected");
    });
    bot.on("error", (error) => this.logger.debug(`bot ${this.username} error: ${error.message}`));
  }

  private sendTickEnd(bot: Bot): void {
    const protocolState: string = bot._client.state;
    if (protocolState === "play") bot._client.write("tick_end", {});
  }

  private capture(text: string, position: string | undefined, json: unknown): void {
    this.sequence += 1;
    this.messages.push({ seq: this.sequence, at: Date.now(), position: position ?? "system", text, json: safeJson(json) });
    if (this.messages.length > MAX_MESSAGES) this.messages.shift();
  }

  private close(reason: string): void {
    this.online = false;
    for (const waiter of [...this.closeWaiters]) waiter(reason);
    this.closeWaiters.clear();
  }

  private require(): Bot {
    if (!this.bot || !this.online) throw new Error(`bot ${this.username} is not connected`);
    return this.bot;
  }

  chat(message: string): void {
    this.require().chat(message);
  }

  async runCommand(command: string, options: RunCommandOptions = {}): Promise<CapturedMessage[]> {
    const since = this.sequence;
    this.chat(command);
    const deadline = Date.now() + (options.timeoutMs ?? 6000);
    const settleMs = options.settleMs ?? 900;
    const expect = options.expect;
    let lastCount = 0;
    let lastGrowth = Date.now();
    while (Date.now() < deadline) {
      await sleep(100);
      const received = this.messagesSince(since);
      if (expect && received.some((message) => expect.test(message.text))) return received;
      if (received.length !== lastCount) {
        lastCount = received.length;
        lastGrowth = Date.now();
      }
      if (!expect && Date.now() - lastGrowth >= settleMs) return received;
    }
    return this.messagesSince(since);
  }

  async awaitMessage(pattern: RegExp, timeoutMs = 8000, since = 0): Promise<CapturedMessage> {
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
      const hit = this.messagesSince(since).find((message) => pattern.test(message.text) || pattern.test(message.json));
      if (hit) return hit;
      await sleep(100);
    }
    throw new Error(`bot ${this.username} saw no message matching ${pattern.toString()} within ${timeoutMs}ms`);
  }

  messagesSince(seq: number): CapturedMessage[] {
    return this.messages.filter((message) => message.seq > seq);
  }

  recentMessages(limit = 20): CapturedMessage[] {
    return this.messages.slice(-limit);
  }

  recentStyledMessages(limit = 20): { position: string; text: string }[] {
    return this.recentMessages(limit).map((message) => ({ position: message.position, text: this.renderer.renderStyled(message.json) }));
  }

  searchMessages(pattern: RegExp, since = 0): RegExpExecArray | null {
    for (const message of this.messagesSince(since).reverse()) {
      const match = pattern.exec(message.json) ?? pattern.exec(message.text);
      if (match) return match;
    }
    return null;
  }

  async openWindow(trigger: string, timeoutMs = 8000): Promise<WindowView> {
    const opened = this.nextWindow(timeoutMs);
    this.chat(trigger);
    await opened;
    return this.requireWindow();
  }

  async clickAndAwaitWindow(slot: number, timeoutMs = 6000): Promise<WindowView> {
    const opened = this.nextWindow(timeoutMs);
    await this.clickSlot(slot);
    await opened;
    return this.requireWindow();
  }

  private async nextWindow(timeoutMs: number): Promise<void> {
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.windowWaiters.delete(onOpen);
        reject(new Error(`bot ${this.username}: no inventory opened within ${timeoutMs}ms`));
      }, timeoutMs);
      const onOpen = () => {
        clearTimeout(timer);
        this.windowWaiters.delete(onOpen);
        setTimeout(resolve, 250);
      };
      this.windowWaiters.add(onOpen);
    });
  }

  private requireWindow(): WindowView {
    const view = this.readWindow();
    if (!view) throw new Error(`bot ${this.username}: the window closed before it could be read`);
    return view;
  }

  readWindow(): WindowView | null {
    const window = this.require().currentWindow;
    if (!window) return null;
    const slots: WindowSlot[] = [];
    window.slots.forEach((item, index) => {
      if (!item || index >= window.inventoryStart) return;
      slots.push({
        slot: index,
        name: item.name,
        displayName: item.displayName,
        count: item.count,
        customName: item.customName ? this.renderer.render(item.customName) : null,
        lore: this.renderer.renderLore(item.customLore),
      });
    });
    return { id: window.id, type: String(window.type), title: this.renderer.render(window.title), containerSlots: window.inventoryStart, slots };
  }

  readStyledWindow(): StyledWindowView | null {
    const window = this.require().currentWindow;
    if (!window) return null;
    const slots: StyledWindowSlot[] = [];
    window.slots.forEach((item, index) => {
      if (!item || index >= window.inventoryStart) return;
      slots.push({ slot: index, item: item.name, name: this.renderer.renderStyled(item.customName), lore: this.renderer.renderStyledLore(item.customLore) });
    });
    return { title: this.renderer.renderStyled(window.title), containerSlots: window.inventoryStart, slots };
  }

  findSlot(pattern: RegExp): WindowSlot | null {
    const view = this.readWindow();
    return view?.slots.find((slot) => pattern.test(slot.customName ?? slot.displayName) || slot.lore.some((line) => pattern.test(line))) ?? null;
  }

  async clickSlot(slot: number, button: "left" | "right" = "left"): Promise<void> {
    const bot = this.require();
    if (!bot.currentWindow) throw new Error(`bot ${this.username} has no window open`);
    const click = button === "right" ? bot.simpleClick.rightMouse(slot) : bot.simpleClick.leftMouse(slot);
    await click.catch(rethrowUnlessMenuTookOverTheClick);
  }

  hotbar(): WindowSlot[] {
    const inventory = this.require().inventory;
    const slots: WindowSlot[] = [];
    for (let index = 0; index < HOTBAR_SIZE; index += 1) {
      const item = inventory.slots[inventory.hotbarStart + index];
      if (!item) continue;
      slots.push({
        slot: index,
        name: item.name,
        displayName: item.displayName,
        count: item.count,
        customName: item.customName ? this.renderer.render(item.customName) : null,
        lore: this.renderer.renderLore(item.customLore),
      });
    }
    return slots;
  }

  findHotbarSlot(pattern: RegExp): WindowSlot | null {
    return this.hotbar().find((slot) => pattern.test(slot.customName ?? slot.displayName)) ?? null;
  }

  async useHotbarSlot(slot: number): Promise<void> {
    const bot = this.require();
    bot.setQuickBarSlot(slot);
    await sleep(200);
    bot.activateItem();
    await sleep(200);
    bot.deactivateItem();
  }

  async wear(itemNames: readonly string[], timeoutMs = 10_000): Promise<void> {
    const bot = this.require();
    for (const name of itemNames) {
      const deadline = Date.now() + timeoutMs;
      let item = bot.inventory.items().find((candidate) => candidate.name === name);
      while (item === undefined) {
        if (Date.now() >= deadline) throw new Error(`bot ${this.username} has no ${name} to wear after ${String(timeoutMs)}ms`);
        await sleep(200);
        item = bot.inventory.items().find((candidate) => candidate.name === name);
      }
      await bot.equip(item, wornSlot(name));
    }
  }

  canSee(username: string): boolean {
    return this.require().players[username]?.entity !== undefined;
  }

  closeWindow(): void {
    const bot = this.require();
    const window = bot.currentWindow;
    if (!window) return;
    const inventory = bot.inventory;
    const syncedInventoryRegion = (this.windowItemCounts.get(window.id) ?? 0) >= window.slots.length;
    const playerSlots = syncedInventoryRegion ? null : inventory.slots.slice(inventory.inventoryStart, inventory.inventoryEnd);
    void bot.closeWindow(window);
    playerSlots?.forEach((item, offset) => {
      if (item) inventory.updateSlot(inventory.inventoryStart + offset, item);
    });
  }

  private recordWorldEntry(): void {
    this.worldEntries += 1;
    if (this.worldEntries > 1) for (const waiter of [...this.switchWaiters]) waiter();
  }

  private recordWindowItems(packet: unknown): void {
    const parsed = WINDOW_ITEMS.safeParse(packet);
    if (parsed.success) this.windowItemCounts.set(parsed.data.windowId, parsed.data.items.length);
  }

  async awaitServerSwitch(previousSwitches: number, timeoutMs = 20_000): Promise<void> {
    if (this.serverSwitches > previousSwitches) return;
    await new Promise<void>((resolve, reject) => {
      const timer = setTimeout(() => {
        this.switchWaiters.delete(onSwitch);
        reject(new Error(`bot ${this.username} did not switch servers within ${timeoutMs}ms`));
      }, timeoutMs);
      const onSwitch = () => {
        if (this.serverSwitches <= previousSwitches) return;
        clearTimeout(timer);
        this.switchWaiters.delete(onSwitch);
        resolve();
      };
      this.switchWaiters.add(onSwitch);
    });
    await sleep(1500);
  }

  async awaitDisconnect(timeoutMs: number): Promise<string> {
    if (!this.online) return Promise.resolve(this.kick ?? this.endReason ?? "disconnected");
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.closeWaiters.delete(onClose);
        reject(new Error(`bot ${this.username} stayed connected for ${timeoutMs}ms`));
      }, timeoutMs);
      const onClose = (reason: string) => {
        clearTimeout(timer);
        resolve(reason);
      };
      this.closeWaiters.add(onClose);
    });
  }

  async wander(durationMs: number): Promise<void> {
    const bot = this.require();
    const deadline = Date.now() + durationMs;
    let turn = 0;
    while (Date.now() < deadline && this.online) {
      await bot.look((turn * Math.PI) / 2, 0, true);
      bot.setControlState("forward", true);
      bot.setControlState("jump", turn % 2 === 0);
      await sleep(900);
      turn += 1;
    }
    bot.clearControlStates();
  }

  async lookAround(): Promise<void> {
    await this.require().look(Math.random() * 2 * Math.PI, (Math.random() - 0.5) * 0.6, false);
  }

  async attackNearestPlayer(range: number): Promise<string | null> {
    const bot = this.require();
    const own = bot.entity.position;
    const target = bot.nearestEntity((entity) => entity.type === "player" && entity.username !== this.username && entity.position.distanceTo(own) <= range);
    if (!target) {
      bot.clearControlStates();
      return null;
    }
    await bot.lookAt(target.position.offset(0, target.height * 0.85, 0), true);
    const distance = target.position.distanceTo(own);
    bot.setControlState("forward", distance > ATTACK_REACH - 0.7);
    bot.setControlState("sprint", distance > ATTACK_REACH + 1);
    if (distance <= ATTACK_REACH) bot.attack(target);
    return target.username ?? null;
  }

  async duel(range: number): Promise<string | null> {
    const target = await this.attackNearestPlayer(range);
    if (target === null) return null;
    const bot = this.require();
    const strafeLeft = Math.floor(Date.now() / DUEL_STRAFE_MS) % 2 === 0;
    bot.setControlState("left", strafeLeft);
    bot.setControlState("right", !strafeLeft);
    bot.setControlState("jump", Math.random() < DUEL_JUMP_CHANCE);
    return target;
  }

  listPlayers(): PlayerInfo[] {
    return Object.values(this.require().players).map((player) => ({ username: player.username, uuid: player.uuid, ping: player.ping }));
  }

  sidebar(): ScoreboardView | null {
    const slot: SidebarSlot = this.require().scoreboard;
    const board = slot.sidebar;
    if (!board) return null;
    return {
      title: this.renderer.render(board.title),
      entries: board.items.map((item) => ({ name: item.name, value: item.value })),
    };
  }

  status(): BotStatus {
    const bot: BotVitals | null = this.online ? this.bot : null;
    const position = bot?.entity?.position;
    return {
      username: this.username,
      connected: this.online,
      health: bot?.health ?? null,
      food: bot?.food ?? null,
      gameMode: bot?.game?.gameMode ?? null,
      position: position ? { x: position.x, y: position.y, z: position.z } : null,
      serverSwitches: this.serverSwitches,
      kickReason: this.kick,
      kickReasonStyled: this.kickStyled,
      disconnectReason: this.endReason,
    };
  }

  quit(): void {
    const bot = this.bot;
    if (!bot) return;
    this.online = false;
    try {
      bot.quit("test finished");
    } catch {
      bot.end();
    }
  }
}

function rethrowUnlessMenuTookOverTheClick(error: unknown): void {
  if (error instanceof Error && error.message.startsWith(MENU_REJECTED_CLICK)) return;
  throw error;
}

function safeJson(value: unknown): string {
  try {
    const json: unknown = JSON.stringify(value);
    return typeof json === "string" ? json : "";
  } catch {
    return "";
  }
}

type WornSlot = "head" | "torso" | "legs" | "feet" | "hand";

const WORN_SLOTS: readonly (readonly [RegExp, WornSlot])[] = [
  [/_helmet$/, "head"],
  [/_chestplate$/, "torso"],
  [/_leggings$/, "legs"],
  [/_boots$/, "feet"],
];

function wornSlot(itemName: string): WornSlot {
  return WORN_SLOTS.find(([pattern]) => pattern.test(itemName))?.[1] ?? "hand";
}
