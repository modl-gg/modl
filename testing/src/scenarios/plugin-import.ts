import { copyFileSync, cpSync, existsSync, mkdirSync, readdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { ensureArtifact, type ArtifactId } from "../lib/artifacts";
import { patchYamlFile, readYamlValue } from "../lib/config-files";
import { ConsoleCommands, ImportMessages } from "../lib/contract";
import { jsonArray, jsonField, jsonString } from "../lib/http";
import { readJarEntry, walkJar } from "../lib/jar";
import { MariaDbSidecar } from "../lib/mariadb-sidecar";
import type { LineName, TopologyName, UpOptions } from "../lib/state";
import { modlDir } from "../lib/topologies/server-files";
import { offlineUuid, removePath } from "../lib/util";
import { defineScenario, type Scenario, type ScenarioContext } from "./framework";
import { awaitImportEnded, ensureSetupFinished, isNoteOfKind, latestImportJob, playerProfile, punishmentData, typeOrdinalOf } from "./import-support";
import { expectJoinRefused } from "./support";

const IMPORT_TIMEOUT_MS = 600_000;
const RESTART_TIMEOUT_MS = 420_000;
const SEED_TIMEOUT_MS = 240_000;
const COMMAND_SETTLE_MS = 2500;
const SETTLE_TIMEOUT_MS = 60_000;
const SETTLE_POLL_MS = 1000;
const MUTE_HOURS = 2;
const HOUR_MS = 3_600_000;
const BAN_ORDINAL = 2;
const MUTE_ORDINAL = 1;
const KICK_ORDINAL = 0;
const PARDON = "PARDON";
const IP_BAN_REASON = "Alts";
const MARIADB_DATABASE = "bans";
const MARIADB_USER = "advancedban";

interface SeedNames {
  banned: string;
  muted: string;
  lifted: string;
}

type IpBanExpectation = "alt-blocking" | "unattributed" | "none";

interface PluginJar {
  artifact: ArtifactId;
  zipEntry?: string;
  target: (pluginsDir: string) => string;
}

type ProxyPlatform = "velocity" | "bungeecord";

const PROXY_OF: Partial<Record<TopologyName, ProxyPlatform>> = { network: "velocity", "network-bungee": "bungeecord" };
const EVERY_TOPOLOGY: readonly TopologyName[] = ["standalone", "network", "network-bungee", "remote"];
const PAPER_TOPOLOGIES: readonly TopologyName[] = ["standalone", "remote"];

function notOnFolia(plugin: string): Partial<Record<LineName, string>> {
  return { folia: `${plugin} does not declare folia-supported in its plugin.yml, so Folia refuses to load it` };
}

interface ProxyVariant {
  lines: readonly LineName[];
  unavailableOn?: Partial<Record<LineName, string>>;
  folders: readonly string[];
  jars: readonly PluginJar[];
  commands: SourcePlugin["commands"];
  modlCommandsOff?: readonly string[];
  seededOn?: string;
  carried?: readonly string[];
}

interface SourcePlugin {
  scenario: string;
  source: string;
  description: string;
  topologies: readonly TopologyName[];
  lines: readonly LineName[];
  unavailableOn?: Partial<Record<LineName, string>>;
  proxies: Record<ProxyPlatform, ProxyVariant | string>;
  folders: readonly string[];
  jars: readonly PluginJar[];
  modlCommandsOff?: readonly string[];
  seededOn?: string;
  carried?: readonly string[];
  cleanupFiles?: readonly string[];
  runningDuringImport: boolean;
  prepare?: (t: ScenarioContext, pluginsDir: string) => Promise<void>;
  release?: () => Promise<void>;
  commands: (names: SeedNames, uuidOf: (name: string) => string) => string[];
  cleanupCommands?: (serverDir: string, names: SeedNames) => string[];
  settled?: (serverDir: string, names: SeedNames) => boolean;
  expects: {
    banReason: string;
    muteMillis: number;
    warnings: boolean;
    kicks: boolean;
    ipBans: IpBanExpectation;
    pardonIssuer: string | null;
    lifted: boolean;
    history: boolean;
  };
}

function plainJar(artifact: ArtifactId, file: string): PluginJar {
  return { artifact, target: (pluginsDir) => join(pluginsDir, file) };
}

async function placeJar(t: ScenarioContext, jar: PluginJar, pluginsDir: string): Promise<void> {
  const artifact = await ensureArtifact(jar.artifact, t.logger);
  const target = jar.target(pluginsDir);
  mkdirSync(dirname(target), { recursive: true });
  const entry = jar.zipEntry;
  if (entry === undefined) {
    copyFileSync(artifact, target);
    return;
  }
  let found = false;
  await walkJar(artifact, {
    wants: (name) => name === entry,
    visit: async (_, read) => {
      writeFileSync(target, await read());
      found = true;
    },
  });
  t.require(found, `${artifact} has no ${entry}`);
}

function removePlugin(plugin: SourcePlugin, serverDir: string, keepFolders: boolean): void {
  const pluginsDir = join(serverDir, "plugins");
  for (const jar of plugin.jars) {
    const target = jar.target(pluginsDir);
    if (dirname(target) === pluginsDir) removePath(target);
  }
  if (keepFolders) return;
  const folders = new Set(plugin.folders.map((folder) => folder.toLowerCase()));
  const present = existsSync(pluginsDir) ? readdirSync(pluginsDir) : [];
  for (const entry of present) {
    if (folders.has(entry.toLowerCase())) removePath(join(pluginsDir, entry));
  }
  for (const file of plugin.cleanupFiles ?? []) removePath(join(serverDir, file));
}

function onTopology(plugin: SourcePlugin, topology: TopologyName): SourcePlugin {
  const platform = PROXY_OF[topology];
  if (platform === undefined) return plugin;
  const variant = plugin.proxies[platform];
  if (typeof variant === "string") throw new Error(`${plugin.scenario} does not run on ${topology}: ${variant}`);
  const placed: SourcePlugin = { ...plugin, lines: variant.lines, folders: variant.folders, jars: variant.jars, commands: variant.commands,
    modlCommandsOff: variant.modlCommandsOff ?? [] };
  if (variant.seededOn !== undefined) placed.seededOn = variant.seededOn;
  if (variant.carried !== undefined) placed.carried = variant.carried;
  return placed;
}

function linesOf(plugin: SourcePlugin): LineName[] {
  const lines = new Set<LineName>(plugin.lines);
  for (const topology of plugin.topologies) {
    const platform = PROXY_OF[topology];
    const variant = platform === undefined ? undefined : plugin.proxies[platform];
    if (variant !== undefined && typeof variant !== "string") for (const line of variant.lines) lines.add(line);
  }
  return [...lines];
}

function unsupportedOn(plugin: SourcePlugin, options: UpOptions): string | null {
  const platform = PROXY_OF[options.topology];
  if (platform === undefined) {
    const unavailable = plugin.unavailableOn?.[options.line];
    if (unavailable !== undefined) return unavailable;
    return plugin.lines.includes(options.line) ? null : `not applicable on the ${options.line} line (runs on ${plugin.lines.join(", ")})`;
  }
  const variant = plugin.proxies[platform];
  if (typeof variant === "string") return variant;
  const unavailable = variant.unavailableOn?.[options.line];
  if (unavailable !== undefined) return unavailable;
  return variant.lines.includes(options.line)
    ? null
    : `not applicable on the ${options.line} line behind ${platform} (runs on ${variant.lines.join(", ")})`;
}

class ModlCommands {
  private readonly file: string;
  private readonly saved = new Map<string, unknown>();

  constructor(serverDir: string) {
    this.file = join(modlDir(serverDir), "config.yml");
  }

  turnOff(keys: readonly string[]): void {
    const patches: Record<string, unknown> = {};
    for (const key of keys) {
      if (!this.saved.has(key)) this.saved.set(key, readYamlValue(this.file, `commands.${key}`));
      patches[`commands.${key}`] = "";
    }
    if (keys.length > 0) patchYamlFile(this.file, patches);
  }

  restore(): boolean {
    if (this.saved.size === 0) return false;
    const patches: Record<string, unknown> = {};
    const absent: string[] = [];
    for (const [key, value] of this.saved) {
      if (value === undefined) absent.push(`commands.${key}`);
      else patches[`commands.${key}`] = value;
    }
    patchYamlFile(this.file, patches, "", absent);
    this.saved.clear();
    return true;
  }
}

function punishmentsOf(profile: unknown): unknown[] {
  return jsonArray(profile, "punishments");
}

function durationOf(punishment: unknown): number {
  const raw = jsonField(punishmentData(punishment), "duration");
  return typeof raw === "number" ? raw : Number(raw ?? 0);
}

function isActive(punishment: unknown): boolean {
  return jsonField(punishmentData(punishment), "active") === true;
}

function reasonOf(punishment: unknown): string | null {
  return jsonString(punishmentData(punishment), "reason");
}

function modificationsOf(punishment: unknown): unknown[] {
  return jsonArray(punishment, "modifications");
}

async function verifyImport(t: ScenarioContext, plugin: SourcePlugin, names: SeedNames): Promise<void> {
  const banned = await playerProfile(t, offlineUuid(names.banned));
  const muted = await playerProfile(t, offlineUuid(names.muted));
  const lifted = await playerProfile(t, offlineUuid(names.lifted));
  t.evidence("banned", banned);
  t.evidence("muted", muted);
  t.evidence("lifted", lifted);

  const bans = punishmentsOf(banned).filter((punishment) => typeOrdinalOf(punishment) === BAN_ORDINAL);
  t.check("the ban arrived active and issued by the console", bans.some((ban) => isActive(ban)
    && jsonString(ban, "issuerName") === "Console" && reasonOf(ban) === plugin.expects.banReason), bans);

  const mutes = punishmentsOf(muted).filter((punishment) => typeOrdinalOf(punishment) === MUTE_ORDINAL);
  const muteMillis = plugin.expects.muteMillis;
  t.check("the temporary mute arrived active with its duration", mutes.some((mute) => isActive(mute)
    && Math.abs(durationOf(mute) - muteMillis) <= HOUR_MS), mutes);

  if (plugin.expects.warnings) {
    const notes = jsonArray(muted, "notes");
    t.check("the warning became a warning note", notes.some((note) => isNoteOfKind(note, "WARNING")
      && jsonString(note, "text") === "Language"), notes);
  }
  if (plugin.expects.kicks) {
    t.check("the kick arrived as a kick", punishmentsOf(muted).some((punishment) => typeOrdinalOf(punishment) === KICK_ORDINAL
      && reasonOf(punishment) === "AFK"), punishmentsOf(muted));
  }
  if (plugin.expects.lifted) {
    const liftedBans = punishmentsOf(lifted).filter((punishment) => typeOrdinalOf(punishment) === BAN_ORDINAL);
    const pardoned = liftedBans.filter((ban) => !isActive(ban)
      && modificationsOf(ban).some((modification) => (jsonString(modification, "type") ?? "").includes(PARDON)));
    t.check("the lifted ban arrived pardoned", pardoned.length === 1, liftedBans);
    const issuer = plugin.expects.pardonIssuer;
    if (issuer !== null) {
      t.check("the pardon names who lifted it", pardoned.some((ban) => modificationsOf(ban).some((modification) =>
        jsonString(modification, "type") === "MANUAL_PARDON" && jsonString(modification, "issuerName") === issuer)), pardoned);
    }
  }
  const everyPunishment = [...punishmentsOf(banned), ...punishmentsOf(muted), ...punishmentsOf(lifted)];
  switch (plugin.expects.ipBans) {
    case "alt-blocking":
      t.check("the IP ban arrived as an alt-blocking ban", everyPunishment.some((punishment) => typeOrdinalOf(punishment) === BAN_ORDINAL
        && jsonField(punishmentData(punishment), "altBlocking") === true && isActive(punishment)), everyPunishment);
      break;
    case "unattributed":
      t.check("the IP ban of an address every bot shared went to no account", everyPunishment.every((punishment) =>
        reasonOf(punishment) !== IP_BAN_REASON), everyPunishment);
      break;
    case "none":
      break;
  }
  if (plugin.expects.history) {
    t.check("the address history arrived", jsonArray(banned, "ipAddresses").length > 0, jsonArray(banned, "ipAddresses"));
  }
  t.check("the player names arrived", jsonArray(muted, "usernames").some((entry) => jsonString(entry, "username") === names.muted),
    jsonArray(muted, "usernames"));
}

interface Placement {
  core: string;
  coreDir: string;
  holder: string;
  holderDir: string;
}

function placementOf(t: ScenarioContext, plugin: SourcePlugin): Placement {
  const core = t.coreServer;
  const holder = plugin.seededOn ?? core;
  return { core, coreDir: t.env.server(core).dataDir, holder, holderDir: t.env.server(holder).dataDir };
}

async function restartPlacement(t: ScenarioContext, placement: Placement): Promise<void> {
  if (placement.holder !== placement.core) await t.restart(placement.holder);
  await t.restart(placement.core);
}

async function seed(t: ScenarioContext, plugin: SourcePlugin, placement: Placement, names: SeedNames): Promise<void> {
  for (const name of [names.banned, names.muted, names.lifted]) await t.bot(name);
  await t.sleep(COMMAND_SETTLE_MS);
  for (const command of plugin.commands(names, offlineUuid)) {
    await t.consoleRun(command, { server: placement.holder });
    await t.sleep(COMMAND_SETTLE_MS);
  }
  t.quitAllBots();
  const settled = plugin.settled;
  if (settled === undefined) return;
  const deadline = Date.now() + SETTLE_TIMEOUT_MS;
  while (!settled(placement.holderDir, names)) {
    t.require(Date.now() < deadline, `${plugin.source} did not write its data within ${SETTLE_TIMEOUT_MS.toString()}ms`);
    await t.sleep(SETTLE_POLL_MS);
  }
}

function carryToCore(plugin: SourcePlugin, placement: Placement): void {
  for (const path of plugin.carried ?? []) {
    const from = join(placement.holderDir, path);
    if (!existsSync(from)) continue;
    const to = join(placement.coreDir, path);
    removePath(to);
    mkdirSync(dirname(to), { recursive: true });
    cpSync(from, to, { recursive: true });
  }
}

function removeCarried(plugin: SourcePlugin, placement: Placement): void {
  if (plugin.carried === undefined) return;
  for (const path of plugin.carried) removePath(join(placement.coreDir, path));
  removePlugin({ ...plugin, jars: [], cleanupFiles: [] }, placement.coreDir, false);
}

async function liftServerBans(t: ScenarioContext, plugin: SourcePlugin, placement: Placement, names: SeedNames): Promise<void> {
  const commands = [...(plugin.cleanupCommands?.(placement.holderDir, names) ?? []),
    ...bannedAddresses(placement.holderDir).map((ip) => `minecraft:pardon-ip ${ip}`)];
  for (const command of commands) await t.consoleRun(command, { server: placement.holder });
}

async function removeEverything(t: ScenarioContext, plugin: SourcePlugin, placement: Placement, names: SeedNames): Promise<void> {
  await liftServerBans(t, plugin, placement, names);
  removePlugin(plugin, placement.holderDir, false);
  removeCarried(plugin, placement);
}

async function runImport(t: ScenarioContext, plugin: SourcePlugin): Promise<void> {
  const detected = await t.consoleExpect(ConsoleCommands.importSources, ImportMessages.detected, { timeoutMs: 60_000 });
  t.check(`${plugin.source} is detected`, (detected.matched ?? "").includes(plugin.source), detected.matched);
  const preview = await t.consoleExpect(ConsoleCommands.importSourcePreview(plugin.source), ImportMessages.previewReady,
    { timeoutMs: 120_000 });
  const counts = preview.lines.map((line) => ImportMessages.previewCounts.exec(line)).find((match) => match !== null);
  t.evidence("preview", preview.lines);
  t.check("the preview counted the seeded punishments", counts !== undefined && Number(counts[1]) >= 2 && Number(counts[2]) >= 1,
    counts ?? null);
  await t.consoleExpect(ConsoleCommands.importSourceConfirm(plugin.source, "widen keep"), ImportMessages.started, { timeoutMs: 120_000 });
  const id = jsonString(await latestImportJob(t), "id");
  t.require(id, "the started import has no id");
  const job = await awaitImportEnded(t, id, IMPORT_TIMEOUT_MS);
  t.evidence("job", job);
  t.check("the import completed", jsonString(job, "status") === "completed", job);
  t.check("the job names its source", jsonString(job, "type") === plugin.source, job);
  t.check("no player failed", Number(jsonField(job, "exportFailed") ?? 0) === 0, job);
}

function pluginImportScenario(plugin: SourcePlugin): Scenario {
  return defineScenario({
    name: plugin.scenario,
    description: plugin.description,
    topologies: plugin.topologies,
    lines: linesOf(plugin),
    unsupportedBy: (options) => unsupportedOn(plugin, options),
    async run(t: ScenarioContext) {
      const installed = onTopology(plugin, t.env.state.topology);
      const placement = placementOf(t, installed);
      const modlCommands = new ModlCommands(placement.coreDir);
      let finished = false;
      const names: SeedNames = { banned: t.playerName("Ban"), muted: t.playerName("Mute"), lifted: t.playerName("Lift") };

      await t.step("the modl setup is finished", async () => ensureSetupFinished(t), { timeoutMs: 120_000 });

      try {
        await t.step(
          `the real plugin is installed`,
          async () => {
            await removeEverything(t, installed, placement, names);
            const pluginsDir = join(placement.holderDir, "plugins");
            for (const jar of installed.jars) await placeJar(t, jar, pluginsDir);
            await installed.prepare?.(t, pluginsDir);
            modlCommands.turnOff(installed.modlCommandsOff ?? []);
            await restartPlacement(t, placement);
          },
          { timeoutMs: 2 * RESTART_TIMEOUT_MS + 300_000, allowConsoleErrors: true },
        );
        await importAndVerify(t, installed, placement, names, modlCommands);
        finished = true;
      } finally {
        try {
          await removeEverything(t, installed, placement, names);
          const restored = modlCommands.restore();
          if (restored || (!finished && placement.holder !== placement.core)) await restartPlacement(t, placement);
        } finally {
          await installed.release?.();
        }
      }
    },
  });
}

async function importAndVerify(t: ScenarioContext, plugin: SourcePlugin, placement: Placement, names: SeedNames,
  modlCommands: ModlCommands): Promise<void> {
  await t.step("punishments are made with the plugin's own console commands", async () => seed(t, plugin, placement, names), {
    timeoutMs: SEED_TIMEOUT_MS,
    allowConsoleErrors: true,
  });

  if (!plugin.runningDuringImport) {
    await t.step(
      "the plugin is removed and its data stays",
      async () => {
        removePlugin(plugin, placement.holderDir, true);
        await t.restart(placement.holder);
      },
      { timeoutMs: RESTART_TIMEOUT_MS, allowConsoleErrors: true },
    );
  }

  if (plugin.carried !== undefined) {
    await t.step(`the data is copied to the ${placement.core} like the documentation says`, () => {
      carryToCore(plugin, placement);
    });
  }

  await t.step("modl imports the data", async () => runImport(t, plugin), {
    timeoutMs: IMPORT_TIMEOUT_MS + 300_000,
    allowConsoleErrors: plugin.runningDuringImport,
  });

  await t.step("the imported punishments are right", async () => verifyImport(t, plugin, names), { timeoutMs: 120_000 });

  await t.step(
    "without the plugin, modl itself refuses the banned player",
    async () => {
      await removeEverything(t, plugin, placement, names);
      modlCommands.restore();
      await restartPlacement(t, placement);
      await expectJoinRefused(t, names.banned);
    },
    { timeoutMs: 2 * RESTART_TIMEOUT_MS + 120_000, allowConsoleErrors: true },
  );
}

function bannedAddresses(serverDir: string): string[] {
  const file = join(serverDir, "banned-ips.json");
  if (!existsSync(file)) return [];
  const parsed: unknown = JSON.parse(readFileSync(file, "utf8"));
  return Array.isArray(parsed) ? parsed.map((entry) => jsonString(entry, "ip")).filter((ip): ip is string => ip !== null) : [];
}

const ADVANCEDBAN: SourcePlugin = {
  scenario: "advancedban-import",
  source: "advancedban",
  description: "the real AdvancedBan 2.3.0: punishments made with its commands on its HSQLDB arrive in modl after it is removed",
  topologies: EVERY_TOPOLOGY,
  lines: ["latest", "1.21", "legacy", "folia"],
  unavailableOn: notOnFolia("AdvancedBan 2.3.0"),
  proxies: {
    velocity: "AdvancedBan 2.3.0 has no Velocity version (its jar declares only plugin.yml and bungee.yml), so it cannot run on the proxy that runs the core",
    bungeecord: {
      lines: ["latest", "1.21", "legacy", "folia"],
      folders: ["AdvancedBan"],
      jars: [plainJar("advancedban", "AdvancedBan-Bundle-2.3.0-RELEASE.jar")],
      commands: (names) => [
        `tempmute ${names.muted} ${MUTE_HOURS.toString()}h Spam`,
        `warn ${names.muted} Language`,
        `kick ${names.muted} AFK`,
        `ban ${names.lifted} Mistake`,
        `unban ${names.lifted}`,
        `ban ${names.banned} Cheating`,
        `ipban ${names.banned} ${IP_BAN_REASON}`,
      ],
      modlCommandsOff: ["ban", "mute", "warn", "kick", "unban"],
    },
  },
  folders: ["AdvancedBan"],
  jars: [plainJar("advancedban", "AdvancedBan-Bundle-2.3.0-RELEASE.jar")],
  runningDuringImport: false,
  commands: (names) => [
    `advancedban:tempmute ${names.muted} ${MUTE_HOURS}h Spam`,
    `advancedban:warn ${names.muted} Language`,
    `advancedban:kick ${names.muted} AFK`,
    `advancedban:ban ${names.lifted} Mistake`,
    `advancedban:unban ${names.lifted}`,
    `advancedban:ban ${names.banned} Cheating`,
    `advancedban:ipban ${names.banned} ${IP_BAN_REASON}`,
  ],
  expects: { banReason: "Cheating", muteMillis: MUTE_HOURS * HOUR_MS, warnings: true, kicks: true, ipBans: "alt-blocking", pardonIssuer: null, lifted: true, history: false },
};

function libertyBansJars(folder: string): PluginJar[] {
  return [
    plainJar("libertybans", "LibertyBans_Release-1.1.4.jar"),
    {
      artifact: "libertybansImplementation",
      target: (pluginsDir) => join(pluginsDir, folder, "internal", "libraries", "space.arim.libertybans.bans-download_1.1.4.jar"),
    },
  ];
}

const LIBERTYBANS_COMMANDS: SourcePlugin["commands"] = (names) => [
  `libertybans mute ${names.muted} ${MUTE_HOURS.toString()}h Spam`,
  `libertybans warn ${names.muted} Language`,
  `libertybans kick ${names.muted} AFK`,
  `libertybans ban ${names.lifted} Mistake`,
  `libertybans unban ${names.lifted}`,
  `libertybans ban ${names.banned} Cheating`,
  `libertybans ipban ${names.banned} ${IP_BAN_REASON}`,
];

const LIBERTYBANS_ON_VELOCITY_4 = "LibertyBans 1.1.4 does not start on Velocity 4 (NoSuchMethodError TextComponent.ofChildren: the Adventure version of Velocity 4 removed it); it runs on Velocity 3, the 1.21 line";

const LIBERTYBANS: SourcePlugin = {
  scenario: "libertybans-import",
  source: "libertybans",
  description: "the real LibertyBans 1.1.4: punishments made with its commands arrive in modl, read through its own connection while it runs",
  topologies: EVERY_TOPOLOGY,
  lines: ["latest", "1.21", "folia"],
  proxies: {
    velocity: { lines: ["1.21"], folders: ["libertybans"], jars: libertyBansJars("libertybans"), commands: LIBERTYBANS_COMMANDS,
      unavailableOn: { latest: LIBERTYBANS_ON_VELOCITY_4, folia: LIBERTYBANS_ON_VELOCITY_4 } },
    bungeecord: { lines: ["latest", "1.21", "legacy", "folia"], folders: ["LibertyBans"], jars: libertyBansJars("LibertyBans"), commands: LIBERTYBANS_COMMANDS },
  },
  folders: ["LibertyBans"],
  jars: libertyBansJars("LibertyBans"),
  runningDuringImport: true,
  commands: LIBERTYBANS_COMMANDS,
  expects: { banReason: "Cheating", muteMillis: MUTE_HOURS * HOUR_MS, warnings: true, kicks: true, ipBans: "unattributed", pardonIssuer: null, lifted: true, history: true },
};

const BANMANAGER_PROXY_COMMANDS: SourcePlugin["commands"] = (names) => [
  `bmtempmute ${names.muted} ${MUTE_HOURS.toString()}h Spam`,
  `bmwarn ${names.muted} Language`,
  `bmban ${names.lifted} Mistake`,
  `bmunban ${names.lifted} Appeal accepted`,
  `bmban ${names.banned} Cheating`,
  `bmbanip ${names.banned} ${IP_BAN_REASON}`,
];

const BANMANAGER: SourcePlugin = {
  scenario: "banmanager-import",
  source: "banmanager",
  description: "the real BanManager 7.11.0: punishments made with its commands on its H2 database arrive in modl, read through its own connection",
  topologies: EVERY_TOPOLOGY,
  lines: ["latest", "1.21", "folia"],
  unavailableOn: notOnFolia("BanManager 7.11.0"),
  proxies: {
    velocity: { lines: ["latest", "1.21", "folia"], folders: ["banmanager"], jars: [plainJar("banmanagerVelocity", "BanManagerVelocity-7.11.0.jar")],
      commands: BANMANAGER_PROXY_COMMANDS },
    bungeecord: { lines: ["latest", "1.21", "legacy", "folia"], folders: ["BanManager"],
      jars: [plainJar("banmanagerBungeecord", "BanManagerBungeeCord-7.11.0.jar")], commands: BANMANAGER_PROXY_COMMANDS },
  },
  folders: ["BanManager"],
  jars: [plainJar("banmanager", "BanManagerBukkit-7.11.0.jar")],
  runningDuringImport: true,
  commands: (names) => [
    `banmanager:tempmute ${names.muted} ${MUTE_HOURS}h Spam`,
    `banmanager:warn ${names.muted} Language`,
    `banmanager:ban ${names.lifted} Mistake`,
    `banmanager:unban ${names.lifted} Appeal accepted`,
    `banmanager:ban ${names.banned} Cheating`,
    `banmanager:banip ${names.banned} ${IP_BAN_REASON}`,
  ],
  expects: { banReason: "Cheating", muteMillis: MUTE_HOURS * HOUR_MS, warnings: true, kicks: false, ipBans: "unattributed", pardonIssuer: "Console", lifted: true, history: true },
};

const BANSYSTEM_MUTE_ID = 6;
const BANSYSTEM_BAN_ID = 1;
const BANSYSTEM_LIFTED_ID = 2;
const BANSYSTEM_MUTE_MILLIS = 24 * HOUR_MS;

const BANSYSTEM_JAR: PluginJar = { artifact: "bansystemArchive", zipEntry: "BanSystem-3.1.jar", target: (pluginsDir) => join(pluginsDir, "BanSystem-3.1.jar") };
const BANSYSTEM_CLASHES: readonly string[] = ["ban", "kick", "unban", "unmute", "history", "inspect"];
const BANSYSTEM_COMMANDS: SourcePlugin["commands"] = (names, uuidOf) => [
  `ban ${names.muted} ${BANSYSTEM_MUTE_ID.toString()}`,
  `kick ${names.muted} AFK`,
  `ban ${names.lifted} ${BANSYSTEM_LIFTED_ID.toString()}`,
  `unban ${uuidOf(names.lifted)}`,
  `ban ${names.banned} ${BANSYSTEM_BAN_ID.toString()}`,
];

const BANSYSTEM: SourcePlugin = {
  scenario: "bansystem-import",
  source: "bansystem",
  description: "the real BanSystem 3.1 (BanSystem mit IDs): ID punishments on its SQLite database arrive in modl, read from a copy while it runs (on a proxy with Floodgate, which BanSystem needs there)",
  topologies: EVERY_TOPOLOGY,
  lines: ["latest", "1.21", "folia"],
  unavailableOn: notOnFolia("BanSystem 3.1"),
  proxies: {
    velocity: { lines: ["latest", "1.21", "folia"], folders: ["BanSystem", "floodgate"],
      jars: [BANSYSTEM_JAR, plainJar("floodgateVelocity", "floodgate-velocity.jar")], commands: BANSYSTEM_COMMANDS,
      modlCommandsOff: BANSYSTEM_CLASHES },
    bungeecord: { lines: ["latest", "1.21", "legacy", "folia"], folders: ["BanSystem", "floodgate"],
      jars: [BANSYSTEM_JAR, plainJar("floodgateBungeecord", "floodgate-bungee.jar")], commands: BANSYSTEM_COMMANDS,
      modlCommandsOff: BANSYSTEM_CLASHES },
  },
  folders: ["BanSystem"],
  jars: [BANSYSTEM_JAR],
  runningDuringImport: true,
  commands: (names, uuidOf) => [
    `bansystem:ban ${names.muted} ${BANSYSTEM_MUTE_ID}`,
    `bansystem:kick ${names.muted} AFK`,
    `bansystem:ban ${names.lifted} ${BANSYSTEM_LIFTED_ID}`,
    `bansystem:unban ${uuidOf(names.lifted)}`,
    `bansystem:ban ${names.banned} ${BANSYSTEM_BAN_ID}`,
  ],
  expects: {
    banReason: "Unerlaubte Clientmodifikation/Hackclient",
    muteMillis: BANSYSTEM_MUTE_MILLIS,
    warnings: false,
    kicks: true,
    ipBans: "none",
    pardonIssuer: "Console",
    lifted: true,
    history: true,
  },
};

function vanillaBehindProxy(lines: readonly LineName[]): ProxyVariant {
  return {
    lines,
    unavailableOn: ESSENTIALS_ON_FOLIA,
    folders: ["Essentials"],
    jars: [plainJar("essentials", "EssentialsX-2.22.0.jar")],
    commands: VANILLA_COMMANDS,
    seededOn: VANILLA_MEMBER,
    carried: ["banned-players.json", "banned-ips.json", join("plugins", "Essentials", "userdata")],
  };
}

const VANILLA_MEMBER = "lobby";
const ESSENTIALS_ON_FOLIA = notOnFolia("EssentialsX 2.22.0, which makes the mutes,");
const VANILLA_COMMANDS: SourcePlugin["commands"] = (names) => [
  `essentials:mute ${names.muted} ${MUTE_HOURS.toString()}h Spam`,
  `minecraft:kick ${names.muted} Saving`,
  `minecraft:ban-ip ${names.banned} ${IP_BAN_REASON}`,
  `minecraft:ban ${names.banned} Cheating`,
];

const VANILLA: SourcePlugin = {
  scenario: "vanilla-import",
  source: "vanilla",
  description: "banned-players.json, banned-ips.json and EssentialsX 2.22.0 mutes made with real commands arrive in modl (on a network: made on lobby, copied to the proxy)",
  topologies: EVERY_TOPOLOGY,
  lines: ["latest", "1.21", "legacy", "folia"],
  unavailableOn: ESSENTIALS_ON_FOLIA,
  proxies: { velocity: vanillaBehindProxy(["latest", "1.21", "folia"]), bungeecord: vanillaBehindProxy(["latest", "1.21", "legacy", "folia"]) },
  folders: ["Essentials"],
  jars: [plainJar("essentials", "EssentialsX-2.22.0.jar")],
  cleanupFiles: ["banned-players.json", "banned-ips.json"],
  runningDuringImport: true,
  commands: VANILLA_COMMANDS,
  cleanupCommands: (_serverDir, names) => [`minecraft:pardon ${names.banned}`],
  settled: (serverDir, names) => {
    const user = join(serverDir, "plugins", "Essentials", "userdata", `${offlineUuid(names.muted)}.yml`);
    return existsSync(user) && readFileSync(user, "utf8").includes('muted: true');
  },
  expects: { banReason: "Cheating", muteMillis: MUTE_HOURS * HOUR_MS, warnings: false, kicks: false, ipBans: "unattributed", pardonIssuer: null, lifted: false, history: true },
};

function readFromCopy(plugin: SourcePlugin, store: string): SourcePlugin {
  return {
    ...plugin,
    scenario: `${plugin.scenario.replace(/-import$/, "")}-stopped-import`,
    topologies: PAPER_TOPOLOGIES,
    description: `${plugin.description.split(":")[0] ?? plugin.source}: after the plugin is removed, modl reads a copy of its ${store} files`,
    runningDuringImport: false,
  };
}

const ADVANCEDBAN_CONFIG = "config.yml";
function withMariaDb(config: string, sidecar: MariaDbSidecar): string | null {
  const { host, port, database, username, password } = sidecar.connection;
  const replacements: [RegExp, string][] = [
    [/^UseMySQL: false$/m, "UseMySQL: true"],
    [/^ {2}IP: .*$/m, `  IP: ${host}`],
    [/^ {2}DB-Name: .*$/m, `  DB-Name: ${database}`],
    [/^ {2}Username: .*$/m, `  Username: ${username}`],
    [/^ {2}Password: .*$/m, `  Password: '${password}'`],
    [/^ {2}Port: .*$/m, `  Port: ${port.toString()}`],
  ];
  let written = config;
  for (const [pattern, line] of replacements) {
    if (!pattern.test(written)) return null;
    written = written.replace(pattern, line);
  }
  return written;
}

const ADVANCEDBAN_MARIADB: SourcePlugin = {
  ...ADVANCEDBAN,
  scenario: "advancedban-mariadb-import",
  description: "the real AdvancedBan 2.3.0 on a MariaDB server: punishments made with its commands arrive in modl, read over the network while it runs",
  topologies: PAPER_TOPOLOGIES,
  lines: ["latest", "1.21", "folia"],
  runningDuringImport: true,
  prepare: async (t: ScenarioContext, pluginsDir: string) => {
    await MariaDbSidecar.pull();
    const sidecar = await MariaDbSidecar.start(t.env.state.project, MARIADB_DATABASE, MARIADB_USER);
    const jar = await ensureArtifact("advancedban", t.logger);
    const defaults = await readJarEntry(jar, ADVANCEDBAN_CONFIG);
    t.require(defaults, `${jar} has no ${ADVANCEDBAN_CONFIG}`);
    const config = withMariaDb(defaults, sidecar);
    t.require(config, `the ${ADVANCEDBAN_CONFIG} of ${jar} has no MySQL section to point at MariaDB`);
    const folder = join(pluginsDir, "AdvancedBan");
    mkdirSync(folder, { recursive: true });
    writeFileSync(join(folder, ADVANCEDBAN_CONFIG), config);
  },
  release: async () => MariaDbSidecar.stop(),
};

export const advancedbanImport = pluginImportScenario(ADVANCEDBAN);
export const advancedbanMariaDbImport = pluginImportScenario(ADVANCEDBAN_MARIADB);
export const bansystemStoppedImport = pluginImportScenario(readFromCopy(BANSYSTEM, "SQLite"));
export const libertybansStoppedImport = pluginImportScenario(readFromCopy(LIBERTYBANS, "HSQLDB"));
export const banmanagerStoppedImport = pluginImportScenario(readFromCopy(BANMANAGER, "H2"));
export const libertybansImport = pluginImportScenario(LIBERTYBANS);
export const banmanagerImport = pluginImportScenario(BANMANAGER);
export const bansystemImport = pluginImportScenario(BANSYSTEM);
export const vanillaImport = pluginImportScenario(VANILLA);
