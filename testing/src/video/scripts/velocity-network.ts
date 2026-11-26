import { readFileSync } from "node:fs";
import { join } from "node:path";
import { ConsolePatterns } from "../../lib/contract";
import { connectionFile, MODL_FOLDER } from "../../lib/topologies/server-files";
import { writeText } from "../../lib/util";
import type { VideoContext, VideoDefinition } from "../definition";
import type { StepLabel } from "../labels";
import { consoleScene, lineIndex, pickConsoleLines } from "../scenes/console";
import { filesScene } from "../scenes/files";
import { indexes, recordReveal } from "../scenes/play";
import { DirectHands } from "../studio/pointer";
import type { Segment } from "../studio/segment";
import type { PanelBrowser } from "../studio/studio";
import { centreOf, openRenderedSettings } from "./panel-navigation";
import { connectedServer, SetupWizard } from "../panel/setup-wizard";
import { BOOT_LINE, OWNER, PLATFORM_START, panelHostBoot, publishPanel, setupCode } from "./stage";

const MEMBER = "survival";
const SERVER_NAME_LINE = /^server-name:.*$/m;

const LABELS = {
  start: { page: "Proxy-Network", text: "Start the proxy" },
  firstRun: { page: "Proxy-Network", text: "finish First Run" },
  servers: { page: "Proxy-Network", text: "Settings > General > Minecraft servers" },
  create: { page: "Proxy-Network", text: "Create key on its row" },
  download: { page: "Proxy-Network", text: "Download connection.yml" },
  save: { page: "Proxy-Network", text: "save the file as plugins/modl/connection.yml" },
  serverName: { page: "Proxy-Network", text: "set server-name to the same name" },
  startServer: { page: "Proxy-Network", text: "Start the server" },
  connected: { page: "Proxy-Network", text: "Behind a proxy and Connected" },
} as const satisfies Record<string, StepLabel>;

const SPEECH = {
  intro: "Hi there, and welcome to the network tutorial for modl. Let's connect the servers behind your Velocity proxy.",
  startProxy: "Put modl in the proxy's plugins folder and start it. The proxy runs the panel.",
  setupCode: "On the first start, the console prints a one-time setup code.",
  panelPort: "The panel listens on port 8080, so open that port for it.",
  claim: "Open the panel address from the console, enter the setup code and your email, and finish the setup wizard.",
  servers: "In the panel, open Settings, General, Minecraft servers. While the proxy is connected, it lists every server from its config.",
  create: "Choose Create key on the row of a server. Here, that is survival.",
  download: "The key is shown only once, so download connection.yml right away.",
  save: "On that server, put modl and PacketEvents in the plugins folder, and save the file as plugins/modl/connection.yml.",
  explainFile: "It sets the mode, the panel address, and this server's key.",
  serverName: "In server-features.yml, set server-name to the same name.",
  startServer: "Now start the server.",
  connectedLine: "When the console says it is connected to the modl network, the link is working.",
  connected: "Back in the panel, the server shows as Behind a proxy and Connected. Repeat these steps for each backend server.",
} as const;

const FILE_LINES = { mode: 0, url: 3, apiKey: 4, serverName: 5 } as const;

const PANEL_ENTRY = { x: 640, y: 420 };
const CLAIM_ENTRY = { x: 900, y: 600 };
const LISTED_TIMEOUT_MS = 60_000;

interface CreatedKey {
  segment: Segment;
  connectionYml: string;
}

async function createServerKey(context: VideoContext, browser: PanelBrowser, origin: string, downloads: string): Promise<CreatedKey> {
  const take = await browser.take("create-key");
  const page = take.page;
  await openRenderedSettings(page, origin, "minecraft-servers", "Minecraft servers");
  const row = page.getByRole("listitem").filter({ has: page.getByText(MEMBER, { exact: true }) });
  const create = row.getByRole("button", { name: "Create key", exact: true });
  await create.waitFor({ timeout: LISTED_TIMEOUT_MS });
  const pointer = take.pointer(PANEL_ENTRY);
  await take.start();
  await pointer.appear();
  await pointer.label(context.text(LABELS.servers));
  await take.say(context.speech(SPEECH.servers));
  await pointer.moveTo(await centreOf(page.getByRole("link", { name: "Minecraft servers", exact: true })));
  await take.untilSilent();
  await pointer.label(context.text(LABELS.create));
  await take.say(context.speech(SPEECH.create));
  await pointer.moveTo(await centreOf(row.getByText(MEMBER, { exact: true })));
  await take.untilSilent();
  await pointer.click(create);
  const dialog = page.getByRole("dialog");
  const download = dialog.getByRole("button", { name: "Download connection.yml", exact: true });
  await download.waitFor({ timeout: 15_000 });
  await take.say(context.speech(SPEECH.download));
  await pointer.label(context.text(LABELS.download));
  const [file] = await Promise.all([page.waitForEvent("download"), pointer.click(download)]);
  const saved = join(downloads, "connection.yml");
  await file.saveAs(saved);
  await pointer.hold(1200);
  await pointer.settleLabel();
  const segment = await take.stop();
  await page.close();
  return { segment, connectionYml: readFileSync(saved, "utf8") };
}

async function installOnMember(context: VideoContext, connectionYml: string): Promise<string> {
  const environment = context.environment;
  const member = environment.server(MEMBER);
  await environment.stopGracefully(MEMBER);
  writeText(connectionFile(member.dataDir), connectionYml);
  const since = await environment.log(MEMBER).mark();
  await environment.start(MEMBER);
  return since;
}

function serverNameLine(memberDir: string): string {
  const line = SERVER_NAME_LINE.exec(readFileSync(join(memberDir, MODL_FOLDER, "server-features.yml"), "utf8"))?.[0];
  if (!line?.includes(MEMBER)) throw new Error(`server-features.yml of ${MEMBER} does not set server-name to ${MEMBER}`);
  return line;
}

async function showConnected(context: VideoContext, browser: PanelBrowser, origin: string): Promise<Segment> {
  const take = await browser.take("connected");
  const page = take.page;
  await openRenderedSettings(page, origin, "minecraft-servers", "Minecraft servers");
  const memberRow = connectedServer(page, MEMBER);
  await memberRow.waitFor({ timeout: 30_000 });
  const pointer = take.pointer(PANEL_ENTRY);
  await take.start();
  await pointer.appear();
  await take.say(context.speech(SPEECH.connected));
  await pointer.label(context.text(LABELS.connected));
  await pointer.moveTo(await centreOf(memberRow));
  await pointer.hold(2600);
  await pointer.settleLabel();
  const segment = await take.stop();
  await page.close();
  return segment;
}

async function recordClaim(context: VideoContext, browser: PanelBrowser, origin: string): Promise<Segment> {
  const take = await browser.take("claim");
  const pointer = take.pointer(CLAIM_ENTRY);
  const wizard = new SetupWizard(take.page, pointer, {
    narrator: pointer,
    speaker: take,
    cues: { code: { label: context.text(LABELS.firstRun), say: context.speech(SPEECH.claim) } },
  });
  await wizard.open(origin);
  await take.start();
  await pointer.appear();
  await wizard.claim(await setupCode(context.environment), OWNER);
  await pointer.hold(700);
  const segment = await take.stop();
  await new SetupWizard(take.page, new DirectHands(), null).complete(OWNER, "off");
  await take.page.close();
  return segment;
}

async function record(context: VideoContext): Promise<Segment[]> {
  const environment = context.environment;
  const proxy = environment.state.coreServer;
  const proxyLines = pickConsoleLines(await environment.log(proxy).lines(), [PLATFORM_START.velocity, ...panelHostBoot(ConsolePatterns.velocityReady)]);
  const proxyConsole = await recordReveal(
    await context.studio.scene("proxy-console", consoleScene(`${proxy} console`, proxyLines)),
    [
      { say: context.speech(SPEECH.intro), holdMs: 0 },
      { say: context.speech(SPEECH.startProxy), label: context.text(LABELS.start), reveal: indexes(proxyLines.length).slice(1), stepMs: 450, focus: [lineIndex(proxyLines, BOOT_LINE.running)], holdMs: 0 },
      { say: context.speech(SPEECH.setupCode), focus: [lineIndex(proxyLines, BOOT_LINE.setupCode)], mark: true, holdMs: 0 },
      { say: context.speech(SPEECH.panelPort), focus: [lineIndex(proxyLines, BOOT_LINE.panelListening)], holdMs: 0 },
    ],
    [0],
  );

  const origin = await publishPanel(context);
  const browser = await context.studio.panelBrowser();
  const claim = await recordClaim(context, browser, origin);
  const created = await createServerKey(context, browser, origin, context.workDir);

  const memberDir = environment.server(MEMBER).dataDir;
  const files = await recordReveal(
    await context.studio.scene("member-files", filesScene([
      { title: `${MEMBER}: plugins/modl/connection.yml`, lines: created.connectionYml.trimEnd().split("\n") },
      { title: `${MEMBER}: plugins/modl/server-features.yml`, lines: [serverNameLine(memberDir)] },
    ])),
    [
      { say: context.speech(SPEECH.save), label: context.text(LABELS.save), holdMs: 0 },
      { say: context.speech(SPEECH.explainFile), focus: [FILE_LINES.mode, FILE_LINES.url, FILE_LINES.apiKey], holdMs: 0 },
      { say: context.speech(SPEECH.serverName), label: context.text(LABELS.serverName), reveal: [1], focus: [FILE_LINES.serverName], holdMs: 0 },
    ],
    [0],
  );

  const since = await installOnMember(context, created.connectionYml);
  const memberLines = pickConsoleLines(await environment.log(MEMBER).lines(since), [
    PLATFORM_START.paper,
    { pattern: ConsolePatterns.paperReady },
    { pattern: ConsolePatterns.memberNetworkConnected, highlightGroup: 0, name: BOOT_LINE.connected },
  ]);
  const memberConsole = await recordReveal(
    await context.studio.scene("member-console", consoleScene(`${MEMBER} console`, memberLines)),
    [
      { say: context.speech(SPEECH.startServer), label: context.text(LABELS.startServer), reveal: indexes(memberLines.length).slice(1), stepMs: 550, holdMs: 0 },
      { say: context.speech(SPEECH.connectedLine), focus: [lineIndex(memberLines, BOOT_LINE.connected)], mark: true, holdMs: 0 },
    ],
    [0],
  );

  const connected = await showConnected(context, browser, origin);
  return [proxyConsole, claim, created.segment, files, memberConsole, connected];
}

export const velocityNetwork: VideoDefinition = {
  id: "velocity-network",
  title: "Connect a Velocity network",
  wikiPage: "Proxy-Network",
  topology: "network",
  labels: Object.values(LABELS),
  presentation: "narrated",
  narration: Object.values(SPEECH),
  record,
};
