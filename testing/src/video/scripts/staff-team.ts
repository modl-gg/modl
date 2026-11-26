import type { Locator, Page } from "playwright-core";
import { TotpDevice } from "../../lib/totp";
import type { VideoContext, VideoDefinition } from "../definition";
import type { StepLabel } from "../labels";
import type { CinematicPointer } from "../studio/pointer";
import type { Segment } from "../studio/segment";
import type { PanelBrowser } from "../studio/studio";
import { centreOf, openRenderedSettings } from "./panel-navigation";
import { claimOffCamera, LOOPBACK_PANEL_URL, OWNER, publishLoopbackPanel } from "./stage";
import { VirtualAuthenticator } from "./virtual-authenticator";

const PANEL_ENTRY = { x: 640, y: 400 };
const STEP_TIMEOUT_MS = 20_000;
const INVITEE = "ava@example.net";
const ROLE = "Moderator";
const PASSKEY_NAME = "Laptop";

const LABELS = {
  invite: { page: "Permissions", text: "Settings > Staff > Members > Invite staff" },
  roles: { page: "Permissions", text: "Settings > Staff > Roles" },
  security: { page: "Permissions", text: "Settings > Account > Security" },
  recovery: { page: "Permissions", text: "save the recovery codes" },
  passkey: { page: "Permissions", text: "Add passkey" },
  require: { page: "Permissions", text: "Require two-factor authentication for all staff" },
  signIn: { page: "Permissions", text: "Sign in with a passkey" },
} as const satisfies Record<string, StepLabel>;

const SPEECH = {
  intro: "Hey guys, welcome to the staff tutorial for modl. Let's bring your team in and keep their accounts safe.",
  invite: "Invite staff from Settings, Staff, Members. Enter their email and pick a role.",
  link: "Without email delivery, you get an invitation link to send them. It lasts 24 hours.",
  roles: "Roles decide what each member may do, in the panel and in game.",
  security: "Protect your own account first. Under Account, Security, set up an authenticator app.",
  recovery: "Save the recovery codes somewhere safe. Each code works once.",
  passkey: "A passkey signs you in without a password or code.",
  require: "Then, under Sign-in, require two-factor authentication for all staff. Staff without it set it up at their next sign-in.",
  signIn: "Next time, sign in with your passkey.",
} as const;

async function confirmIdentityIfAsked(page: Page, pointer: CinematicPointer, next: Locator): Promise<void> {
  const proof = page.getByRole("dialog").filter({ hasText: "Confirm it is you" });
  await Promise.race([proof.waitFor({ timeout: STEP_TIMEOUT_MS }), next.waitFor({ timeout: STEP_TIMEOUT_MS })]);
  if (!(await proof.isVisible())) return;
  await pointer.type(proof.getByRole("textbox", { name: "Current password" }), OWNER.password);
  await pointer.click(proof.getByRole("button", { name: "Continue", exact: true }));
  await next.waitFor({ timeout: STEP_TIMEOUT_MS });
}

async function recordInvite(context: VideoContext, browser: PanelBrowser, origin: string): Promise<Segment> {
  const take = await browser.take("invite");
  const page = take.page;
  await openRenderedSettings(page, origin, "members", "Members");
  const pointer = take.pointer(PANEL_ENTRY);
  await take.start();
  await pointer.appear();
  await take.say(context.speech(SPEECH.intro));
  await take.untilSilent();
  await pointer.label(context.text(LABELS.invite));
  await take.say(context.speech(SPEECH.invite));
  await pointer.click(page.getByRole("button", { name: "Invite staff", exact: true }));
  const dialog = page.getByRole("dialog");
  await pointer.type(dialog.getByRole("textbox", { name: "Email" }), INVITEE);
  await pointer.click(dialog.getByRole("combobox", { name: "Role" }));
  await pointer.click(page.getByRole("option", { name: ROLE, exact: true }));
  await pointer.click(dialog.getByRole("button", { name: /^(Create invitation|Send invitation)$/ }));
  const links = page.getByRole("dialog").filter({ hasText: "Invitation links" });
  await links.waitFor({ timeout: STEP_TIMEOUT_MS });
  await take.say(context.speech(SPEECH.link));
  await pointer.moveTo(await centreOf(links.getByRole("button", { name: "Copy invitation link" })));
  await take.untilSilent();
  await pointer.click(links.getByRole("button", { name: "Close", exact: true }).first());
  await page.getByText("Invitation pending").first().waitFor({ timeout: STEP_TIMEOUT_MS });
  await pointer.hold(900);
  await pointer.settleLabel();
  const segment = await take.stop();
  await page.close();
  return segment;
}

async function recordRoles(context: VideoContext, browser: PanelBrowser, origin: string): Promise<Segment> {
  const take = await browser.take("roles");
  const page = take.page;
  await openRenderedSettings(page, origin, "roles", "Roles");
  const pointer = take.pointer(PANEL_ENTRY);
  await take.start();
  await pointer.appear();
  await pointer.label(context.text(LABELS.roles));
  await take.say(context.speech(SPEECH.roles));
  const card = page.locator("div").filter({ has: page.getByText(ROLE, { exact: true }) }).filter({ has: page.getByRole("button", { name: "Edit role" }) }).last();
  await pointer.click(card.getByRole("button", { name: "Edit role" }));
  const editor = page.getByRole("dialog");
  await editor.getByText("Permissions").first().waitFor({ timeout: STEP_TIMEOUT_MS });
  await pointer.moveTo(await centreOf(editor.getByRole("checkbox", { name: "View All Tickets" })));
  await pointer.hold(900);
  await pointer.moveTo(await centreOf(editor.getByRole("checkbox", { name: "Reply to All Tickets" })));
  await take.untilSilent();
  await pointer.click(editor.getByRole("button", { name: "Cancel", exact: true }));
  await pointer.hold(500);
  await pointer.settleLabel();
  const segment = await take.stop();
  await page.close();
  return segment;
}

async function recordSecurity(context: VideoContext, browser: PanelBrowser, origin: string): Promise<{ segment: Segment; page: Page; authenticator: VirtualAuthenticator }> {
  const take = await browser.take("security");
  const page = take.page;
  const authenticator = await VirtualAuthenticator.attach(page);
  await openRenderedSettings(page, origin, "security", "Security");
  const pointer = take.pointer(PANEL_ENTRY);
  await take.start();
  await pointer.appear();
  await pointer.label(context.text(LABELS.security));
  await take.say(context.speech(SPEECH.security));
  await pointer.click(page.getByRole("button", { name: "Set up", exact: true }));
  const setupKey = page.getByLabel("Setup key", { exact: true });
  await confirmIdentityIfAsked(page, pointer, setupKey);
  const device = new TotpDevice((await setupKey.innerText()).replace(/\s+/g, ""));
  await pointer.type(page.getByRole("textbox", { name: "Enter the 6-digit code" }), await device.nextCode());
  await pointer.click(page.getByRole("button", { name: "Continue", exact: true }));
  const saved = page.getByRole("button", { name: "I saved these codes", exact: true });
  await saved.waitFor({ timeout: STEP_TIMEOUT_MS });
  await pointer.label(context.text(LABELS.recovery));
  await take.say(context.speech(SPEECH.recovery));
  await take.untilSilent();
  await pointer.click(saved);

  await pointer.label(context.text(LABELS.passkey));
  await take.say(context.speech(SPEECH.passkey));
  await pointer.click(page.getByRole("button", { name: "Add passkey", exact: true }));
  const naming = page.getByRole("dialog").filter({ hasText: "Name your passkey" });
  await confirmIdentityIfAsked(page, pointer, naming);
  await pointer.replace(naming.getByRole("textbox", { name: "Name your passkey" }), PASSKEY_NAME);
  await pointer.click(naming.getByRole("button", { name: "Save", exact: true }));
  await page.getByText(PASSKEY_NAME, { exact: true }).first().waitFor({ timeout: STEP_TIMEOUT_MS });
  await authenticator.remember();
  await pointer.hold(1200);
  await pointer.settleLabel();
  const segment = await take.stop();
  return { segment, page, authenticator };
}

async function recordRequirement(context: VideoContext, browser: PanelBrowser, page: Page, origin: string): Promise<Segment> {
  await openRenderedSettings(page, origin, "sign-in", "Sign-in");
  const take = browser.retake(page, "require");
  const pointer = take.pointer(PANEL_ENTRY);
  await take.start();
  await pointer.appear();
  await pointer.label(context.text(LABELS.require));
  await take.say(context.speech(SPEECH.require));
  await pointer.click(page.getByRole("switch", { name: "Require two-factor authentication for all staff" }));
  await pointer.click(page.getByRole("button", { name: "Save", exact: true }));
  await page.getByRole("status").filter({ hasText: "Saved" }).waitFor({ timeout: STEP_TIMEOUT_MS });
  await take.untilSilent();
  await pointer.hold(700);
  await pointer.settleLabel();
  return take.stop();
}

async function recordPasskeySignIn(context: VideoContext, browser: PanelBrowser, page: Page, origin: string, authenticator: VirtualAuthenticator): Promise<Segment> {
  await page.context().clearCookies();
  await page.goto(`${origin}/panel/auth`);
  await authenticator.restore(page);
  const passkey = page.getByRole("button", { name: "Sign in with a passkey", exact: true });
  await passkey.waitFor({ timeout: STEP_TIMEOUT_MS });
  const take = browser.retake(page, "passkey-sign-in");
  const pointer = take.pointer(PANEL_ENTRY);
  await take.start();
  await pointer.appear();
  await pointer.label(context.text(LABELS.signIn));
  await take.say(context.speech(SPEECH.signIn));
  await pointer.click(passkey);
  await page.getByRole("heading", { name: "Dashboard" }).waitFor({ timeout: STEP_TIMEOUT_MS });
  await pointer.hide();
  await pointer.hold(1600);
  await pointer.settleLabel();
  const segment = await take.stop();
  await page.close();
  return segment;
}

async function record(context: VideoContext): Promise<Segment[]> {
  const origin = publishLoopbackPanel(context);
  const browser = await context.studio.panelBrowser();
  await claimOffCamera(context, browser, origin, { panelUrl: LOOPBACK_PANEL_URL });
  const invite = await recordInvite(context, browser, origin);
  const roles = await recordRoles(context, browser, origin);
  const security = await recordSecurity(context, browser, origin);
  const requirement = await recordRequirement(context, browser, security.page, origin);
  const signIn = await recordPasskeySignIn(context, browser, security.page, origin, security.authenticator);
  return [invite, roles, security.segment, requirement, signIn];
}

export const staffTeam: VideoDefinition = {
  id: "staff-team",
  title: "Set up your staff team",
  wikiPage: "Permissions",
  topology: "standalone",
  labels: Object.values(LABELS),
  presentation: "narrated",
  narration: Object.values(SPEECH),
  record,
};
