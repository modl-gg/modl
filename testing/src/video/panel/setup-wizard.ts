import type { Locator, Page } from "playwright-core";
import type { Hands, Narrator } from "../studio/pointer";
import type { SpokenClip } from "../studio/segment";

export type WizardScreen =
  | "code"
  | "account"
  | "import"
  | "identity"
  | "address"
  | "email"
  | "servers"
  | "minecraft-account"
  | "staff"
  | "player-addresses"
  | "ai"
  | "replays"
  | "content";

const STEP_TITLES: Record<WizardScreen, string> = {
  code: "Setup code",
  account: "Sign-in method",
  import: "Import from modl.gg",
  identity: "Server identity",
  address: "Panel address",
  email: "Email",
  servers: "Minecraft servers",
  "minecraft-account": "Minecraft account",
  staff: "Staff",
  "player-addresses": "Player addresses",
  ai: "AI moderation",
  replays: "Replays and anticheat",
  content: "Finish",
};

const RESULT_HOLD_MS = 1000;
const CONNECTED_BADGE = /^\[?Connected\]?$/;

export function connectedServer(page: Page, name: string): Locator {
  return page
    .locator("div")
    .filter({ has: page.getByText(name, { exact: true }) })
    .filter({ has: page.getByText(CONNECTED_BADGE) })
    .last();
}

export interface WizardCue {
  readonly label?: string;
  readonly say?: SpokenClip;
}

export interface Speaker {
  say(clip: SpokenClip): Promise<void>;
  untilSilent(): Promise<void>;
}

export interface WizardDirection {
  readonly narrator: Narrator;
  readonly speaker?: Speaker;
  readonly cues: Partial<Record<WizardScreen, WizardCue>>;
}

export type ReplayChoice = "off" | "on" | "every player";

export interface WizardObserver {
  entered(screen: WizardScreen): Promise<void>;
  finishing(): void;
}

export interface WizardOwner {
  email: string;
  password: string;
  serverName: string;
}

const STEP_TIMEOUT_MS = 30_000;
const SETTLE_MS = 350;

async function waitEnabled(target: Locator, timeoutMs = STEP_TIMEOUT_MS): Promise<void> {
  await target.waitFor({ state: "visible", timeout: timeoutMs });
  const deadline = Date.now() + timeoutMs;
  while (!(await target.isEnabled())) {
    if (Date.now() > deadline) throw new Error("a control stayed disabled");
    await target.page().waitForTimeout(100);
  }
}

export class SetupWizard {
  private readonly page: Page;
  private readonly hands: Hands;
  private readonly direction: WizardDirection | null;

  private readonly observer: WizardObserver | null;

  constructor(page: Page, hands: Hands, direction: WizardDirection | null, observer: WizardObserver | null = null) {
    this.page = page;
    this.hands = hands;
    this.direction = direction;
    this.observer = observer;
  }

  async open(origin: string): Promise<void> {
    await this.page.goto(`${origin}/setup`);
    await this.codeField().waitFor({ timeout: STEP_TIMEOUT_MS });
  }

  async claim(code: string, owner: WizardOwner): Promise<void> {
    await this.enter("code");
    await this.hands.type(this.codeField(), code);
    await this.hands.type(this.page.getByLabel("Owner email"), owner.email);
    await this.advance("Continue", "account");
  }

  async complete(owner: WizardOwner, replays: ReplayChoice, panelUrl: string | null = null): Promise<void> {
    await this.enter("account");
    await this.choose("Password");
    await this.hands.type(this.page.getByRole("textbox", { name: "Password", exact: true }), owner.password);
    await this.hands.type(this.page.getByRole("textbox", { name: "Confirm password" }), owner.password);
    await this.advance("Continue", "import");

    await this.enter("import");
    await this.choose("Start fresh");
    await this.advance("Continue", "identity");

    await this.enter("identity");
    await this.hands.replace(this.page.getByLabel("Server display name"), owner.serverName);
    await this.advance("Continue", "address");

    await this.enter("address");
    if (panelUrl !== null) {
      await this.hands.replace(this.page.getByRole("textbox", { name: "Panel URL" }), panelUrl);
      await this.hands.click(this.page.getByRole("button", { name: "Save", exact: true }));
      const confirm = this.page.getByRole("button", { name: "Change URL", exact: true });
      if (await confirm.waitFor({ timeout: 5000 }).then(() => true, () => false)) await this.hands.click(confirm);
    }
    await this.page.getByRole("status").getByRole("img", { name: "Passed", exact: true }).waitFor({ timeout: STEP_TIMEOUT_MS });
    await this.direction?.narrator.hold(RESULT_HOLD_MS);
    await this.advance("Continue", "email");

    await this.enter("email");
    await this.choose("No email delivery");
    await this.advance("Continue", "servers");

    await this.enter("servers");
    await this.page.getByText(CONNECTED_BADGE).first().waitFor({ timeout: STEP_TIMEOUT_MS });
    await this.direction?.narrator.hold(RESULT_HOLD_MS);
    await this.advance("Continue", "minecraft-account");

    await this.enter("minecraft-account");
    await this.choose("Don't link an account");
    await this.advance("Continue", "staff");

    await this.enter("staff");
    await this.choose("No staff yet");
    await this.advance("Continue", "player-addresses");

    await this.enter("player-addresses");
    await this.choose("No lookups");
    await this.advance("Continue", "ai");

    await this.enter("ai");
    await this.choose("No AI moderation");
    await this.advance("Continue", "replays");

    await this.enter("replays");
    await this.choose(replays === "off" ? "No replays" : "Turn on replays");
    if (replays === "every player") await this.hands.click(this.page.getByRole("checkbox", { name: "Record every player" }));
    await this.choose("No anticheat integration");
    await this.advance("Continue", "content");

    await this.enter("content");
    this.observer?.finishing();
    await this.press("Finish setup");
    await this.page.waitForURL("**/panel", { timeout: STEP_TIMEOUT_MS });
    await this.page.getByRole("heading", { name: "Dashboard" }).waitFor({ timeout: STEP_TIMEOUT_MS });
  }

  private codeField(): Locator {
    return this.page.getByLabel("Setup code", { exact: true });
  }

  private async enter(screen: WizardScreen): Promise<void> {
    const direction = this.direction;
    if (!direction && !this.observer) return;
    await this.page.getByRole("heading", { level: 1, name: STEP_TITLES[screen], exact: true }).waitFor({ timeout: STEP_TIMEOUT_MS });
    await this.observer?.entered(screen);
    if (!direction) return;
    const cue = direction.cues[screen];
    await direction.narrator.label(cue?.label ?? null);
    if (cue?.say) await direction.speaker?.say(cue.say);
    await direction.narrator.hold(SETTLE_MS);
  }

  private async choose(option: string): Promise<void> {
    await this.hands.click(this.page.getByText(option, { exact: true }));
  }

  private async advance(button: string, next: WizardScreen): Promise<void> {
    await this.press(button);
    await this.page.waitForURL(`**/setup/${next}`, { timeout: STEP_TIMEOUT_MS });
  }

  private async press(button: string): Promise<void> {
    const control = this.page.getByRole("button", { name: button, exact: true });
    await waitEnabled(control);
    await this.direction?.speaker?.untilSilent();
    await this.direction?.narrator.label(null);
    await this.hands.click(control);
  }
}
