import { ConsoleCommands, ImportMessages } from "../../lib/contract";
import type { Environment } from "../../lib/environment";
import { installLiteBansFixture, removeLiteBansFixture } from "../../lib/litebans-fixture";
import { offlineUuid } from "../../lib/util";
import type { VideoContext, VideoDefinition } from "../definition";
import type { StepLabel } from "../labels";
import { consoleLine, consoleScene, type ConsoleLine } from "../scenes/console";
import { indexes, recordReveal, type RevealBeat } from "../scenes/play";
import type { Segment } from "../studio/segment";
import { centreOf, openRenderedPage } from "./panel-navigation";
import { claimOffCamera, publishPanel } from "./stage";

const SOURCE = "litebans";
const IMPORT_TIMEOUT_MS = 300_000;
const REPLY_TIMEOUT_MS = 60_000;
const PANEL_ENTRY = { x: 640, y: 420 };
const NOISE = /joined the game|left the game|UUID of player|logged in with entity|lost connection/;
const DAY_MS = 86_400_000;

const LABELS = {
  list: { page: "Migrating-from-Other-Plugins", text: "List the sources: modl import" },
  preview: { page: "Migrating-from-Other-Plugins", text: "Preview: modl import <source>" },
  confirm: { page: "Migrating-from-Other-Plugins", text: "Confirm within 60 seconds" },
  panel: { page: "Migrating-from-Other-Plugins", text: "Check the panel" },
} as const satisfies Record<string, StepLabel>;

const SPEECH = {
  intro: "Hi everyone, welcome to the import tutorial for modl. Today we bring a LiteBans history over.",
  list: "Run the import in the console of the server that has the old plugin's data. modl import lists the sources it found here.",
  preview: "modl import LiteBans shows what LiteBans holds, and imports nothing yet.",
  confirm: "Within 60 seconds, confirm with two choices: widen or skip for IP-wide and server-scoped punishments, then keep or standard for IP history.",
  running: "The import runs in the background. Bans and mutes take effect as they arrive.",
  done: "When the console says the import completed, open a player in the panel.",
  panel: "The imported punishments are there, with the original staff names, reasons, and dates.",
} as const;

interface DemoPlayer {
  name: string;
  address: string;
}

const PLAYERS: readonly DemoPlayer[] = [
  { name: "Steve", address: "203.0.113.24" },
  { name: "Alex", address: "198.51.100.77" },
];
const FEATURED = "Steve";
const OTHER = "Alex";
const FEATURED_REASON = "Fly hacking at spawn";

function sql(value: string): string {
  return `'${value.replaceAll("'", "''")}'`;
}

function demoSeed(now: number): string {
  const uuid = (name: string): string => sql(offlineUuid(name));
  const at = (days: number): number => now - days * DAY_MS;
  const columns = "(id, uuid, reason, banned_by_name, time, until, active, ipban, server_scope, removed_by_name, removed_by_date)";
  const statements = [
    ...PLAYERS.map((player, index) => `INSERT INTO litebans_history (id, uuid, name, ip, date) VALUES (${String(index + 1)}, ${uuid(player.name)}, ${sql(player.name)}, ${sql(player.address)}, ${String(at(120 - index * 9))})`),
    `INSERT INTO litebans_bans ${columns} VALUES (1, ${uuid(FEATURED)}, ${sql(FEATURED_REASON)}, 'Ava', ${String(at(12))}, -1, 1, 0, '*', NULL, NULL)`,
    `INSERT INTO litebans_bans ${columns} VALUES (2, ${uuid(OTHER)}, 'Griefing the market', 'Console', ${String(at(40))}, ${String(at(33))}, 0, 0, '*', '#expired', NULL)`,
    `INSERT INTO litebans_mutes ${columns} VALUES (1, ${uuid(OTHER)}, 'Spam in chat', 'Leo', ${String(at(20))}, ${String(at(19))}, 0, 0, '*', 'Leo', ${String(at(19))})`,
    `INSERT INTO litebans_mutes ${columns} VALUES (2, ${uuid(FEATURED)}, 'Insulting other players', 'Leo', ${String(at(30))}, ${String(at(29))}, 0, 0, '*', NULL, NULL)`,
    `INSERT INTO litebans_warnings ${columns} VALUES (1, ${uuid(FEATURED)}, 'Unfair advantage in PvP', 'Ava', ${String(at(45))}, -1, 1, 0, '*', NULL, NULL)`,
    `INSERT INTO litebans_warnings ${columns} VALUES (2, ${uuid(OTHER)}, 'Advertising another server', 'Leo', ${String(at(8))}, -1, 1, 0, '*', NULL, NULL)`,
    `INSERT INTO litebans_kicks (id, uuid, reason, banned_by_name, time, until, active, ipban, server_scope) VALUES (1, ${uuid(OTHER)}, 'AFK farming', 'Console', ${String(at(60))}, 0, 0, 0, '*')`,
  ];
  return `${statements.join("\n")}\n`;
}

function promptLine(command: string): ConsoleLine {
  return { meta: "", text: `> ${command}`, highlight: null, name: null };
}

async function consoleReply(environment: Environment, server: string, command: string, expect: RegExp): Promise<ConsoleLine[]> {
  const result = await environment.console(server).run(command, { expect, timeoutMs: REPLY_TIMEOUT_MS });
  return [promptLine(command), ...result.lines.filter((line) => !NOISE.test(line)).map((line) => consoleLine(line, { pattern: /./ }))];
}

async function playConsole(context: VideoContext, name: string, lines: readonly ConsoleLine[], beats: readonly RevealBeat[], shownAtStart: readonly number[] = []): Promise<Segment> {
  return recordReveal(await context.studio.scene(name, consoleScene(`${context.environment.state.coreServer} console`, lines)), beats, shownAtStart);
}

async function record(context: VideoContext): Promise<Segment[]> {
  const environment = context.environment;
  const core = environment.state.coreServer;
  const serverDir = environment.server(core).dataDir;
  const origin = await publishPanel(context);
  const browser = await context.studio.panelBrowser();
  await claimOffCamera(context, browser, origin);
  installLiteBansFixture(serverDir, demoSeed(Date.now()));
  await environment.restart(core);
  try {
    const sources = await consoleReply(environment, core, ConsoleCommands.importSources, ImportMessages.detected);
    const list = await playConsole(context, "list", sources, [
      { say: context.speech(SPEECH.intro), holdMs: 0 },
      { say: context.speech(SPEECH.list), label: context.text(LABELS.list), reveal: indexes(sources.length).slice(1), stepMs: 420, holdMs: 400 },
    ], [0]);

    const previewLines = await consoleReply(environment, core, ConsoleCommands.importSourcePreview(SOURCE), ImportMessages.previewReady);
    const confirmLines = await consoleReply(environment, core, ConsoleCommands.importSourceConfirm(SOURCE, "skip standard"), ImportMessages.started);
    const since = await environment.log(core).mark();
    await environment.log(core).waitFor(ImportMessages.completed, { since, timeoutMs: IMPORT_TIMEOUT_MS });
    const completed = (await environment.log(core).lines(since)).filter((line) => ImportMessages.completed.test(line.text)).map((line) => consoleLine(line.text, { pattern: /./ }));
    const preview = await playConsole(context, "preview", previewLines, [
      { say: context.speech(SPEECH.preview), label: context.text(LABELS.preview), reveal: indexes(previewLines.length), stepMs: 420, holdMs: 600 },
    ]);
    const runLines = [...confirmLines, ...completed];
    const run = await playConsole(context, "confirm", runLines, [
      { say: context.speech(SPEECH.confirm), label: context.text(LABELS.confirm), reveal: indexes(confirmLines.length), stepMs: 420, holdMs: 0 },
      { say: context.speech(SPEECH.running), holdMs: 0 },
      { say: context.speech(SPEECH.done), reveal: indexes(runLines.length).slice(confirmLines.length), focus: [runLines.length - 1], holdMs: 300 },
    ]);

    const take = await browser.take("player");
    await openRenderedPage(take.page, `${origin}/panel/player/${offlineUuid(FEATURED)}`, take.page.getByText(FEATURED_REASON).first());
    const pointer = take.pointer(PANEL_ENTRY);
    await take.start();
    await pointer.appear();
    await pointer.label(context.text(LABELS.panel));
    await take.say(context.speech(SPEECH.panel));
    await pointer.moveTo(await centreOf(take.page.getByText(FEATURED_REASON).first()));
    await pointer.hold(1800);
    await pointer.settleLabel();
    const panel = await take.stop();
    await take.page.close();
    return [list, preview, run, panel];
  } finally {
    removeLiteBansFixture(serverDir);
  }
}

export const importPlugins: VideoDefinition = {
  id: "import-plugins",
  title: "Import from LiteBans",
  wikiPage: "Migrating-from-Other-Plugins",
  topology: "standalone",
  labels: Object.values(LABELS),
  presentation: "narrated",
  narration: Object.values(SPEECH),
  record,
};
