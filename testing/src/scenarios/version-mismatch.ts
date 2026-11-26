import { CompatibilityMessages, ConsolePatterns, PlayerCommands } from "../lib/contract";
import { replaceServerKey } from "../lib/server-keys";
import { CONTAINER_PORTS } from "../lib/topologies/compose";
import { patchConnection } from "../lib/topologies/server-files";
import { defineScenario, type ScenarioContext } from "./framework";
import { expectJoinRefused } from "./support";
import {
  awaitConsole,
  needsFixtureAndJarBackend,
  needsUpdateFixture,
  replacePluginJar,
  replaceServerJar,
  RESTART_TIMEOUT_MS,
  UPDATE_TIMEOUT_MS,
  withUpdateLab,
} from "./update-support";

const BACKEND = "backend";
const PAPER = "paper";
const PROXY = "proxy";
const MEMBER = "survival";
const STEP_OPTIONS = { timeoutMs: UPDATE_TIMEOUT_MS + RESTART_TIMEOUT_MS, allowConsoleErrors: true } as const;

async function startWithPlugin(t: ScenarioContext, server: string, plugin: "base" | "minor"): Promise<string> {
  t.quitAllBots();
  await t.env.stopGracefully(server);
  await replacePluginJar(t, server, plugin);
  const since = await t.env.log(server).mark();
  await t.env.startExisting(server);
  await t.env.waitForMinecraft(server, since, "none");
  return since;
}

export const versionMismatch = defineScenario({
  name: "version-mismatch",
  description: "older and newer plugins are denied with the remedy, and a backend rollback heals a running plugin without a restart",
  topologies: ["remote"],
  unsupportedBy: needsFixtureAndJarBackend,
  async run(t: ScenarioContext) {
    await withUpdateLab(t, [BACKEND, PAPER], async (lab) => {
      const since = await t.env.log(PAPER).mark();

      await t.step(
        "a newer server line gates the running older plugin",
        async () => {
          await t.env.stopGracefully(BACKEND);
          await replaceServerJar(t, "minor");
          await t.env.startExisting(BACKEND);
          await t.env.waitForPanel();
          await awaitConsole(t, PAPER, CompatibilityMessages.mismatch, since);
          await awaitConsole(t, PAPER, CompatibilityMessages.pluginOlder, since);
          t.expectMatch("players get the update message", await expectJoinRefused(t, t.playerName("Old")), CompatibilityMessages.playerRefusal);
        },
        STEP_OPTIONS,
      );

      await t.step(
        "the older server heals the plugin without a restart",
        async () => {
          await t.env.stopGracefully(BACKEND);
          await lab.restoreData(BACKEND);
          await replaceServerJar(t, "base");
          await t.env.startExisting(BACKEND);
          await t.env.waitForPanel();
          await awaitConsole(t, PAPER, CompatibilityMessages.acceptedAgain, since);
          const boots = await t.env.log(PAPER).find(ConsolePatterns.pluginBooted, since);
          t.check("the plugin did not start again", boots.length === 0, boots.map((line) => line.text));
          const player = await t.bot(t.playerName("Heal"));
          t.check("players can join again", player.connected);
        },
        STEP_OPTIONS,
      );

      await t.step(
        "a newer plugin is denied at startup",
        async () => {
          const startedAt = await startWithPlugin(t, PAPER, "minor");
          await awaitConsole(t, PAPER, CompatibilityMessages.serverOlder, startedAt);
          t.expectMatch("players get the update message", await expectJoinRefused(t, t.playerName("New")), CompatibilityMessages.playerRefusal);
        },
        STEP_OPTIONS,
      );
    });
  },
});

async function createMemberKey(t: ScenarioContext): Promise<string> {
  const created = await replaceServerKey(t.instance, MEMBER);
  t.evidence("server key", { name: created.name, id: created.id });
  return created.secret;
}

async function joinMember(t: ScenarioContext): Promise<void> {
  const player = await t.bot(t.playerName("Mem"));
  const switches = player.serverSwitches;
  player.chat(PlayerCommands.switchServer(MEMBER));
  await player.awaitServerSwitch(switches);
  t.check(`the player reached ${MEMBER}`, player.connected);
}

export const memberVersionMismatch = defineScenario({
  name: "member-version-mismatch",
  description: "a server behind the proxy with another line degrades while players still join, and recovers after the jar fix",
  topologies: ["network"],
  unsupportedBy: needsUpdateFixture,
  async run(t: ScenarioContext) {
    await withUpdateLab(t, [PROXY, "lobby", MEMBER], async () => {
      await t.step(
        `${MEMBER} joins the network with a server key`,
        async () => {
          const secret = await createMemberKey(t);
          t.quitAllBots();
          await t.env.stopGracefully(MEMBER);
          patchConnection(t.env.server(MEMBER).dataDir, {
            mode: "behind-proxy",
            "remote.url": `http://${PROXY}:${CONTAINER_PORTS.http}`,
            "remote.api-key": secret,
          });
          const since = await t.env.log(MEMBER).mark();
          await t.env.startExisting(MEMBER);
          await t.env.waitForMinecraft(MEMBER, since, "none");
          await awaitConsole(t, MEMBER, ConsolePatterns.memberNetworkConnected, since);
        },
        STEP_OPTIONS,
      );

      await t.step(
        "another line degrades the member and players still join",
        async () => {
          const since = await startWithPlugin(t, MEMBER, "minor");
          await awaitConsole(t, MEMBER, ConsolePatterns.memberDegraded, since);
          await awaitConsole(t, MEMBER, CompatibilityMessages.serverOlder, since);
          await joinMember(t);
        },
        STEP_OPTIONS,
      );

      await t.step(
        "the fixed jar recovers",
        async () => {
          const since = await startWithPlugin(t, MEMBER, "base");
          await awaitConsole(t, MEMBER, ConsolePatterns.memberNetworkConnected, since);
          await joinMember(t);
        },
        STEP_OPTIONS,
      );
    });
  },
});
