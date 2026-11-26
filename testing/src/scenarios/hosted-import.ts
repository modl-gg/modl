import { join } from "node:path/posix";
import {
  Endpoints,
  HostedImportEndpoints,
  HostedImportErrors,
  HostedImportIssueCodes,
  HostedImportMessages,
  HostedImportPhases,
  HostedImportRetentions,
  HostedImportStatuses,
  SetupPhases,
  SetupStepStatuses,
  setupStepEnum,
} from "../lib/contract";
import { apiErrorCode, HttpStatus, jsonArray, jsonField, jsonString, type HttpResult } from "../lib/http";
import { HostedImportClient } from "../lib/hosted-import-client";
import { createHostedImportFixture, type HostedImportFixture, type HostedImportSeed } from "../lib/hosted-import-fixture";
import { setupStepStatus } from "../lib/instance";
import type { LogMark } from "../lib/logs";
import { defineScenario, type ScenarioContext } from "./framework";
import { requireNoPresetOwner } from "./setup";
import { expectJoinRefused, serverReloads } from "./support";

const VERIFY_TIMEOUT_MS = 180_000;
const REFUSALS_TIMEOUT_MS = 480_000;
const IMPORT_TIMEOUT_MS = 900_000;
const RESTART_TIMEOUT_MS = 420_000;
const RELOAD_TIMEOUT_MS = 300_000;
const CHUNK_BYTES = 128 * 1024;
const JOIN_LIMIT_MS = 2000;
const STORED_OBJECT_PATH = /\/v1\/storage\/objects\/[^"\\\s]+/g;
const LOOPBACK_LINK = "https://127.0.0.1/export.zip?X-Amz-Signature=harness";
const LOOK_ALIKE_LINK = "https://exports.s3.us-east-005.backblazeb2.com.modl.test/export.zip?X-Amz-Signature=harness";

interface RunningImport {
  id: string;
  archiveFile: string;
  since: LogMark;
}

function evidenceOf(result: HttpResult): Record<string, unknown> {
  return { status: result.status, code: apiErrorCode(result), body: result.json };
}

async function panelText(t: ScenarioContext, path: string): Promise<string> {
  const result = await t.instance.api(path);
  t.require(result.status === HttpStatus.ok, `GET ${path} returned ${result.status}: ${result.text}`);
  return result.text;
}

async function panelJson(t: ScenarioContext, path: string): Promise<unknown> {
  const result = await t.instance.api(path);
  t.require(result.status === HttpStatus.ok, `GET ${path} returned ${result.status}: ${result.text}`);
  return result.json;
}

async function domainSnapshot(t: ScenarioContext, seed: HostedImportSeed): Promise<string> {
  const player = await t.instance.api(Endpoints.panelPlayer(seed.banned.uuid));
  const texts = [await panelText(t, Endpoints.panelStaff), await panelText(t, Endpoints.generalSettings)];
  return JSON.stringify({ player: player.status, texts });
}

async function claimWithoutFinishingSetup(t: ScenarioContext): Promise<void> {
  const code = await t.instance.latestSetupCode();
  t.require(code, "no setup code was printed");
  t.instance.http.clearSession();
  const claimed = await t.instance.submitClaim(code.code);
  t.require(claimed.status === HttpStatus.ok, `the claim answered ${claimed.status}: ${claimed.text}`);
  await t.instance.updateSetupStep("account", SetupStepStatuses.done);
}

async function expectLinkRefused(t: ScenarioContext, client: HostedImportClient, name: string, url: string): Promise<void> {
  const result = await client.request(HostedImportEndpoints.download, { method: "POST", json: { url } });
  t.check(
    `a ${name} link is refused`,
    result.status === HttpStatus.badRequest && apiErrorCode(result) === HostedImportErrors.linkNotAllowed,
    evidenceOf(result),
  );
}

async function expectArchiveRefused(
  t: ScenarioContext,
  client: HostedImportClient,
  name: string,
  archive: Buffer,
  reason: RegExp,
): Promise<void> {
  const { importId } = await client.begin(archive);
  const misplaced = await client.chunk(importId, 1, archive.subarray(1, 2));
  t.check(
    `${name}: a wrong offset is refused`,
    misplaced.status === HttpStatus.conflict && apiErrorCode(misplaced) === HostedImportErrors.uploadOffset,
    evidenceOf(misplaced),
  );
  await client.upload(importId, archive, 0, archive.length, CHUNK_BYTES);
  await client.completeUpload(importId);
  const state = await client.awaitVerified(VERIFY_TIMEOUT_MS);
  t.check(`${name} is refused`, state.status === HostedImportStatuses.failed && reason.test(state.reasonCode), state);
}

async function expectRefusals(t: ScenarioContext, client: HostedImportClient, fixture: HostedImportFixture): Promise<void> {
  const before = await domainSnapshot(t, fixture.seed);
  const anonymous = await t.instance.anonymousSession().request(HostedImportEndpoints.state, { auth: "none" });
  t.check("an anonymous caller is 401", anonymous.status === HttpStatus.unauthorized, evidenceOf(anonymous));
  await expectLinkRefused(t, client, "loopback", LOOPBACK_LINK);
  await expectLinkRefused(t, client, "look-alike", LOOK_ALIKE_LINK);
  t.check("refused links change nothing", (await domainSnapshot(t, fixture.seed)) === before);

  const archives: readonly [string, Buffer, RegExp][] = [
    ["a truncated zip", fixture.truncated, /^ARCHIVE_(?:NOT_ZIP|DAMAGED|INCOMPLETE)$/],
    ["a wrong hash", fixture.wrongHash, /^ARCHIVE_CHECKSUM$/],
    ["a traversal key", fixture.foreignKey, /^ARCHIVE_FORMAT$/],
  ];
  for (const [name, archive, reason] of archives) {
    await expectArchiveRefused(t, client, name, archive, reason);
    t.check(`${name} changes nothing`, (await domainSnapshot(t, fixture.seed)) === before);
  }
}

async function uploadWithInterruption(t: ScenarioContext, client: HostedImportClient, archive: Buffer): Promise<string> {
  const { importId } = await client.begin(archive);
  const received = await client.upload(importId, archive, 0, Math.ceil(archive.length / 2), CHUNK_BYTES);
  const interrupted = await client.state();
  t.check("the state remembers the received bytes", interrupted.receivedBytes === received, interrupted);
  const replayed = await client.chunk(importId, 0, archive.subarray(0, CHUNK_BYTES));
  t.check("bytes that arrived are not taken again", replayed.status === HttpStatus.conflict, evidenceOf(replayed));
  await client.upload(importId, archive, interrupted.receivedBytes, archive.length, CHUNK_BYTES);
  await client.completeUpload(importId);
  const verified = await client.awaitVerified(VERIFY_TIMEOUT_MS);
  t.require(verified.status === HostedImportStatuses.review, `the archive was not accepted: ${JSON.stringify(verified)}`);
  return importId;
}

function countOf(json: unknown, subject: string, field: "total" | "imported"): number {
  const count = jsonArray(json, "counts").find((entry) => jsonString(entry, "subject") === subject);
  return Number(jsonField(count, field) ?? 0);
}

function issueCodes(json: unknown): (string | null)[] {
  return jsonArray(json, "issues").map((issue) => jsonString(issue, "code"));
}

async function checkPreview(t: ScenarioContext, seed: HostedImportSeed): Promise<void> {
  const preview = await panelJson(t, HostedImportEndpoints.preview);
  t.evidence("preview", preview);
  for (const [subject, expected] of Object.entries(seed.expected)) {
    t.check(`the preview counts ${expected} ${subject}`, countOf(preview, subject, "total") === expected, preview);
  }
  const imported = jsonArray(preview, "counts").filter((entry) => Number(jsonField(entry, "imported") ?? 0) !== 0);
  t.check("the preview counts nothing as imported yet", imported.length === 0, imported);
  const codes = issueCodes(preview);
  const expectedCodes = [
    HostedImportIssueCodes.fileSkippedByExport,
    HostedImportIssueCodes.fileCaseConflict,
    HostedImportIssueCodes.webhookDisabled,
    HostedImportIssueCodes.aiDisabled,
    HostedImportIssueCodes.commandRedacted,
    HostedImportIssueCodes.punishmentWithoutData,
    HostedImportIssueCodes.punishmentRollbackEnds,
    HostedImportIssueCodes.roleRenamed,
  ];
  for (const code of expectedCodes) t.check(`the preview reports ${code}`, codes.includes(code), codes);
  t.check("refused settings are left out without an issue", !codes.includes(HostedImportIssueCodes.settingUnreadable), codes);
  t.check("the other owner email is pointed out", jsonField(preview, "ownerEmailsMatch") !== true);
  t.check("both retention choices are offered", jsonArray(preview, "retention").length === 2);
  const staff = await panelText(t, HostedImportEndpoints.staff);
  t.check("both staff members are offered", staff.includes(seed.selectedStaff.email) && staff.includes(seed.unselectedStaffEmail));
}

async function importFileExists(t: ScenarioContext, path: string): Promise<boolean> {
  return (await t.env.readServerFile(t.noticeServer, path)) !== null;
}

async function startImport(t: ScenarioContext, client: HostedImportClient, seed: HostedImportSeed, importId: string): Promise<void> {
  const started = await client.request(HostedImportEndpoints.start, {
    method: "POST",
    json: {
      importId,
      staffIds: [seed.selectedStaff.sourceId],
      retention: HostedImportRetentions.keepHistory,
      confirmed: true,
    },
  });
  t.require(started.status === HttpStatus.ok, `starting answered ${started.status}: ${started.text}`);
  const completion = await t.instance.api(Endpoints.setupStep("import"), { method: "PUT", json: { status: SetupStepStatuses.done } });
  t.check(
    "the import step cannot be completed during the import",
    completion.status === HttpStatus.conflict && apiErrorCode(completion) === HostedImportErrors.active,
    evidenceOf(completion),
  );
}

async function restartWhilePlayersAreImported(t: ScenarioContext, client: HostedImportClient): Promise<void> {
  const interrupted = await client.awaitState(
    (state) => state.phase === HostedImportPhases.players || state.status !== HostedImportStatuses.applying,
    VERIFY_TIMEOUT_MS,
  );
  t.check(
    "the players were being imported",
    interrupted.status === HostedImportStatuses.applying && interrupted.phase === HostedImportPhases.players,
    interrupted,
  );
  const since = await t.env.log(t.noticeServer).mark();
  await t.restart(t.env.state.dataServer);
  await t.instance.waitHealthy(RESTART_TIMEOUT_MS);
  await t.env.log(t.noticeServer).waitFor(HostedImportMessages.resumed, { since, timeoutMs: VERIFY_TIMEOUT_MS });
}

async function joinDuringImport(t: ScenarioContext, client: HostedImportClient): Promise<void> {
  const state = await client.state();
  t.check("the import is still running", state.status === HostedImportStatuses.applying, state);
  const name = t.playerName("HNew");
  const started = Date.now();
  await t.bot(name);
  const joinMs = Date.now() - started;
  t.evidence("join during the import", { joinMs, state });
  t.check(`a login during the import takes under ${JOIN_LIMIT_MS}ms`, joinMs < JOIN_LIMIT_MS, { joinMs });
  t.quitBot(name);
}

async function reloadDuringImport(t: ScenarioContext, client: HostedImportClient): Promise<void> {
  const state = await client.state();
  t.check("the import is running when /reload is sent", state.status === HostedImportStatuses.applying, state);
  t.quitAllBots();
  const log = t.env.log(t.coreServer);
  const since = await log.mark();
  await t.sendReload(t.coreServer);
  await t.env.waitForReload(t.coreServer, since);
  await log.waitFor(HostedImportMessages.resumed, { since, timeoutMs: VERIFY_TIMEOUT_MS });
}

async function checkFinished(t: ScenarioContext, client: HostedImportClient, seed: HostedImportSeed, running: RunningImport): Promise<void> {
  const ended = await client.awaitState((state) => state.status !== HostedImportStatuses.applying, IMPORT_TIMEOUT_MS);
  t.require(ended.status === HostedImportStatuses.completed, `the import did not complete: ${JSON.stringify(ended)}`);
  const report = await panelJson(t, HostedImportEndpoints.report);
  t.evidence("report", report);
  for (const [subject, expected] of Object.entries(seed.expected)) {
    t.check(`exactly ${expected} ${subject} arrived`, countOf(report, subject, "imported") === expected, report);
  }
  t.check("the one selected staff member is counted", countOf(report, "staff", "imported") === 1, report);
  const log = t.env.log(t.noticeServer);
  const line = await log.waitFor(HostedImportMessages.finished, { since: running.since, timeoutMs: 30_000 });
  const printed = HostedImportMessages.finished.exec(line.text)?.slice(1).map(Number);
  const { players, punishments, tickets, files } = seed.expected;
  t.check("the console counts are exact", JSON.stringify(printed) === JSON.stringify([players, punishments, tickets, files]), line.text);
  t.check("the start was printed", (await log.find(HostedImportMessages.started, running.since)).length === 1);
  t.check("the import never paused", (await log.find(HostedImportMessages.paused, running.since)).length === 0);
  const wizard = await t.instance.setupWizard();
  t.check("the import step is done", setupStepStatus(wizard, "import") === SetupStepStatuses.done, wizard.steps);
}

async function checkEnforcement(t: ScenarioContext, seed: HostedImportSeed): Promise<void> {
  const refusal = await expectJoinRefused(t, seed.banned.name);
  t.expectMatch("the imported ban is enforced", refusal, /ban/i);
  const released = await t.bot(seed.rolledBack.name);
  t.check("the rolled-back player is admitted", released.connected);
  t.quitBot(seed.rolledBack.name);
}

async function checkImportedData(t: ScenarioContext, seed: HostedImportSeed, mailBefore: string): Promise<void> {
  const profile = await t.instance.profile();
  t.check("the owner is unchanged", profile?.email.toLowerCase() === t.instance.ownerEmail.toLowerCase(), profile);
  const staff = await panelText(t, Endpoints.panelStaff);
  t.check("the selected staff member is restored", staff.includes(seed.selectedStaff.email));
  t.check("the unselected staff member is absent", !staff.includes(seed.unselectedStaffEmail));
  t.check("the archived owner has no account", !staff.includes(seed.archivedOwnerEmail));

  const webhooks = await panelJson(t, Endpoints.webhookSettings);
  t.check("the webhook arrived", jsonField(webhooks, "discordWebhookConfigured") === true, webhooks);
  t.check("the webhook is off", jsonField(webhooks, "enabled") !== true, webhooks);
  const ai = await panelJson(t, Endpoints.aiModerationSettings);
  t.check("AI review is off", jsonField(ai, "enableAIReview") !== true, ai);
  t.check("AI actions are off", jsonField(ai, "enableAutomatedActions") !== true, ai);
  const mail = await panelText(t, Endpoints.mailSettings);
  t.check("mail is untouched", mail === mailBefore && !mail.includes(seed.hostileMailHost));

  const commands = await t.instance.apiWithCredentials(Endpoints.minecraftCommandLogs(seed.banned.uuid), { auth: "api-key" });
  t.check(
    "the credential command is redacted",
    commands.status === HttpStatus.ok && commands.text.includes("/login") && !commands.text.includes(seed.secretInCommand),
    evidenceOf(commands),
  );
  const deletedType = await panelText(t, Endpoints.panelPlayer(seed.deletedType.uuid));
  t.check("the deleted type keeps a name", deletedType.includes(seed.deletedTypeName));
  const linked = await t.instance.apiWithCredentials(Endpoints.minecraftLinkedAccounts(seed.banned.uuid), { auth: "api-key" });
  t.check("linked accounts are present", linked.status === HttpStatus.ok && linked.text.includes(seed.linked.uuid), evidenceOf(linked));
}

async function checkEvidence(t: ScenarioContext, seed: HostedImportSeed): Promise<void> {
  const paths = [...new Set((await panelText(t, Endpoints.panelPlayer(seed.banned.uuid))).match(STORED_OBJECT_PATH) ?? [])];
  t.check("both case variants have a local URL", paths.length === seed.evidence.length, paths);
  const files: Buffer[] = [];
  for (const path of paths) files.push((await t.instance.http.bytes(path)).body);
  for (const [index, expected] of seed.evidence.entries()) {
    t.check(`evidence ${index + 1} is byte for byte the exported file`, files.some((file) => file.equals(expected)), paths);
  }
}

async function checkRetentionAndCleanup(
  t: ScenarioContext,
  client: HostedImportClient,
  fixture: HostedImportFixture,
  running: RunningImport,
): Promise<void> {
  const retention = jsonField(await panelJson(t, Endpoints.dataRetentionSettings), "data");
  const chatDays = Number(jsonField(retention, "chatLogDays") ?? 0);
  t.check("kept history covers the oldest log", chatDays >= fixture.seed.oldLogAgeDays + 30, retention);
  const report = await panelJson(t, HostedImportEndpoints.report);
  t.check("the report names the choice", jsonString(report, "retention") === HostedImportRetentions.keepHistory, report);
  t.check("the archive was removed", !(await importFileExists(t, running.archiveFile)));
  const state = await client.state();
  t.check("a second import is not eligible", !state.eligible && state.blockers.includes(HostedImportErrors.alreadyImported), state);
  const second = await client.request(HostedImportEndpoints.upload, {
    method: "POST",
    json: { sizeBytes: String(fixture.wrongHash.length), fileName: "second.zip" },
  });
  t.check("a second import is refused", second.status === HttpStatus.conflict, evidenceOf(second));
}

export const hostedImport = defineScenario({
  name: "hosted-import",
  description:
    "a fresh instance imports a synthetic modl.gg export: refusals, an interrupted upload, the review, a restart while players import, exact counts, enforcement, settings, files and cleanup",
  topologies: ["standalone", "remote"],
  setupPhase: SetupPhases.unclaimed,
  unsupportedBy: requireNoPresetOwner,
  async run(t: ScenarioContext) {
    const client = new HostedImportClient(t.instance);

    const boot = await t.step("the claimed instance offers the import as the second step", async () => {
      await claimWithoutFinishingSetup(t);
      const wizard = await t.instance.setupWizard();
      t.check("import is the second step", wizard.steps[1]?.step === setupStepEnum("import"), wizard.steps);
      const state = await client.state();
      t.check("the import is eligible", state.eligible && state.status === HostedImportStatuses.none, state);
      return { importDirectory: join(t.instance.dataDirectoryPath(), "imports"), mail: await panelText(t, Endpoints.mailSettings) };
    });

    await t.connectPendingServers("the servers waiting for the API key connect");

    const fixture = await t.step("the synthetic export is built", () => createHostedImportFixture((prefix) => t.playerName(prefix)));
    const { seed } = fixture;

    await t.step("callers, links and broken archives are refused without writing", async () => expectRefusals(t, client, fixture), {
      timeoutMs: REFUSALS_TIMEOUT_MS,
    });

    const uploaded = await t.step(
      "an interrupted upload resumes and the review shows counts and issues",
      async () => {
        const id = await uploadWithInterruption(t, client, fixture.archive);
        await checkPreview(t, seed);
        const archiveFile = join(boot.importDirectory, `${id}.zip`);
        t.check("the archive waits in the data directory", await importFileExists(t, archiveFile));
        return { id, archiveFile };
      },
      { timeoutMs: REFUSALS_TIMEOUT_MS },
    );

    const running = await t.step("the owner starts with one staff member", async (): Promise<RunningImport> => {
      const since = await t.env.log(t.noticeServer).mark();
      await startImport(t, client, seed, uploaded.id);
      return { ...uploaded, since };
    });

    await t.step("a restart while players are imported resumes the import", async () => restartWhilePlayersAreImported(t, client), {
      timeoutMs: RESTART_TIMEOUT_MS + VERIFY_TIMEOUT_MS,
      allowConsoleErrors: true,
    });

    await t.step("a player logs in while the import runs", async () => joinDuringImport(t, client));

    if (t.topology === "standalone" && serverReloads(t)) {
      await t.step("/reload during the import resumes it", async () => reloadDuringImport(t, client), {
        timeoutMs: RELOAD_TIMEOUT_MS + VERIFY_TIMEOUT_MS,
        allowConsoleErrors: true,
      });
    }

    await t.step("the import completes with exact counts", async () => checkFinished(t, client, seed, running), {
      timeoutMs: IMPORT_TIMEOUT_MS + VERIFY_TIMEOUT_MS,
    });

    await t.step("the imported ban holds and the rollback does not", async () => checkEnforcement(t, seed));

    await t.step("the archive granted nothing by itself and the data is right", async () => checkImportedData(t, seed, boot.mail));

    await t.step("evidence downloads byte for byte", async () => checkEvidence(t, seed));

    await t.step(
      "evidence still downloads after a restart",
      async () => {
        await t.restart(t.env.state.dataServer);
        await t.instance.waitHealthy(RESTART_TIMEOUT_MS);
        await checkEvidence(t, seed);
      },
      { timeoutMs: RESTART_TIMEOUT_MS + VERIFY_TIMEOUT_MS, allowConsoleErrors: true },
    );

    await t.step(
      "retention is as confirmed, the archive is gone and a second import is refused",
      async () => checkRetentionAndCleanup(t, client, fixture, running),
      { timeoutMs: VERIFY_TIMEOUT_MS },
    );

    await t.step("the wizard completes", async () => {
      t.check("setup completes", (await t.instance.completeOpenSteps()).completed);
    });
  },
});
