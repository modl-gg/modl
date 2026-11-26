import { BotPool, entryTarget } from "../../lib/bots/pool";
import { REPORT_TICKET_ID } from "../../lib/contract";
import { escapeRegExp, sleep } from "../../lib/util";
import type { VideoContext, VideoDefinition } from "../definition";
import { RealClient, standingAt, viewpoint, type Filmed } from "../footage/real-client";
import type { StepLabel } from "../labels";
import type { Segment } from "../studio/segment";
import type { Take } from "../studio/take";
import { centreOf, dismissNoticeIfShown, openRenderedPage } from "./panel-navigation";
import { chooseInMenu, planReport, type ReportPlan } from "./report-menus";
import { replayControl, waitForReplay } from "./replay-world";
import { claimOffCamera, harnessOwner, LOOPBACK_PANEL_URL, publishLoopbackPanel, recordEveryPlayer } from "./stage";

const TARGET = "Steve";
const HELPER = "Alex";
const PANEL_ENTRY = { x: 640, y: 400 };
const WANDER_MS = 60_000;
const RECORDED_BEFORE_REPORT_MS = 12_000;
const TICKET_TIMEOUT_MS = 20_000;
const FIRST_READ_MS = 1100;
const READ_MS = 800;
const PUNISHMENT = "Cheating";
const SEVERITY = "Regular";

const LABELS = {
  report: { page: "Commands", text: "In game: /report <player>" },
  open: { page: "Commands", text: "Panel > Tickets: open the new report" },
  replay: { page: "Commands", text: "View replay opens the replay" },
  close: { page: "Commands", text: "Esc closes it" },
  accept: { page: "Commands", text: "Reply as Accept" },
  punish: { page: "Commands", text: "apply the punishment" },
  closed: { page: "Commands", text: "Applying it closes the ticket" },
} as const satisfies Record<string, StepLabel>;

const SPEECH = {
  intro: "Hey guys, welcome to the reports tutorial for modl. Let's follow one report from the game all the way to a punishment.",
  report: "Players report rule breakers with /report. Here, we report Steve for cheating.",
  replay: "With replays on, cheating reports can capture a replay of the reported player. Then skip the details and confirm.",
  ticket: "We get a ticket ID, and online staff are alerted.",
  list: "In the panel, the report is waiting in Tickets.",
  detail: "The ticket shows who reported whom, with the replay attached.",
  viewer: "View replay opens it right over the ticket, following the player from behind.",
  firstPerson: "Switch to first person to see exactly what they saw, crosshair included.",
  free: "Or fly around freely with W, A, S, and D.",
  close: "Escape closes the replay.",
  accept: "Back on the ticket, reply as Accept. This opens the punishment panel.",
  punish: "Choose Cheating, pick a severity, and apply it.",
  closed: "Applying it closes the ticket, sends the reply, and bans Steve.",
} as const;

const FREE_FLIGHT_MS = 1400;
const ESCAPES = 3;

async function recordGame(context: VideoContext, game: RealClient, plan: ReportPlan): Promise<Filmed<string>> {
  return game.film("report-game", async (take) => {
    const narrator = take.narration();
    await take.say(context.speech(SPEECH.intro));
    await take.untilSilent();
    await narrator.label(context.text(LABELS.report));
    await take.say(context.speech(SPEECH.report));
    const [first, ...rest] = plan.steps;
    if (!first) throw new Error("the report menus were not planned");
    const since = await game.runCommandIntoMenu(narrator, plan.command, first.rows);
    await chooseInMenu(game, narrator, first, rest[0], FIRST_READ_MS);
    await take.say(context.speech(SPEECH.replay));
    for (const [index, step] of rest.entries()) await chooseInMenu(game, narrator, step, rest[index + 1], READ_MS);
    const reply = await game.client.log.waitForChat(REPORT_TICKET_ID, since, TICKET_TIMEOUT_MS);
    await take.say(context.speech(SPEECH.ticket));
    await narrator.hold(600);
    await narrator.settleLabel();
    const ticketId = reply.match[1];
    if (ticketId === undefined) throw new Error(`no ticket id in "${reply.text}"`);
    return ticketId;
  });
}

async function openTicket(context: VideoContext, take: Take, origin: string, ticketId: string): Promise<void> {
  const page = take.page;
  const row = page.getByText(new RegExp(`#?${escapeRegExp(ticketId)}\\b`)).first();
  await openRenderedPage(page, `${origin}/panel/tickets`, row);
  await dismissNoticeIfShown(page);
  const pointer = take.pointer(PANEL_ENTRY);
  await take.start();
  await pointer.appear();
  await pointer.label(context.text(LABELS.open));
  await take.say(context.speech(SPEECH.list));
  await pointer.click(row);
  const view = page.getByRole("button", { name: "View replay", exact: true });
  await view.waitFor({ timeout: 20_000 });
  await take.say(context.speech(SPEECH.detail));
  await pointer.moveTo(await centreOf(view));
  await take.untilSilent();
  await pointer.label(context.text(LABELS.replay));
  await pointer.click(view);
  await waitForReplay(page);
  await take.say(context.speech(SPEECH.viewer));
  await pointer.click(replayControl(page, "Play"));
  await take.untilSilent();
  await pointer.hold(1200);
  await take.say(context.speech(SPEECH.firstPerson));
  await pointer.click(replayControl(page, "Follow camera"));
  await take.untilSilent();
  await pointer.hold(1500);
  await take.say(context.speech(SPEECH.free));
  await pointer.click(replayControl(page, "First person"));
  await pointer.hide();
  await page.keyboard.down("w");
  await pointer.hold(FREE_FLIGHT_MS);
  await page.keyboard.up("w");
  await take.untilSilent();
  await pointer.label(context.text(LABELS.close));
  await take.say(context.speech(SPEECH.close));
  for (let attempt = 0; attempt < ESCAPES && (await page.getByRole("dialog").count()) > 0; attempt += 1) {
    await page.keyboard.press("Escape");
    await pointer.hold(500);
  }
  await page.getByRole("dialog").waitFor({ state: "detached", timeout: 10_000 });
  await pointer.settleLabel();
}

async function punishFromTicket(context: VideoContext, take: Take): Promise<void> {
  const page = take.page;
  const pointer = take.pointer(PANEL_ENTRY);
  await pointer.appear();
  await pointer.label(context.text(LABELS.accept));
  await take.say(context.speech(SPEECH.accept));
  await pointer.click(page.getByRole("combobox", { name: "Reply as" }));
  await pointer.click(page.getByRole("option", { name: "Accept", exact: true }));
  await page.getByText(/^Punish /).first().waitFor({ timeout: 15_000 });
  await pointer.label(context.text(LABELS.punish));
  await take.say(context.speech(SPEECH.punish));
  await pointer.click(page.getByRole("button", { name: PUNISHMENT, exact: true }));
  await pointer.click(page.getByRole("tab", { name: SEVERITY, exact: true }));
  const apply = page.getByRole("button", { name: /^Apply:/ });
  await apply.waitFor({ timeout: 15_000 });
  await pointer.click(apply);
  await page.getByText("Punishment applied", { exact: true }).first().waitFor({ timeout: 20_000 });
  await pointer.hold(900);
  await page.getByText("Report Accepted").first().scrollIntoViewIfNeeded();
  await pointer.label(context.text(LABELS.closed));
  await take.say(context.speech(SPEECH.closed));
  await pointer.hold(1800);
  await pointer.hide();
  await pointer.settleLabel();
}

async function record(context: VideoContext): Promise<Segment[]> {
  const environment = context.environment;
  const game = await RealClient.attach(context);
  const origin = publishLoopbackPanel(context);
  const browser = await context.studio.panelBrowser();
  await claimOffCamera(context, browser, origin, { replays: "every player", owner: harnessOwner(environment), panelUrl: LOOPBACK_PANEL_URL });
  await recordEveryPlayer(environment, environment.state.coreServer);
  await game.waitUntilOnline(environment, context.logger);
  const target = entryTarget(environment.state);
  const bots = new BotPool(() => target, context.logger);
  try {
    const steve = await bots.join(TARGET);
    const alex = await bots.join(HELPER);
    await game.stageWorld(environment, [viewpoint(game.username), standingAt(TARGET, 8.5, 180), standingAt(HELPER, -10.5, 0)]);
    const wanderStarted = Date.now();
    const [, filmed] = await Promise.all([
      steve.wander(WANDER_MS),
      (async () => {
        const plan = await planReport(alex, TARGET);
        await sleep(Math.max(0, wanderStarted + RECORDED_BEFORE_REPORT_MS - Date.now()));
        return recordGame(context, game, plan);
      })(),
    ]);

    const ticket = await browser.take("ticket");
    await openTicket(context, ticket, origin, filmed.value);
    await punishFromTicket(context, ticket);
    const panel = await ticket.stop();
    await ticket.page.close();
    return [filmed.segment, panel];
  } finally {
    bots.quitAll();
  }
}

export const reportToReplay: VideoDefinition = {
  id: "report-to-replay",
  title: "From report to punishment",
  wikiPage: "Commands",
  topology: "standalone",
  realClient: true,
  labels: Object.values(LABELS),
  presentation: "narrated",
  narration: Object.values(SPEECH),
  record,
};
