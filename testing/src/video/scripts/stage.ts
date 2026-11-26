import { readdirSync, statSync } from "node:fs";
import { join } from "node:path";
import { runDocker } from "../../lib/docker";
import type { Environment } from "../../lib/environment";
import { ConsolePatterns, Endpoints, RECORD_EVERY_PLAYER } from "../../lib/contract";
import { jsonArray, jsonField, jsonString } from "../../lib/http";
import { HARNESS_OWNER_PASSWORD } from "../../lib/sign-in";
import { sleep } from "../../lib/util";
import { CONTAINER_PORTS } from "../../lib/topologies/compose";
import type { TeaserContext, VideoContext } from "../definition";
import type { ConsolePick } from "../scenes/console";
import type { FolderEntry } from "../scenes/files";
import { megabytes } from "../scenes/files";
import { SetupWizard, type ReplayChoice, type WizardOwner } from "../panel/setup-wizard";
import { DirectHands } from "../studio/pointer";
import type { PanelBrowser } from "../studio/studio";

export const OWNER: WizardOwner = {
  email: "owner@example.net",
  password: "example-panel-2026",
  serverName: "Example SMP",
};

async function containerAddress(container: string, network: string): Promise<string> {
  const result = await runDocker(["inspect", "-f", `{{(index .NetworkSettings.Networks ${JSON.stringify(network)}).IPAddress}}`, container]);
  const address = result.stdout.trim();
  if (!/^\d{1,3}(?:\.\d{1,3}){3}$/.test(address)) throw new Error(`${container} has no IPv4 address on its network (got "${address}")`);
  return address;
}

type StagedContext = Pick<VideoContext | TeaserContext, "environment" | "studio">;

function panelPort(context: StagedContext): { container: string; port: number } {
  const host = context.environment.server(context.environment.state.dataServer);
  if (host.httpPort === null) throw new Error(`${host.name} publishes no panel port`);
  return { container: host.container, port: host.httpPort };
}

export async function publishPanel(context: StagedContext): Promise<string> {
  const { container, port } = panelPort(context);
  const network = `${context.environment.state.project}_default`;
  const origin = `http://${await containerAddress(container, network)}:${String(CONTAINER_PORTS.http)}`;
  context.studio.route({ publicOrigin: origin, targetPort: port });
  return origin;
}

export const LOOPBACK_PANEL_URL = `http://localhost:${String(CONTAINER_PORTS.http)}`;

export function publishLoopbackPanel(context: StagedContext): string {
  return `http://localhost:${String(panelPort(context).port)}`;
}

export async function setupCode(environment: Environment): Promise<string> {
  const printed = await environment.instance.latestSetupCode();
  if (!printed) throw new Error("no setup code was printed to the console");
  return printed.code;
}

export interface ClaimOptions {
  readonly replays?: ReplayChoice;
  readonly panelUrl?: string | null;
  readonly owner?: WizardOwner;
}

export async function claimAndComplete(wizard: SetupWizard, code: string, options: ClaimOptions = {}): Promise<void> {
  const owner = options.owner ?? OWNER;
  await wizard.claim(code, owner);
  await wizard.complete(owner, options.replays ?? "off", options.panelUrl ?? null);
}

export function harnessOwner(environment: Environment): WizardOwner {
  return { email: environment.instance.ownerEmail, password: HARNESS_OWNER_PASSWORD, serverName: OWNER.serverName };
}

const FEATURES_TIMEOUT_MS = 60_000;

export async function recordEveryPlayer(environment: Environment, server: string): Promise<void> {
  await environment.instance.ensureOwnerSession();
  await environment.instance.updateReplaySettings(RECORD_EVERY_PLAYER);
  const deadline = Date.now() + FEATURES_TIMEOUT_MS;
  while (Date.now() < deadline) {
    const result = await environment.instance.apiWithCredentials(Endpoints.connectedServers);
    const entry = jsonArray(result.json, "servers").find((candidate) => jsonString(candidate, "name")?.toLowerCase() === server.toLowerCase());
    if (jsonField(jsonField(entry, "features"), "replaysActive") === true) return;
    await sleep(1000);
  }
  throw new Error(`${server} did not report replays active within ${String(FEATURES_TIMEOUT_MS / 1000)} s`);
}

export async function claimOffCamera(context: StagedContext, browser: PanelBrowser, origin: string, options: ClaimOptions = {}): Promise<void> {
  const page = await browser.page();
  const wizard = new SetupWizard(page, new DirectHands(), null);
  await wizard.open(origin);
  await claimAndComplete(wizard, await setupCode(context.environment), options);
  await page.close();
}

export function pluginJars(serverDir: string): FolderEntry[] {
  const plugins = join(serverDir, "plugins");
  return readdirSync(plugins)
    .filter((file) => file.endsWith(".jar"))
    .sort()
    .map((file) => ({ name: file, detail: megabytes(statSync(join(plugins, file)).size) }));
}

export const PLATFORM_START = {
  paper: { pattern: /Starting minecraft server version/ },
  velocity: { pattern: /Booting up Velocity/ },
} as const satisfies Record<string, ConsolePick>;

export const BOOT_LINE = {
  setupCode: "setup-code",
  panelListening: "panel-listening",
  running: "running",
  connected: "connected",
} as const;

export function panelHostBoot(ready: RegExp): ConsolePick[] {
  return [
    { pattern: ConsolePatterns.serverLibrariesDownload },
    { pattern: ready },
    { pattern: ConsolePatterns.serverLibrariesInstalled },
    { pattern: ConsolePatterns.setupCode, highlightGroup: 1, name: BOOT_LINE.setupCode },
    { pattern: ConsolePatterns.panelStarted, name: BOOT_LINE.panelListening },
    { pattern: ConsolePatterns.pluginBooted, name: BOOT_LINE.running },
  ];
}
