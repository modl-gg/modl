import { ConsoleCommands, ConsolePatterns, Endpoints, ImportMessages } from "../lib/contract";
import { HttpStatus, jsonArray, jsonField, jsonString } from "../lib/http";
import { createSeed, importFilesLeft, installLiteBansFixture, removeLiteBansFixture, type LiteBansSeed } from "../lib/litebans-fixture";
import { defineScenario, type ScenarioContext } from "./framework";
import { awaitImportEnded, ensureSetupFinished, importedIdOf, isNoteOfKind, latestImportJob, latestImportStatus, playerProfile, typeOrdinalOf } from "./import-support";
import { expectJoinRefused } from "./support";

const IMPORT_TIMEOUT_MS = 600_000;
const RESTART_TIMEOUT_MS = 420_000;
const POLL_MS = 1000;
const PREVIEW_IDLE_TIMEOUT_MS = 60_000;

async function previewWhenIdle(t: ScenarioContext): Promise<void> {
  const answers = new RegExp(`${ImportMessages.previewReady.source}|${ImportMessages.busyHere.source}`);
  const deadline = Date.now() + PREVIEW_IDLE_TIMEOUT_MS;
  while (Date.now() < deadline) {
    const reply = await t.consoleExpect(ConsoleCommands.importPreview, answers, { timeoutMs: 60_000 });
    if (reply.matched !== null && ImportMessages.previewReady.test(reply.matched)) return;
    await t.sleep(POLL_MS);
  }
  t.fail(`the previous import still kept this server busy after ${PREVIEW_IDLE_TIMEOUT_MS.toString()}ms`);
}

async function startImport(t: ScenarioContext, choices: string): Promise<string> {
  await previewWhenIdle(t);
  await t.consoleExpect(ConsoleCommands.importConfirm(choices), ImportMessages.started, { timeoutMs: 60_000 });
  const id = jsonString(await latestImportJob(t), "id");
  t.require(id, "the started import has no id");
  return id;
}

async function punishmentOf(t: ScenarioContext, uuid: string, id: string): Promise<{ count: number; text: string }> {
  const found = jsonArray(await playerProfile(t, uuid), "punishments").filter((punishment) => importedIdOf(punishment) === id);
  return { count: found.length, text: JSON.stringify(found) };
}

async function verifyFirstImport(t: ScenarioContext, seed: LiteBansSeed): Promise<void> {
  const banned = await punishmentOf(t, seed.banned.uuid, seed.banned.banId);
  const expired = await punishmentOf(t, seed.expired.uuid, seed.expired.banId);
  const lifted = await punishmentOf(t, seed.lifted.uuid, seed.lifted.muteId);
  const scoped = await punishmentOf(t, seed.scoped.uuid, seed.scoped.banId);
  const withoutHistory = await punishmentOf(t, seed.withoutHistory.uuid, seed.withoutHistory.banId);
  t.check("the active ban arrived", banned.count === 1, banned);
  const profile = await playerProfile(t, seed.banned.uuid);
  const notes = jsonArray(profile, "notes");
  t.check("the warning became a warning note", notes.some((note) => jsonString(note, "id") === seed.banned.warningId
    && isNoteOfKind(note, "WARNING")), notes);
  const kick = jsonArray(profile, "punishments").find((punishment) => importedIdOf(punishment) === seed.banned.kickId);
  t.check("the kick arrived as a kick", kick !== undefined && typeOrdinalOf(kick) === 0, kick);
  t.check("a player without history arrived", withoutHistory.count === 1, withoutHistory);
  t.check("an expired ban is not pardoned", expired.count === 1 && !/PARDON/i.test(expired.text), expired);
  t.check("a lifted mute is pardoned", lifted.count === 1 && /PARDON/i.test(lifted.text), lifted);
  t.check("non-ASCII text survived", lifted.text.includes(seed.lifted.reason), lifted);
  t.check("the scoped ban was left out", scoped.count === 0, scoped);
  t.check("old IP history is kept", jsonField(await latestImportStatus(t), "ipHistoryKeptBefore") !== undefined);
  await expectJoinRefused(t, seed.banned.name);
}

export const litebansImport = defineScenario({
  name: "litebans-import",
  description: "the console-only LiteBans import: refusals, preview, choices, a restart while processing, one file per job, a second run and cancel",
  topologies: ["standalone", "remote"],
  lines: ["latest"],
  async run(t: ScenarioContext) {
    const core = t.coreServer;
    const serverDir = t.env.server(core).dataDir;

    await t.step("the modl setup is finished", async () => ensureSetupFinished(t), { timeoutMs: 120_000 });

    await t.step(
      "without LiteBans the command refuses",
      async () => {
        removeLiteBansFixture(serverDir);
        await t.restart(core);
        await t.consoleExpect(ConsoleCommands.importPreview, ImportMessages.liteBansMissing);
      },
      { timeoutMs: RESTART_TIMEOUT_MS },
    );

    const seed = await t.step(
      "the LiteBans fixture is installed with a seed beyond 100 MB",
      async () => {
        const created = createSeed((prefix) => t.playerName(prefix));
        installLiteBansFixture(serverDir, created.sql);
        await t.restart(core);
        return created.seed;
      },
      { timeoutMs: RESTART_TIMEOUT_MS },
    );

    await t.step("players and RCON are refused", async () => {
      const bot = await t.bot(t.playerName("Imp"));
      const replies = await bot.runCommand(`/${ConsoleCommands.importPreview}`, { expect: ImportMessages.localConsoleOnly, timeoutMs: 8000 });
      t.expectMatch("a player is refused", replies.map((message) => message.text), ImportMessages.localConsoleOnly);
      const before = await t.env.log(core).mark();
      t.evidence("RCON reply", await t.env.console(core).rcon(ConsoleCommands.importPreview));
      await t.sleep(3000);
      const printed = await t.env.log(core).text(before);
      t.check("RCON started no preview", !printed.some((line) => ImportMessages.previewReady.test(line)), printed);
    });

    const first = await t.step("a preview creates no job and confirm needs both choices", async () => {
      const earlierJob = jsonString(await latestImportJob(t), "id");
      await t.consoleExpect(ConsoleCommands.importPreview, ImportMessages.previewReady, { timeoutMs: 60_000 });
      await t.consoleExpect(ConsoleCommands.importConfirm(""), ImportMessages.choicesRequired);
      t.check("no job was created", jsonString(await latestImportJob(t), "id") === earlierJob);
      const since = await t.env.log(core).mark();
      return { id: await startImport(t, "skip keep"), since };
    }, { ignoreProblems: [ConsolePatterns.importUnreadablePlayer] });

    await t.step(
      "a restart of the data server while processing still completes the same import",
      async () => {
        await t.env.log(core).waitFor(ImportMessages.processingStarted, { since: first.since, timeoutMs: IMPORT_TIMEOUT_MS });
        const interruptedWhile = jsonString(await latestImportJob(t), "status");
        t.evidence("status when restarted", interruptedWhile);
        await t.restart(t.env.state.dataServer);
        await t.instance.waitHealthy(RESTART_TIMEOUT_MS);
        const job = await awaitImportEnded(t, first.id, IMPORT_TIMEOUT_MS);
        t.evidence("job", job);
        t.check("the import completed", jsonString(job, "status") === "completed", job);
        t.check("the unreadable player was counted", Number(jsonField(job, "exportFailed")) === 1, job);
        t.check("the scoped punishment was counted", Number(jsonField(job, "exportSkipped")) >= 1, job);
      },
      { timeoutMs: IMPORT_TIMEOUT_MS + RESTART_TIMEOUT_MS, allowConsoleErrors: true },
    );

    await t.step(
      "the import took one file and its data is right",
      async () => {
        const second = await t.instance.apiWithCredentials(Endpoints.importFile(first.id), {
          method: "PUT",
          auth: "api-key",
          headers: { "Content-Type": "application/octet-stream" },
        });
        t.check("a second file is refused", second.status === HttpStatus.conflict, second.text);
        await t.consoleExpect(ConsoleCommands.importStatus, ImportMessages.statusDetails);
        await verifyFirstImport(t, seed);
      },
      { timeoutMs: 180_000 },
    );

    await t.step(
      "a second run starts at once and widens without duplicates",
      async () => {
        const id = await startImport(t, "widen standard");
        const job = await awaitImportEnded(t, id, IMPORT_TIMEOUT_MS);
        t.check("the second import completed", jsonString(job, "status") === "completed", job);
        t.check("the scoped ban arrived", (await punishmentOf(t, seed.scoped.uuid, seed.scoped.banId)).count === 1);
        t.check("the first ban is not duplicated", (await punishmentOf(t, seed.banned.uuid, seed.banned.banId)).count === 1);
        t.check("standard ended the kept history", jsonField(await latestImportStatus(t), "ipHistoryKeptBefore") === undefined);
      },
      { timeoutMs: IMPORT_TIMEOUT_MS, ignoreProblems: [ConsolePatterns.importUnreadablePlayer] },
    );

    await t.step(
      "cancel during export leaves no file",
      async () => {
        const id = await startImport(t, "skip standard");
        await t.consoleRun(ConsoleCommands.importCancel);
        const job = await awaitImportEnded(t, id, IMPORT_TIMEOUT_MS);
        t.check("the import was cancelled", jsonString(job, "endReason") === "IMPORT_END_REASON_CANCELLED", job);
        await t.sleep(5000);
        const left = importFilesLeft(serverDir);
        t.check("no import file is left on the Minecraft server", left.length === 0, left);
      },
      { timeoutMs: IMPORT_TIMEOUT_MS, ignoreProblems: [ConsolePatterns.importUnreadablePlayer] },
    );

    await t.step(
      "the fixture is removed",
      async () => {
        removeLiteBansFixture(serverDir);
        await t.restart(core);
      },
      { timeoutMs: RESTART_TIMEOUT_MS },
    );
  },
});
