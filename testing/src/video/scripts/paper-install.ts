import type { VideoContext, VideoDefinition } from "../definition";
import type { StepLabel } from "../labels";
import { consoleScene, lineIndex, pickConsoleLines } from "../scenes/console";
import { folderScene } from "../scenes/files";
import { indexes, recordReveal } from "../scenes/play";
import type { Segment } from "../studio/segment";
import { SetupWizard, type WizardCue } from "../panel/setup-wizard";
import { ConsolePatterns } from "../../lib/contract";
import { BOOT_LINE, PLATFORM_START, claimAndComplete, panelHostBoot, publishPanel, pluginJars, setupCode } from "./stage";

const LABELS = {
  plugins: { page: "Single-Server", text: "Put PacketEvents and modl-<version>.jar in plugins/" },
  start: { page: "Single-Server", text: "Start the server" },
  port: { page: "Single-Server", text: "Give the panel its own port" },
  open: { page: "First-Run", text: "Open the /setup address" },
  code: { page: "First-Run", text: "Enter the setup code" },
  account: { page: "First-Run", text: "Email code or password" },
  import: { page: "First-Run", text: "Start fresh" },
  identity: { page: "First-Run", text: "Server name and icons" },
  address: { page: "First-Run", text: "Panel URL" },
  servers: { page: "First-Run", text: "Keys and connection.yml for your servers" },
  email: { page: "First-Run", text: "No email delivery" },
  staff: { page: "First-Run", text: "No staff yet" },
  addresses: { page: "First-Run", text: "No lookups" },
  ai: { page: "First-Run", text: "No AI moderation" },
  replays: { page: "First-Run", text: "Replays on or off" },
  content: { page: "First-Run", text: "ends setup and opens the panel" },
} as const satisfies Record<string, StepLabel>;

const SPEECH = {
  intro: "Hey everyone, welcome to the modl install tutorial. Today we set it up on a single Paper server.",
  plugins: "Put PacketEvents and the modl jar in your server's plugins folder.",
  start: "Start the server. The first start downloads the panel and about 100 megabytes of libraries.",
  setupCode: "Then the console prints a one-time setup code, valid for one hour.",
  panelPort: "The panel listens on its own port, 8080 by default, never the Minecraft port.",
  running: "Players can join once the console says modl is running.",
  open: "Open the setup address from the console in your browser.",
  code: "Enter the setup code and your email. This account becomes the owner.",
  account: "Choose how you sign in: with an email code, or a password.",
  import: "Start fresh, unless you are moving from modl.gg.",
  identity: "Give the panel your server's name.",
  address: "modl checks the panel URL it puts in every link it sends.",
  email: "Without a mail server, choose no email delivery. Sign-in codes then appear in the console.",
  servers: "This server is already connected. Keys and connection files for more servers come from here.",
  minecraftAccount: "You can link your Minecraft account later, from your profile.",
  staff: "Invite staff now, or later in Settings.",
  addresses: "Choose whether to look up player IP addresses for alt detection.",
  ai: "AI moderation is optional, too.",
  replays: "Replays are optional. Turn them on, so staff can watch the moments before a report.",
  content: "Finish lists the default content, then ends setup and opens the panel.",
  done: "Your panel is ready. Everything can be changed later in Settings.",
} as const;

const PANEL_ENTRY = { x: 900, y: 600 };

async function record(context: VideoContext): Promise<Segment[]> {
  const environment = context.environment;
  const server = environment.server(environment.state.coreServer);
  const plugins = await recordReveal(await context.studio.scene("plugins", folderScene("plugins/", pluginJars(server.dataDir))), [
    { say: context.speech(SPEECH.intro), holdMs: 0 },
    { say: context.speech(SPEECH.plugins), label: context.text(LABELS.plugins), holdMs: 600 },
  ], indexes(2));

  const lines = pickConsoleLines(await environment.log(server.name).lines(), [PLATFORM_START.paper, ...panelHostBoot(ConsolePatterns.paperReady)]);
  const boot = await recordReveal(
    await context.studio.scene("console", consoleScene("Server console", lines)),
    [
      { say: context.speech(SPEECH.start), label: context.text(LABELS.start), reveal: indexes(lines.length).slice(1), stepMs: 450, holdMs: 0 },
      { say: context.speech(SPEECH.setupCode), focus: [lineIndex(lines, BOOT_LINE.setupCode)], mark: true, holdMs: 0 },
      { say: context.speech(SPEECH.panelPort), label: context.text(LABELS.port), focus: [lineIndex(lines, BOOT_LINE.panelListening)], holdMs: 0 },
      { say: context.speech(SPEECH.running), label: null, focus: [lineIndex(lines, BOOT_LINE.running)], holdMs: 0 },
    ],
    [0],
  );

  const origin = await publishPanel(context);
  const code = await setupCode(environment);
  const browser = await context.studio.panelBrowser();
  const take = await browser.take("setup");
  const pointer = take.pointer(PANEL_ENTRY);
  const cue = (label: StepLabel, line: string): WizardCue => ({ label: context.text(label), say: context.speech(line) });
  const wizard = new SetupWizard(take.page, pointer, {
    narrator: pointer,
    speaker: take,
    cues: {
      code: cue(LABELS.code, SPEECH.code),
      account: cue(LABELS.account, SPEECH.account),
      import: cue(LABELS.import, SPEECH.import),
      identity: cue(LABELS.identity, SPEECH.identity),
      address: cue(LABELS.address, SPEECH.address),
      email: cue(LABELS.email, SPEECH.email),
      servers: cue(LABELS.servers, SPEECH.servers),
      "minecraft-account": { say: context.speech(SPEECH.minecraftAccount) },
      staff: cue(LABELS.staff, SPEECH.staff),
      "player-addresses": cue(LABELS.addresses, SPEECH.addresses),
      ai: cue(LABELS.ai, SPEECH.ai),
      replays: cue(LABELS.replays, SPEECH.replays),
      content: cue(LABELS.content, SPEECH.content),
    },
  });
  await wizard.open(origin);
  await take.start();
  await pointer.appear();
  await pointer.label(context.text(LABELS.open));
  await take.say(context.speech(SPEECH.open));
  await take.untilSilent();
  await claimAndComplete(wizard, code, { replays: "on" });
  await pointer.label(null);
  await pointer.hide();
  await take.say(context.speech(SPEECH.done));
  await pointer.hold(1200);
  const panel = await take.stop();
  return [plugins, boot, panel];
}

export const paperInstall: VideoDefinition = {
  id: "paper-install",
  title: "Install on one Paper server",
  wikiPage: "Single-Server",
  topology: "standalone",
  labels: Object.values(LABELS),
  presentation: "narrated",
  narration: Object.values(SPEECH),
  record,
};
