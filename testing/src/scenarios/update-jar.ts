import { rmSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { ConsolePatterns, UpdateEnums, UpdateMessages } from "../lib/contract";
import { containerState } from "../lib/docker";
import { HttpStatus, jsonField, jsonString } from "../lib/http";
import { releaseVersion, type ReleaseName } from "../lib/release-fixtures";
import { sleep } from "../lib/util";
import { defineScenario, type ScenarioContext } from "./framework";
import {
  awaitConsole,
  awaitRolledBack,
  awaitRunning,
  awaitStatus,
  awaitStatusAcrossRestore,
  currentVersion,
  dataDirectory,
  expectDataIntact,
  expectSessionKept,
  expectRolledBackWithRestore,
  expectSessionMatchesRestore,
  expectSessionRevokedByRestore,
  install,
  installOffered,
  needsFixtureAndJarBackend,
  offer,
  replaceServerJar,
  RESTART_TIMEOUT_MS,
  seedBan,
  UPDATE_TIMEOUT_MS,
  updateFile,
  updateStatus,
  upgradeBackups,
  withUpdateLab,
} from "./update-support";

const BACKEND = "backend";
const SERVERS = [BACKEND, "paper"] as const;
const STEP_OPTIONS = { timeoutMs: UPDATE_TIMEOUT_MS + RESTART_TIMEOUT_MS, allowConsoleErrors: true } as const;

async function awaitExited(t: ScenarioContext): Promise<void> {
  const { container } = t.env.server(BACKEND);
  const deadline = Date.now() + RESTART_TIMEOUT_MS;
  while ((await containerState(container)).status === "running") {
    if (Date.now() > deadline) t.fail("the modl server kept running");
    await sleep(1000);
  }
}

async function startManually(t: ScenarioContext, name: ReleaseName): Promise<string> {
  await t.env.stopGracefully(BACKEND);
  await replaceServerJar(t, name);
  const since = await t.env.log(BACKEND).mark();
  await t.env.startExisting(BACKEND);
  return since;
}

export const updateJar = defineScenario({
  name: "update-jar",
  description: "Update now installs a signed patch of the server jar, keeps the data and survives a clean stop",
  topologies: ["remote"],
  unsupportedBy: needsFixtureAndJarBackend,
  async run(t: ScenarioContext) {
    await withUpdateLab(t, SERVERS, async (lab) => {
      const banned = await t.step("a ban is written before the update", async () => seedBan(t));

      await t.step(
        "Update now installs the patch",
        async () => {
          await installOffered(t, lab.mirror, "patch");
          await awaitRunning(t, "patch");
          t.check("the commit is recorded", updateFile(t, "committed")?.includes(releaseVersion("patch")) === true);
          t.check("nothing is pending", updateFile(t, "pending") === null);
          t.check("a pre-update backup exists", upgradeBackups(t).length > 0);
        },
        STEP_OPTIONS,
      );

      await t.step("the data is intact and Paper reconnects", async () => {
        await expectSessionKept(t);
        await expectDataIntact(t, banned);
      }, STEP_OPTIONS);

      await t.step(
        "a clean stop and the next start keep the patch",
        async () => {
          const restarted = await t.restart(BACKEND);
          t.check("the stop was clean", restarted.stop?.graceful === true, restarted.stop);
          await awaitRunning(t, "patch");
        },
        STEP_OPTIONS,
      );
    });
  },
});

export const updateJarRollback = defineScenario({
  name: "update-jar-rollback",
  description: "a broken release returns the server jar to the old version and restores the database",
  topologies: ["remote"],
  unsupportedBy: needsFixtureAndJarBackend,
  async run(t: ScenarioContext) {
    await withUpdateLab(t, SERVERS, async (lab) => {
      const banned = await t.step("a ban is written before the update", async () => seedBan(t));

      await t.step(
        "the broken release is rolled back",
        async () => {
          await installOffered(t, lab.mirror, "broken");
          const settled = await awaitRolledBack(t);
          expectRolledBackWithRestore(t, settled);
          t.check("the failed update was never committed", updateFile(t, "committed") === null);
          t.check("nothing is pending", updateFile(t, "pending") === null);
        },
        STEP_OPTIONS,
      );

      await t.step("the data is intact and players can join", async () => expectDataIntact(t, banned), STEP_OPTIONS);
    });
  },
});

export const updateJarInterrupted = defineScenario({
  name: "update-jar-interrupted",
  description: "a SIGKILL during an update is finished or rolled back by the next start, never both",
  topologies: ["remote"],
  unsupportedBy: needsFixtureAndJarBackend,
  async run(t: ScenarioContext) {
    await withUpdateLab(t, SERVERS, async (lab) => {
      const banned = await t.step("a ban is written before the update", async () => seedBan(t));

      await t.step(
        "the process tree is killed during the update",
        async () => {
          const since = await t.env.log(BACKEND).mark();
          await installOffered(t, lab.mirror, "patch");
          await awaitConsole(t, BACKEND, UpdateMessages.backingUp, since);
          t.evidence("kill", await t.kill(BACKEND));
        },
        STEP_OPTIONS,
      );

      await t.step(
        "the next start ends in exactly one outcome",
        async () => {
          await t.start(BACKEND);
          const settled = await awaitStatusAcrossRestore(t, "a settled update", () => updateFile(t, "pending") === null);
          const { status } = settled;
          const committed = updateFile(t, "committed") !== null;
          const rolledBack = updateFile(t, "last-failure") !== null;
          t.check("the update finished or rolled back, never both", committed !== rolledBack, { committed, rolledBack });
          const expected = releaseVersion(committed ? "patch" : "base");
          t.check(`modl ${expected} runs`, currentVersion(status) === expected, status);
          expectSessionMatchesRestore(t, settled);
        },
        STEP_OPTIONS,
      );

      await t.step("the data is intact", async () => expectDataIntact(t, banned), STEP_OPTIONS);
    });
  },
});

export const updateBackupFailure = defineScenario({
  name: "update-backup-failure",
  description: "an unwritable backups folder refuses Update now and aborts a manual upgrade without changing anything",
  topologies: ["remote"],
  unsupportedBy: needsFixtureAndJarBackend,
  async run(t: ScenarioContext) {
    await withUpdateLab(t, SERVERS, async (lab) => {
      const backups = join(dataDirectory(t), "backups");

      await t.step(
        "Update now refuses and the old version keeps serving",
        async () => {
          await offer(t, lab.mirror, "patch");
          rmSync(backups, { recursive: true, force: true });
          writeFileSync(backups, "not a folder\n");
          const started = await install(t, "patch");
          t.require(started.status === HttpStatus.accepted, `the install returned ${started.status}`);
          const status = await awaitStatus(
            t,
            "the refused install",
            (current) => jsonString(jsonField(current, "install"), "phase") === UpdateEnums.installFailed,
          );
          t.check("the backups folder is named", jsonString(jsonField(status, "install"), "failure") === UpdateEnums.backupDirectory, status);
          t.check("the old version serves", currentVersion(status) === releaseVersion("base"), status);
          t.check("nothing is staged", updateFile(t, "pending") === null);
        },
        STEP_OPTIONS,
      );

      await t.step(
        "a replaced jar aborts with the remedy",
        async () => {
          const since = await startManually(t, "patch");
          await awaitConsole(t, BACKEND, UpdateMessages.backupFailed, since);
          await awaitExited(t);
          t.check("nothing is pending", updateFile(t, "pending") === null);
        },
        STEP_OPTIONS,
      );

      await t.step(
        "the old jar still starts on the unchanged database",
        async () => {
          rmSync(backups, { force: true });
          await startManually(t, "base");
          await t.env.waitForPanel();
          t.check("the old version runs", currentVersion(await updateStatus(t)) === releaseVersion("base"));
        },
        STEP_OPTIONS,
      );
    });
  },
});

export const updateManualGuard = defineScenario({
  name: "update-manual-guard",
  description: "a manual upgrade backs up, refuses a downgrade, and the old jar undoes a failed upgrade by itself",
  topologies: ["remote"],
  unsupportedBy: needsFixtureAndJarBackend,
  async run(t: ScenarioContext) {
    await withUpdateLab(t, SERVERS, async (lab) => {
      const banned = await t.step("a ban is written before the upgrade", async () => seedBan(t));

      await t.step(
        "the data before the upgrade is kept for the failed upgrade",
        async () => {
          await lab.stopAll();
          lab.keepUpgradeBase(BACKEND);
          await lab.startAll();
        },
        STEP_OPTIONS,
      );

      await t.step(
        "a replaced jar backs up and upgrades",
        async () => {
          const since = await startManually(t, "patch");
          await awaitConsole(t, BACKEND, UpdateMessages.updated, since);
          await awaitRunning(t, "patch");
          t.check("a pre-update backup exists", upgradeBackups(t).length > 0);
        },
        STEP_OPTIONS,
      );

      await t.step(
        "a downgrade is refused",
        async () => {
          const since = await startManually(t, "base");
          await awaitConsole(t, BACKEND, UpdateMessages.downgradeRefused, since);
          await awaitExited(t);
        },
        STEP_OPTIONS,
      );

      await t.step(
        "a broken manual upgrade stops",
        async () => {
          await lab.restoreUpgradeBase(BACKEND);
          const since = await startManually(t, "broken");
          await awaitConsole(t, BACKEND, UpdateMessages.didNotStart, since);
          await awaitExited(t);
        },
        STEP_OPTIONS,
      );

      await t.step(
        "the old jar restores the database by itself",
        async () => {
          const since = await startManually(t, "base");
          await awaitConsole(t, BACKEND, UpdateMessages.restoring, since);
          await t.env.waitForPanel();
          await t.env.log(BACKEND).waitFor(ConsolePatterns.backendListening, { since, timeoutMs: RESTART_TIMEOUT_MS });
          await expectSessionRevokedByRestore(t);
          await expectDataIntact(t, banned);
        },
        STEP_OPTIONS,
      );
    });
  },
});
