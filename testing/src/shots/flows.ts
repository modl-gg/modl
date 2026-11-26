import { readFileSync } from "node:fs";
import { join } from "node:path";
import type { Locator, Page } from "playwright-core";
import { z } from "zod";
import { HttpStatus, jsonField, jsonString, type HttpResult, type SessionCredentials } from "../lib/http";
import type { Logger } from "../lib/log";
import { REPO_ROOT } from "../lib/paths";
import { errorMessage, writeJson, writeText } from "../lib/util";
import { EXAMPLE_PLAYER, seedPlayers } from "../seed/dataset";
import type { ShotsBackend } from "./backend";
import { MOBILE, ShotBrowser } from "./capture";
import { findInJson } from "./json";
import { VARIANTS } from "./screens";

const STEP_TIMEOUT_MS = 15_000;
const LOGIN_CODE = /Login code for (\S+): (\d{6})/;
const DESKTOP_DARK = VARIANTS.find((variant) => variant.id === "dark-desktop");
const LOCALE_FILE = join(REPO_ROOT, "panel", "client", "src", "locales", "en.json");
const LOCALE = z.record(z.string(), z.unknown());

export function localeLookup(): (key: string) => string {
  const root: unknown = LOCALE.parse(JSON.parse(readFileSync(LOCALE_FILE, "utf8")));
  return (key) => {
    let current: unknown = root;
    for (const part of key.split(".")) current = jsonField(current, part);
    if (typeof current !== "string") throw new Error(`the panel locale has no string ${key}`);
    return current;
  };
}

export class FlowContext {
  readonly backend: ShotsBackend;
  readonly t = localeLookup();
  private readonly browser: ShotBrowser;
  private readonly owner: SessionCredentials;
  readonly pages: Page[] = [];

  constructor(backend: ShotsBackend, browser: ShotBrowser, owner: SessionCredentials) {
    this.backend = backend;
    this.browser = browser;
    this.owner = owner;
  }

  async page(signedIn: boolean): Promise<Page> {
    if (DESKTOP_DARK === undefined) throw new Error("the dark desktop variant is missing");
    const context = await this.browser.context(DESKTOP_DARK, signedIn ? this.owner : null);
    const page = await context.newPage();
    page.setDefaultTimeout(STEP_TIMEOUT_MS);
    page.on("dialog", (dialog) => {
      dialog.accept().catch(() => undefined);
    });
    this.pages.push(page);
    return page;
  }

  async open(page: Page, path: string): Promise<void> {
    await page.goto(`${this.backend.baseUrl}${path}`, { waitUntil: "load" });
  }

  async api(path: string, method: "GET" | "POST" = "GET", json?: unknown): Promise<HttpResult> {
    const instance = this.backend.instance();
    return this.backend.request(async () => instance.api(path, json === undefined ? { method } : { method, json }));
  }

  async minecraft(path: string, json: unknown): Promise<unknown> {
    const instance = this.backend.instance();
    const result = await this.backend.request(async () => instance.apiWithCredentials(path, { method: "POST", json, auth: "api-key" }));
    if (result.status !== HttpStatus.ok && result.status !== HttpStatus.created) throw new Error(`${path} answered ${String(result.status)}: ${result.text.slice(0, 200)}`);
    return result.json;
  }

  async ticketIdWithSubject(subject: string): Promise<string> {
    const list = await this.api("/v1/panel/tickets?limit=50");
    const ticket = findInJson(list.json, (candidate) => jsonString(candidate, "subject") === subject);
    const id = jsonString(ticket, "id");
    if (id === null) throw new Error(`no ticket with the subject "${subject}"`);
    return id;
  }

  async playerText(uuid: string): Promise<string> {
    return (await this.api(`/v1/panel/players/${encodeURIComponent(uuid)}`)).text;
  }

  async loginCode(email: string, since: string): Promise<string> {
    const line = await this.backend.log.waitFor(LOGIN_CODE, { since, timeoutMs: STEP_TIMEOUT_MS });
    const match = LOGIN_CODE.exec(line.text);
    if (match?.[1] !== email || match[2] === undefined) throw new Error(`the login code line was for another address: ${match?.[1] ?? "none"}`);
    return match[2];
  }
}

function expect(condition: boolean, message: string): void {
  if (!condition) throw new Error(message);
}

async function visible(locator: Locator): Promise<void> {
  await locator.first().waitFor({ state: "visible" });
}

async function saveSettings(page: Page, context: FlowContext): Promise<void> {
  await page.getByRole("button", { name: context.t("settings.saveBar.save"), exact: true }).click();
  const refusal = page.locator("li", { hasText: context.t("toast.saveFailed") }).first();
  const settled = page.getByText(context.t("settings.saveBar.unsaved")).waitFor({ state: "hidden" }).then(() => null);
  const refused = refusal.waitFor({ state: "visible" }).then(async () => refusal.innerText());
  settled.catch(() => undefined);
  refused.catch(() => undefined);
  const outcome = await Promise.race([settled, refused]);
  if (outcome !== null) throw new Error(`the save was refused: ${outcome.replace(/\s+/g, " ").trim()}`);
}

async function eventually(check: () => Promise<boolean>, message: string): Promise<void> {
  const deadline = Date.now() + STEP_TIMEOUT_MS;
  while (Date.now() < deadline) {
    if (await check()) return;
    await new Promise((resolve) => setTimeout(resolve, 500));
  }
  throw new Error(message);
}

export interface Flow {
  name: string;
  run(context: FlowContext): Promise<void>;
}

const OTHER_PLAYER = seedPlayers().find((player) => player.name === "TNTnoob");

const SIGN_IN: Flow = {
  name: "sign in with an email code",
  async run(context) {
    const page = await context.page(false);
    await context.open(page, "/panel/auth");
    await page.getByRole("textbox", { name: context.t("auth.email") }).fill("owner@modl.test");
    const since = await context.backend.log.mark();
    await page.getByRole("button", { name: context.t("auth.sendCode") }).click();
    const code = await context.loginCode("owner@modl.test", since);
    await page.getByRole("textbox", { name: context.t("auth.verificationCode") }).fill(code);
    await page.getByRole("button", { name: context.t("auth.verifyLogin") }).click();
    await page.waitForURL((url) => !url.pathname.startsWith("/panel/auth"));
    await visible(page.getByRole("heading", { name: context.t("dashboard.title") }));
  },
};

const PLAYER_WINDOW: Flow = {
  name: "a player window shows the punishment history",
  async run(context) {
    const page = await context.page(true);
    await context.open(page, `/panel?player=${EXAMPLE_PLAYER.uuid}`);
    const window = page.getByRole("dialog", { name: EXAMPLE_PLAYER.name });
    await window.getByRole("tab", { name: context.t("player.tabs.history") }).click();
    await visible(window.getByText("Toxic behaviour toward other players"));
  },
};

const PUNISH: Flow = {
  name: "punish a player from the player window",
  async run(context) {
    const reason = "Flow: muted from the player window";
    const page = await context.page(true);
    await context.open(page, `/panel?player=${EXAMPLE_PLAYER.uuid}`);
    const window = page.getByRole("dialog", { name: EXAMPLE_PLAYER.name });
    await window.getByRole("tab", { name: context.t("player.tabs.punish") }).click();
    await window.getByRole("button", { name: "Manual Mute", exact: true }).click();
    await window.getByRole("spinbutton", { name: context.t("punishment.duration") }).fill("2");
    await window.getByRole("textbox", { name: context.t("punishment.reason") }).fill(reason);
    await window.getByRole("button", { name: /^Apply/ }).click();
    await window.getByRole("tab", { name: context.t("player.tabs.history") }).click();
    await visible(window.getByText(reason));
    await eventually(async () => (await context.playerText(EXAMPLE_PLAYER.uuid)).includes(reason), "the new mute is not on the player");
  },
};

const PUNISH_WITHOUT_REASON: Flow = {
  name: "ban a player without a reason and keep the staff note",
  async run(context) {
    const note = "Flow: banned without a reason";
    const page = await context.page(true);
    await context.open(page, `/panel?player=${EXAMPLE_PLAYER.uuid}`);
    const window = page.getByRole("dialog", { name: EXAMPLE_PLAYER.name });
    await window.getByRole("tab", { name: context.t("player.tabs.punish") }).click();
    await window.getByRole("button", { name: "Manual Ban", exact: true }).click();
    await window.getByRole("spinbutton", { name: context.t("punishment.duration") }).fill("1");
    await window.getByRole("textbox", { name: context.t("punishment.staffNotes") }).fill(note);
    await window.getByRole("button", { name: /^Apply/ }).click();
    await eventually(async () => (await context.playerText(EXAMPLE_PLAYER.uuid)).includes(note), "the new ban is not on the player");
    await window.getByRole("tab", { name: context.t("player.tabs.history") }).click();
    const entry = window.locator("div.rounded-lg.border", { hasText: "Manual Ban" }).first();
    await entry.getByRole("button", { name: context.t("common.showMore") }).click();
    await visible(entry.getByText(note));
  },
};

const TICKET_REPLY: Flow = {
  name: "reply to a ticket",
  async run(context) {
    const reply = "Flow: we restored your items.";
    const id = await context.ticketIdWithSubject("Lost my items");
    const page = await context.page(true);
    await context.open(page, `/panel/tickets/${encodeURIComponent(id)}`);
    await page.getByRole("textbox", { name: context.t("ticket.replyLabel") }).fill(reply);
    await page.getByRole("button", { name: context.t("ticket.send") }).click();
    await eventually(async () => (await context.api(`/v1/panel/tickets/${encodeURIComponent(id)}`)).text.includes(reply), "the reply is not on the ticket");
    await visible(page.getByText(reply));
  },
};

const ACCEPT_REPORT: Flow = {
  name: "accept a chat report and punish from the ticket",
  async run(context) {
    const id = await context.ticketIdWithSubject(`${EXAMPLE_PLAYER.name} is insulting players`);
    const before = await context.playerText(EXAMPLE_PLAYER.uuid);
    const page = await context.page(true);
    await context.open(page, `/panel/tickets/${encodeURIComponent(id)}`);
    await page.getByRole("combobox", { name: context.t("ticket.replyAction") }).click();
    await page.getByRole("option", { name: "Accept", exact: true }).click();
    await page.getByRole("button", { name: "Chat Abuse", exact: true }).click();
    await page.getByRole("tab", { name: context.t("punishment.severityRegular") }).click();
    await page.getByRole("button", { name: /^Apply/ }).click();
    await page.getByRole("button", { name: context.t("ticket.send") }).click();
    await eventually(async () => (await context.api(`/v1/panel/tickets/${encodeURIComponent(id)}`)).text.includes("accepted this"), "the accepting reply is not on the ticket");
    await eventually(async () => (await context.playerText(EXAMPLE_PLAYER.uuid)).length > before.length, "no punishment was added to the reported player");
  },
};

const APPEAL: Flow = {
  name: "appeal a ban publicly and answer it as staff",
  async run(context) {
    if (OTHER_PLAYER === undefined) throw new Error("the seed has no TNTnoob");
    const created = await context.minecraft("/v1/minecraft/punishments/dynamic", {
      targetUuid: OTHER_PLAYER.uuid,
      issuerName: "Console",
      type_ordinal: 2,
      reason: "Flow: ban to appeal",
      duration: 7 * 86_400_000,
      console: true,
    });
    const punishmentId = jsonString(created, "punishmentId");
    if (punishmentId === null) throw new Error(`the ban answered without an id: ${JSON.stringify(created).slice(0, 200)}`);
    await context.minecraft("/v1/minecraft/punishments/acknowledge", { punishmentId, playerUuid: OTHER_PLAYER.uuid, success: true });
    const visitor = await context.page(false);
    await context.open(visitor, "/appeal");
    await visitor.getByRole("textbox", { name: context.t("appeals.punishmentId") }).fill(punishmentId);
    await visitor.getByRole("button", { name: context.t("appeals.checkStatus") }).click();
    await visitor.locator("textarea").first().fill("Flow: I was banned by mistake.");
    await visitor.getByRole("button", { name: context.t("appeals.submitAppeal") }).click();
    await visitor.waitForURL((url) => url.pathname.startsWith("/ticket/"));
    const appealPath = new URL(visitor.url()).pathname;
    const appealId = decodeURIComponent(appealPath.slice("/ticket/".length));
    const staff = await context.page(true);
    await context.open(staff, `/panel/tickets/${encodeURIComponent(appealId)}`);
    const answer = "Flow: we reviewed the appeal.";
    await staff.getByRole("textbox", { name: context.t("ticket.replyLabel") }).fill(answer);
    await staff.getByRole("button", { name: context.t("ticket.send") }).click();
    await eventually(async () => (await context.api(`/v1/panel/tickets/${encodeURIComponent(appealId)}`)).text.includes(answer), "the staff answer is not on the appeal");
    await visitor.reload();
    await visible(visitor.getByText(answer));
  },
};

const INVITE: Flow = {
  name: "invite a staff member who accepts the link",
  async run(context) {
    const email = "moderator@modl.test";
    const page = await context.page(true);
    await context.open(page, "/panel/settings/members");
    await page.getByRole("button", { name: context.t("settings.staff.invite") }).click();
    const dialog = page.getByRole("dialog").last();
    await dialog.getByRole("textbox", { name: context.t("auth.email") }).fill(email);
    await dialog.getByRole("combobox", { name: context.t("settings.staff.role") }).click();
    await page.getByRole("option", { name: "Moderator", exact: true }).click();
    await dialog.getByRole("button", { name: context.t("settings.staff.createInvitation") }).click();
    const link = await page.getByRole("textbox", { name: email }).inputValue();
    expect(link.includes("/accept-invitation?token="), `the invitation link looks wrong: ${link.slice(0, 60)}`);
    const invitee = await context.page(false);
    await invitee.goto(link);
    await invitee.waitForURL((url) => url.pathname.startsWith("/panel") && !url.pathname.startsWith("/panel/auth"), { timeout: 30_000 });
    const role = await invitee.evaluate(`fetch("/v1/panel/auth/me", { headers: { "X-Modl-Session-Key": localStorage.getItem("modl.sessionKey") || "" } }).then((response) => response.json()).then((profile) => profile.role)`);
    expect(role === "Moderator", `the invitee signed in as ${String(role)}`);
  },
};

const MAIL_REFUSED: Flow = {
  name: "settings/email: an unreachable mail server is refused with a message",
  async run(context) {
    const page = await context.page(true);
    await context.open(page, "/panel/settings/email");
    await page.getByRole("textbox", { name: context.t("settings.mail.host"), exact: true }).fill("smtp.modl.test");
    await page.getByRole("textbox", { name: context.t("settings.mail.from"), exact: true }).fill("panel@modl.test");
    await page.getByRole("button", { name: context.t("settings.saveBar.save"), exact: true }).click();
    await visible(page.getByText(context.t("settings.mail.failureConnect")));
    await visible(page.getByText(context.t("settings.saveBar.unsaved")));
  },
};

const DRAFT_ACROSS_LAYOUTS: Flow = {
  name: "settings/profile: an unsaved draft survives the switch to the mobile layout",
  async run(context) {
    const page = await context.page(true);
    await context.open(page, "/panel/settings/profile");
    const field = page.getByRole("textbox", { name: context.t("settings.panelDisplayName"), exact: true }).first();
    await field.fill("Draft Admin");
    await visible(page.getByText(context.t("settings.saveBar.unsaved")));
    await page.setViewportSize(MOBILE);
    await visible(page.locator('a[href="/panel/settings"]', { hasText: context.t("settings.page.title") }));
    expect((await field.inputValue()) === "Draft Admin", `the draft reads "${await field.inputValue()}" after the layout switch`);
    await visible(page.getByText(context.t("settings.saveBar.unsaved")));
  },
};

type SettingsEdit = { page: string; role: "textbox" | "spinbutton"; label: string; value: string } | { page: string; role: "switch"; label: string };

const SETTINGS_EDITS: readonly SettingsEdit[] = [
  { page: "profile", role: "textbox", label: "settings.panelDisplayName", value: "Flow Admin" },
  { page: "webhooks", role: "textbox", label: "settings.webhook.botName", value: "modl flow" },
  { page: "player-addresses", role: "spinbutton", label: "settings.playerAddresses.sharedLimitLabel", value: "12" },
  { page: "replays", role: "switch", label: "settings.replays.enabled" },
  { page: "forms", role: "switch", label: "settings.tickets.requireEmailForCreation" },
  { page: "ai-moderation", role: "textbox", label: "settings.ai.limits.callsPerHour", value: "50" },
  { page: "homepage", role: "textbox", label: "settings.homepage.tagline", value: "Flow tagline" },
  { page: "theme", role: "textbox", label: "settings.theme.primaryColor", value: "#1e88e5" },
  { page: "custom-css", role: "textbox", label: "settings.pages.customCss", value: ".flow{color:red}" },
  { page: "storage", role: "spinbutton", label: "settings.storage.limits.maxUploadMb.label", value: "90" },
  { page: "data-retention", role: "spinbutton", label: "settings.dataRetention.auditLog", value: "400" },
  { page: "updates", role: "switch", label: "settings.updates.prereleases" },
];

function settingsFlow(edit: SettingsEdit): Flow {
  return {
    name: `settings/${edit.page}: ${edit.label} saves and survives a reload`,
    async run(context) {
      const name = context.t(edit.label);
      const page = await context.page(true);
      await context.open(page, `/panel/settings/${edit.page}`);
      const field = page.getByRole(edit.role, { name, exact: true }).first();
      await field.waitFor();
      const before = edit.role === "switch" ? await field.getAttribute("aria-checked") : null;
      if (edit.role === "switch") await field.click();
      else await field.fill(edit.value);
      await saveSettings(page, context);
      await page.reload();
      await field.waitFor();
      if (edit.role === "switch") expect((await field.getAttribute("aria-checked")) !== before, `${name} did not stay changed`);
      else expect((await field.inputValue()) === edit.value, `${name} reads "${await field.inputValue()}" after a reload`);
    },
  };
}

export const FLOWS: readonly Flow[] = [SIGN_IN, PLAYER_WINDOW, PUNISH, PUNISH_WITHOUT_REASON, TICKET_REPLY, ACCEPT_REPORT, APPEAL, INVITE, MAIL_REFUSED, DRAFT_ACROSS_LAYOUTS, ...SETTINGS_EDITS.map(settingsFlow)];

export interface FlowResult {
  name: string;
  ok: boolean;
  seconds: number;
  error?: string;
  screenshot?: string;
}

function fileSafe(name: string): string {
  return name.toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "").slice(0, 60);
}

export async function runFlows(backend: ShotsBackend, owner: SessionCredentials, outDir: string, only: string | null, logger: Logger): Promise<FlowResult[]> {
  const browser = await ShotBrowser.open(backend);
  const results: FlowResult[] = [];
  try {
    for (const flow of FLOWS.filter((candidate) => only === null || candidate.name.includes(only))) {
      const context = new FlowContext(backend, browser, owner);
      const started = Date.now();
      try {
        await flow.run(context);
        results.push({ name: flow.name, ok: true, seconds: Math.round((Date.now() - started) / 1000) });
        logger.info(`passed: ${flow.name}`);
      } catch (error) {
        const screenshot = join(outDir, `${fileSafe(flow.name)}.png`);
        const page = context.pages.at(-1);
        if (page) await page.screenshot({ path: screenshot }).catch(() => undefined);
        results.push({ name: flow.name, ok: false, seconds: Math.round((Date.now() - started) / 1000), error: errorMessage(error).split("\n")[0] ?? "", ...(page ? { screenshot } : {}) });
        logger.warn(`FAILED: ${flow.name}: ${errorMessage(error).split("\n")[0] ?? ""}`);
      } finally {
        await Promise.all(context.pages.map(async (page) => page.context().close()));
      }
    }
  } finally {
    await browser.close();
  }
  writeJson(join(outDir, "flows.json"), results);
  writeText(
    join(outDir, "flows.md"),
    [`# Panel flows`, "", ...results.map((result) => `- ${result.ok ? "passed" : "**FAILED**"} ${result.name} (${String(result.seconds)} s)${result.error ? `: ${result.error}` : ""}${result.screenshot ? ` ([screenshot](${result.screenshot.slice(outDir.length + 1)}))` : ""}`), ""].join("\n"),
  );
  return results;
}
