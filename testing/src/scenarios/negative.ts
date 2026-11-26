import { existsSync, mkdirSync, readdirSync, renameSync } from "node:fs";
import { join } from "node:path";
import { readYamlValue } from "../lib/config-files";
import { ConsolePatterns, PACKETEVENTS_REQUIRED_REFUSAL, PlayerCommands, StartupFailurePatterns } from "../lib/contract";
import { runDocker } from "../lib/docker";
import { PANEL_JAVA_VERSION } from "../lib/java-runtime";
import { requestedJava } from "../lib/lines";
import type { LogLine, LogMark } from "../lib/logs";
import { CONTAINER_PORTS } from "../lib/topologies/compose";
import { EMBEDDED_PORT } from "../lib/topologies/modl-config";
import { connectionFile, patchConnection } from "../lib/topologies/server-files";
import { randomSecret } from "../lib/util";
import { defineScenario, type ScenarioContext } from "./framework";
import { expectJoinRefused, memberNames, serverReloads } from "./support";

const RECONFIGURE_TIMEOUT_MS = 420_000;
const NOTICE_TIMEOUT_MS = 90_000;
const HELD_PANEL_PORT = 8090;
const HELD_PORT_NOTICE_TIMEOUT_MS = 240_000;
const HELD_PORT_READY_TIMEOUT_MS = 30_000;
const HELD_PLUGINS = "held-plugins";
const PACKETEVENTS_JAR = /^packetevents-.*\.jar$/;

async function startWithoutReadiness(t: ScenarioContext, server: string): Promise<LogMark> {
  t.quitAllBots();
  const stop = await t.env.stopGracefully(server);
  t.evidence(`${server} stopped`, stop);
  const since = await t.env.log(server).mark();
  await t.env.startExisting(server);
  await t.env.waitForMinecraft(server, since, "none");
  return since;
}

async function awaitLine(t: ScenarioContext, server: string, pattern: RegExp, since: LogMark): Promise<LogLine> {
  const line = await t.env.log(server).waitFor(pattern, { since, timeoutMs: NOTICE_TIMEOUT_MS });
  t.evidence(`${server}: ${pattern.toString()}`, line.text);
  return line;
}

async function expectNoLine(t: ScenarioContext, server: string, name: string, pattern: RegExp, since: LogMark): Promise<void> {
  const lines = await t.env.log(server).find(pattern, since);
  t.check(name, lines.length === 0, lines.map((line) => line.text));
}

async function withRestore(t: ScenarioContext, restoreStep: string, restore: () => Promise<void>, body: () => Promise<void>): Promise<void> {
  try {
    await body();
  } finally {
    await t.step(restoreStep, restore, { timeoutMs: RECONFIGURE_TIMEOUT_MS, allowConsoleErrors: true });
  }
}

function movePacketEvents(serverDir: string, from: string, to: string): string[] {
  const source = join(serverDir, from);
  const target = join(serverDir, to);
  mkdirSync(target, { recursive: true });
  const jars = existsSync(source) ? readdirSync(source).filter((file) => PACKETEVENTS_JAR.test(file)) : [];
  for (const jar of jars) renameSync(join(source, jar), join(target, jar));
  return jars;
}

export const missingPacketEvents = defineScenario({
  name: "missing-packetevents",
  description: "Paper without PacketEvents keeps modl loaded but failed closed: the console repeats what to install, players are refused with a message naming PacketEvents, and modl recovers once it is installed",
  topologies: ["standalone"],
  async run(t: ScenarioContext) {
    const server = t.coreServer;
    const dir = t.env.server(server).dataDir;
    await withRestore(
      t,
      "PacketEvents is reinstalled and modl starts again",
      async () => {
        const restored = movePacketEvents(dir, HELD_PLUGINS, "plugins");
        t.evidence("restored jars", restored);
        const report = await t.restart(server);
        t.evidence("restart", report);
      },
      async () => {
        await t.step(
          "Paper starts without PacketEvents",
          async () => {
            t.quitAllBots();
            await t.env.stopGracefully(server);
            const held = movePacketEvents(dir, "plugins", HELD_PLUGINS);
            t.require(held.length > 0, `no PacketEvents jar found in ${join(dir, "plugins")}`);
            const since = await t.env.log(server).mark();
            await t.env.startExisting(server);
            await t.env.waitForMinecraft(server, since, "none");
            await awaitLine(t, server, ConsolePatterns.missingPacketEvents, since);
            await awaitLine(t, server, ConsolePatterns.packetEventsRemedy, since);
            const refusal = await expectJoinRefused(t, t.playerName("NoPE"), 20_000);
            t.expectMatch("the refusal names the PacketEvents version modl needs", refusal, PACKETEVENTS_REQUIRED_REFUSAL);
            await expectNoLine(t, server, "modl did not boot", ConsolePatterns.pluginBooted, since);
          },
          { timeoutMs: RECONFIGURE_TIMEOUT_MS, allowConsoleErrors: true },
        );
      },
    );
  },
});

export const panelPortInUse = defineScenario({
  name: "panel-port-in-use",
  description: "an embedded.port equal to the game port, or bound by another process, disables modl with a clear message instead of a stack trace",
  topologies: ["standalone"],
  async run(t: ScenarioContext) {
    const server = t.coreServer;
    const handle = t.env.server(server);
    await withRestore(
      t,
      `embedded.port is set back to ${EMBEDDED_PORT} and the panel starts`,
      async () => {
        patchConnection(handle.dataDir, { "embedded.port": EMBEDDED_PORT });
        t.evidence("restart", await t.restart(server));
      },
      async () => {
        await t.step(
          `Paper refuses embedded.port ${CONTAINER_PORTS.game}, the game port`,
          async () => {
            patchConnection(handle.dataDir, { "embedded.port": CONTAINER_PORTS.game });
            const since = await startWithoutReadiness(t, server);
            const notice = await awaitLine(t, server, ConsolePatterns.panelPortIsGamePort, since);
            t.expectMatch("the message names the port", notice.text, new RegExp(`embedded\\.port is ${CONTAINER_PORTS.game}\\b`));
            await expectNoLine(t, server, "modl did not boot", ConsolePatterns.pluginBooted, since);
          },
          { timeoutMs: RECONFIGURE_TIMEOUT_MS, allowConsoleErrors: true },
        );
        if (!serverReloads(t)) return;
        await t.step(
          `a reload with embedded.port ${HELD_PANEL_PORT} held by another process says the port is in use`,
          async () => {
            await holdPort(t, handle.container, HELD_PANEL_PORT);
            patchConnection(handle.dataDir, { "embedded.port": HELD_PANEL_PORT });
            const since = await t.env.log(server).mark();
            await t.sendReload(server);
            const notice = await t.env.log(server).waitFor(ConsolePatterns.panelPortInUse, { since, timeoutMs: HELD_PORT_NOTICE_TIMEOUT_MS });
            t.evidence(`${server}: ${ConsolePatterns.panelPortInUse.toString()}`, notice.text);
            t.expectMatch("the message names the port", notice.text, new RegExp(`Port ${HELD_PANEL_PORT}\\b`));
            t.expectMatch("the message names the setting to change", notice.text, /embedded\.port/);
            await expectNoLine(t, server, "modl did not boot", ConsolePatterns.pluginBooted, since);
          },
          { timeoutMs: RECONFIGURE_TIMEOUT_MS, allowConsoleErrors: true },
        );
      },
    );
  },
});

async function holdPort(t: ScenarioContext, container: string, port: number): Promise<void> {
  await runDocker(["exec", "-d", container, "java", "-Xmx32m", "-m", "jdk.httpserver", "-b", "0.0.0.0", "-p", String(port), "-d", "/tmp"]);
  const listening = `:${port.toString(16).toUpperCase().padStart(4, "0")} 0+:0000 0A`;
  const deadline = Date.now() + HELD_PORT_READY_TIMEOUT_MS;
  while (Date.now() < deadline) {
    const probe = await runDocker(["exec", container, "grep", "-qiE", listening, "/proc/net/tcp", "/proc/net/tcp6"], { allowFailure: true });
    if (probe.code === 0) return;
    await t.sleep(500);
  }
  t.fail(`nothing listens on port ${port.toString()} in ${container}`);
}

function wrongKey(): string {
  return `wrong-${randomSecret(16)}`;
}

async function awaitRepeatedLine(t: ScenarioContext, server: string, pattern: RegExp, since: LogMark, times: number): Promise<LogLine[]> {
  const deadline = Date.now() + NOTICE_TIMEOUT_MS;
  let lines: LogLine[] = [];
  while (Date.now() < deadline) {
    lines = await t.env.log(server).find(pattern, since);
    if (lines.length >= times) return lines;
    await t.sleep(2000);
  }
  return t.fail(`${server} printed ${pattern.toString()} ${lines.length} time(s), expected at least ${times}`);
}

async function joinMember(t: ScenarioContext, member: string): Promise<boolean> {
  const player = await t.bot(t.playerName("Mbr"));
  const switches = player.serverSwitches;
  player.chat(PlayerCommands.switchServer(member));
  await player.awaitServerSwitch(switches);
  return player.connected;
}

async function wrongMemberKey(t: ScenarioContext): Promise<void> {
  const member = memberNames(t).at(-1);
  t.require(member, "the topology has no server behind the proxy");
  const dir = t.env.server(member).dataDir;
  const serverKey = readYamlValue(connectionFile(dir), "remote.api-key");
  t.require(typeof serverKey === "string" && serverKey !== "", `${member} has no remote.api-key to restore`);
  await withRestore(
    t,
    `${member} gets its server key back and rejoins the modl network`,
    async () => {
      patchConnection(dir, { "remote.api-key": serverKey });
      t.evidence("restart", await t.restart(member));
    },
    async () => {
      await t.step(
        `${member} starts with a wrong remote.api-key and keeps letting players in`,
        async () => {
          patchConnection(dir, { "remote.api-key": wrongKey() });
          const since = await startWithoutReadiness(t, member);
          const rejected = await awaitRepeatedLine(t, member, ConsolePatterns.memberKeyRejected, since, 2);
          t.expectMatch("the warning names the setting and where to create a key", rejected[0]?.text ?? "", /remote\.api-key.*Add server/);
          await awaitLine(t, member, ConsolePatterns.memberPlayersCanJoin, since);
          t.check(`a player still reaches ${member} through the proxy`, await joinMember(t, member));
          await expectNoLine(t, member, `${member} did not join the modl network`, ConsolePatterns.memberNetworkConnected, since);
        },
        { timeoutMs: RECONFIGURE_TIMEOUT_MS, allowConsoleErrors: true },
      );
    },
  );
}

async function wrongRemoteKey(t: ScenarioContext): Promise<void> {
  const server = t.coreServer;
  const dir = t.env.server(server).dataDir;
  const apiKey = readYamlValue(connectionFile(dir), "remote.api-key");
  t.require(typeof apiKey === "string" && apiKey !== "", `${server} has no remote.api-key to restore`);
  await withRestore(
    t,
    `${server} gets the right API key back and connects`,
    async () => {
      patchConnection(dir, { "remote.api-key": apiKey });
      t.evidence("restart", await t.restart(server));
    },
    async () => {
      await t.step(
        `${server} starts with a wrong remote.api-key`,
        async () => {
          patchConnection(dir, { "remote.api-key": wrongKey() });
          const since = await startWithoutReadiness(t, server);
          const rejected = await awaitLine(t, server, ConsolePatterns.remoteKeyRejected, since);
          t.expectMatch("the message says which setting is wrong", rejected.text, /remote\.api-key/);
          const refusal = await expectJoinRefused(t, t.playerName("Key"), 20_000);
          t.evidence("join refused", refusal);
        },
        { timeoutMs: RECONFIGURE_TIMEOUT_MS, allowConsoleErrors: true },
      );
    },
  );
}

export const wrongMemberApiKey = defineScenario({
  name: "wrong-member-key",
  description: "a server behind the proxy with a wrong remote.api-key repeats a warning that names the setting, still lets players in through the proxy, and rejoins the network once fixed",
  topologies: ["network", "network-bungee"],
  run: wrongMemberKey,
});

export const wrongRemoteApiKey = defineScenario({
  name: "wrong-remote-key",
  description: "a remote-mode server with a wrong remote.api-key says the API key was rejected and which setting to fix, refuses logins, and recovers once fixed",
  topologies: ["remote"],
  run: wrongRemoteKey,
});

export const embeddedNeedsJava21 = defineScenario({
  name: "embedded-needs-java-21",
  description:
    "mode: embedded on a Java 8 server keeps modl loaded but failed closed, names the Java 21 requirement and mode: remote as the fix, refuses players, and recovers once mode: remote is back",
  topologies: ["remote"],
  lines: ["legacy"],
  unsupportedBy: (options) => {
    const java = requestedJava(options);
    return java < PANEL_JAVA_VERSION ? null : `Paper runs on Java ${java} (--java), which can host the built-in panel`;
  },
  async run(t: ScenarioContext) {
    const server = t.coreServer;
    const dir = t.env.server(server).dataDir;
    const java = requestedJava(t.env.state.options);
    await withRestore(
      t,
      "mode: remote is restored and modl connects again",
      async () => {
        patchConnection(dir, { mode: "remote" });
        t.evidence("restart", await t.restart(server));
      },
      async () => {
        await t.step(
          `Paper on Java ${java} starts with mode: embedded`,
          async () => {
            patchConnection(dir, { mode: "embedded" });
            const since = await startWithoutReadiness(t, server);
            const reason = await awaitLine(t, server, ConsolePatterns.embeddedNeedsJava, since);
            t.check("the message names Java 21", ConsolePatterns.embeddedNeedsJava.exec(reason.text)?.[1] === String(PANEL_JAVA_VERSION), reason.text);
            const remedy = await awaitLine(t, server, StartupFailurePatterns.remedy, since);
            t.expectMatch("the remedy points to mode: remote", remedy.text, /mode: remote/);
            const refusal = await expectJoinRefused(t, t.playerName("Java"), 20_000);
            t.evidence("join refused", refusal);
            await expectNoLine(t, server, "the built-in panel did not start", ConsolePatterns.panelStarted, since);
            await expectNoLine(t, server, "modl did not boot", ConsolePatterns.pluginBooted, since);
          },
          { timeoutMs: RECONFIGURE_TIMEOUT_MS, allowConsoleErrors: true },
        );
      },
    );
  },
});
