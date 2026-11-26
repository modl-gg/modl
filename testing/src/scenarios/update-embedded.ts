import { ConsolePatterns, UpdateEnums, UpdateErrors, UpdateMessages } from "../lib/contract";
import { apiErrorCode, HttpStatus, jsonString } from "../lib/http";
import { releaseVersion } from "../lib/release-fixtures";
import { defineScenario, type ScenarioContext } from "./framework";
import { serverReloads } from "./support";
import {
  awaitConsole,
  awaitRolledBack,
  awaitRunning,
  expectDataIntact,
  expectSessionKept,
  expectRolledBackWithRestore,
  install,
  installOffered,
  needsUpdateFixture,
  offer,
  RESTART_TIMEOUT_MS,
  seedBan,
  UPDATE_TIMEOUT_MS,
  updateFile,
  updateStatus,
  withUpdateLab,
} from "./update-support";

const STEP_OPTIONS = { timeoutMs: UPDATE_TIMEOUT_MS + RESTART_TIMEOUT_MS, allowConsoleErrors: true } as const;

function panelHostFirst(t: ScenarioContext): string[] {
  const host = t.coreServer;
  return [host, ...t.env.serverNames().filter((name) => name !== host)];
}

async function reloadOrRestart(t: ScenarioContext, host: string): Promise<void> {
  if (t.topology !== "standalone" || !serverReloads(t)) {
    await t.restart(host);
    return;
  }
  t.quitAllBots();
  const log = t.env.log(host);
  const since = await log.mark();
  await t.sendReload(host);
  await log.waitFor(ConsolePatterns.bukkitReloadComplete, { since, timeoutMs: RESTART_TIMEOUT_MS, requireRunning: true });
  await t.env.waitForReload(host, since);
}

export const updateEmbedded = defineScenario({
  name: "update-embedded",
  description: "Update now swaps a patch of the built-in panel in place; another line needs the plugin jar",
  topologies: ["standalone", "network"],
  unsupportedBy: needsUpdateFixture,
  async run(t: ScenarioContext) {
    const host = t.coreServer;
    await withUpdateLab(t, panelHostFirst(t), async (lab) => {
      await t.step("another line needs the plugin jar", async () => {
        const release = await offer(t, lab.mirror, "minor");
        t.check("the plugin jar is required", jsonString(release, "installAvailability") === UpdateEnums.pluginJarRequired, release);
        t.check("the deployment is embedded", jsonString(await updateStatus(t), "deployment") === UpdateEnums.embedded);
        const refused = await install(t, "minor");
        t.check(
          "Update now is refused",
          refused.status === HttpStatus.conflict && apiErrorCode(refused) === UpdateErrors.notInstallable,
          refused.json,
        );
      });

      const banned = await t.step("a ban is written before the update", async () => seedBan(t));
      const online = await t.step("a player is online", async () => t.bot(t.playerName("Stay")));

      await t.step(
        "the patch replaces the panel in place",
        async () => {
          const since = await t.env.log(host).mark();
          await installOffered(t, lab.mirror, "patch");
          await awaitRunning(t, "patch");
          await awaitConsole(t, host, UpdateMessages.restarted, since);
          t.check("the player stayed online", online.connected);
          const boots = await t.env.log(host).find(ConsolePatterns.pluginBooted, since);
          t.check("the plugin did not start again", boots.length === 0, boots.map((line) => line.text));
          t.check("the commit is recorded", updateFile(t, "committed")?.includes(releaseVersion("patch")) === true);
        },
        STEP_OPTIONS,
      );

      await t.step(
        "the plugin keeps working with the same port and key",
        async () => {
          await expectSessionKept(t);
          await expectDataIntact(t, banned);
          await seedBan(t);
        },
        STEP_OPTIONS,
      );

      await t.step(
        "a later reload or restart keeps the patch",
        async () => {
          await reloadOrRestart(t, host);
          await awaitRunning(t, "patch");
        },
        STEP_OPTIONS,
      );
    });
  },
});

export const updateEmbeddedRollback = defineScenario({
  name: "update-embedded-rollback",
  description: "a broken payload returns the built-in panel to the packed version with its data and without a stuck lock",
  topologies: ["standalone"],
  unsupportedBy: needsUpdateFixture,
  async run(t: ScenarioContext) {
    const host = t.coreServer;
    await withUpdateLab(t, panelHostFirst(t), async (lab) => {
      const banned = await t.step("a ban is written before the update", async () => seedBan(t));
      const since = await t.env.log(host).mark();

      await t.step(
        "the broken payload is rolled back to the packed one",
        async () => {
          await installOffered(t, lab.mirror, "broken");
          const settled = await awaitRolledBack(t);
          expectRolledBackWithRestore(t, settled);
          await awaitConsole(t, host, UpdateMessages.returning, since);
          t.check("nothing is pending", updateFile(t, "pending") === null);
        },
        STEP_OPTIONS,
      );

      await t.step("the data is intact", async () => expectDataIntact(t, banned), STEP_OPTIONS);

      await t.step(
        "no lock is stuck",
        async () => {
          const waits = await t.env.log(host).find(ConsolePatterns.databaseLockWait, since);
          t.check("no start waited for the database lock", waits.length === 0, waits.map((line) => line.text));
          await t.restart(host);
          await awaitRunning(t, "base");
        },
        STEP_OPTIONS,
      );
    });
  },
});
