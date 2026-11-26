import type { Page } from "playwright-core";
import { ACCOUNT_LINKED, LOGIN_LINK_TEXT, LOGIN_LINK_URL, PlayerCommands } from "../../lib/contract";
import type { VideoContext, VideoDefinition } from "../definition";
import { RealClient, viewpoint } from "../footage/real-client";
import type { StepLabel } from "../labels";
import type { Segment } from "../studio/segment";
import { dismissForwardingNotice, openPanelHome, openSettingsPage, revealBottomOf } from "./panel-navigation";
import { claimOffCamera, publishPanel } from "./stage";

const LINK_COMMAND_CODE = /\/modl link ([A-Z0-9]+)/;
const PANEL_ENTRY = { x: 640, y: 420 };
const REPLY_TIMEOUT_MS = 10_000;

const LABELS = {
  link: { page: "First-Run", text: "Panel > Settings > Account > Profile > Link account" },
  run: { page: "First-Run", text: "Run /modl link <code> in game" },
  panel: { page: "First-Run", text: "/modl panel" },
  signIn: { page: "First-Run", text: "one-time sign-in link" },
} as const satisfies Record<string, StepLabel>;

const SPEECH = {
  intro: "Hey, welcome back. In this short one, we link your Minecraft account to the panel.",
  link: "In-game staff tools need a linked account. In the panel, open Settings, then Profile, and choose Link account.",
  code: "The panel shows a code, valid for 10 minutes.",
  run: "In game, run /modl link with that code.",
  linked: "The panel now shows your linked Minecraft account.",
  panel: "Later, /modl panel gives you a one-time sign-in link, valid for 2 minutes.",
  signIn: "Open the link and confirm, and you are signed in to the panel.",
} as const;

function loginLinkOn(copied: string, origin: string): string {
  const url = LOGIN_LINK_URL.exec(copied)?.[0];
  if (url === undefined) throw new Error("the copied chat link is not a panel sign-in link");
  if (new URL(url).origin !== new URL(origin).origin) throw new Error(`the copied sign-in link points at ${new URL(url).origin}, not this panel`);
  return url;
}

async function linkCodeFrom(page: Page): Promise<string> {
  const command = page.getByText(LINK_COMMAND_CODE);
  await command.waitFor({ timeout: 15_000 });
  const code = LINK_COMMAND_CODE.exec(await command.innerText())?.[1];
  if (code === undefined) throw new Error("the panel shows no /modl link code");
  return code;
}

async function record(context: VideoContext): Promise<Segment[]> {
  const environment = context.environment;
  const game = await RealClient.attach(context);
  const origin = await publishPanel(context);
  const browser = await context.studio.panelBrowser();
  await claimOffCamera(context, browser, origin);
  await game.waitUntilOnline(environment, context.logger);
  await game.stageWorld(environment, [viewpoint(game.username)]);

  const panel = await browser.take("link-panel");
  await openPanelHome(panel.page, origin);
  await dismissForwardingNotice(panel.page);
  const panelPointer = panel.pointer(PANEL_ENTRY);
  await panel.start();
  await panelPointer.appear();
  await panel.say(context.speech(SPEECH.intro));
  await panel.untilSilent();
  await panelPointer.label(context.text(LABELS.link));
  await panel.say(context.speech(SPEECH.link));
  await openSettingsPage(panel.page, panelPointer, "Profile");
  await panelPointer.click(panel.page.getByRole("button", { name: "Link account", exact: true }));
  const code = await linkCodeFrom(panel.page);
  await panel.say(context.speech(SPEECH.code));
  await panelPointer.hold(1400);
  await panelPointer.settleLabel();
  const linkPanel = await panel.stop();

  const linkGame = await game.film("link-game", async (take) => {
    const narrator = take.narration();
    await narrator.label(context.text(LABELS.run));
    await take.say(context.speech(SPEECH.run));
    await game.runCommand(narrator, PlayerCommands.link(code), ACCOUNT_LINKED, REPLY_TIMEOUT_MS);
    await narrator.hold(1600);
    await narrator.settleLabel();
  });

  await panel.page.getByText(game.username, { exact: true }).waitFor({ timeout: 15_000 });
  await panelPointer.label(null);
  await panelPointer.hide();
  await revealBottomOf(panel.page.getByRole("button", { name: "Sign out", exact: true }));
  const linkedTake = browser.retake(panel.page, "linked");
  await linkedTake.start();
  await linkedTake.say(context.speech(SPEECH.linked));
  await panelPointer.hold(1200);
  const linked = await linkedTake.stop();
  await panel.page.close();

  const panelGame = await game.film("panel-game", async (take) => {
    const narrator = take.narration();
    await narrator.label(context.text(LABELS.panel));
    await take.say(context.speech(SPEECH.panel));
    const run = await game.runCommand(narrator, PlayerCommands.panelLogin, LOGIN_LINK_TEXT, REPLY_TIMEOUT_MS);
    await take.untilSilent();
    await narrator.label(context.text(LABELS.signIn));
    const copied = await game.copyNewestChatLink(narrator, LOGIN_LINK_TEXT, run.since);
    await narrator.hold(900);
    return loginLinkOn(copied, origin);
  });

  const signedIn = await (await context.studio.panelBrowser()).take("signed-in");
  await signedIn.page.goto(panelGame.value);
  const confirm = signedIn.page.getByRole("button", { name: "Sign in", exact: true });
  await confirm.waitFor({ timeout: 20_000 });
  const signedInPointer = signedIn.pointer(PANEL_ENTRY);
  await signedIn.start();
  await signedInPointer.appear();
  await signedInPointer.label(context.text(LABELS.signIn));
  await signedIn.say(context.speech(SPEECH.signIn));
  await signedInPointer.hold(900);
  await signedInPointer.click(confirm);
  await signedIn.page.getByRole("heading", { name: "Dashboard" }).waitFor({ timeout: 20_000 });
  await signedInPointer.hide();
  await signedInPointer.hold(2000);
  await signedInPointer.settleLabel();
  const signedInPanel = await signedIn.stop();
  return [linkPanel, linkGame.segment, linked, panelGame.segment, signedInPanel];
}

export const linkAccount: VideoDefinition = {
  id: "link-account",
  title: "Link your Minecraft account",
  wikiPage: "First-Run",
  topology: "standalone",
  realClient: true,
  labels: Object.values(LABELS),
  presentation: "narrated",
  narration: Object.values(SPEECH),
  record,
};
