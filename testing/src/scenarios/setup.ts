import { randomInt } from "node:crypto";
import {
  connectedPlatform,
  Endpoints,
  MailTestOutcomes,
  PublicUrlVerdicts,
  ReplayErrors,
  SETUP_CODE_ALPHABET,
  SETUP_CODE_ATTEMPTS_PER_IP,
  SETUP_STEPS,
  SetupErrors,
  SetupHosts,
  SetupPhases,
  setupStepEnum,
  SetupStepStatuses,
  SettingsErrors,
  type SetupStep,
} from "../lib/contract";
import { apiErrorCode, HttpStatus, jsonArray, jsonField, jsonString, type HttpResult } from "../lib/http";
import { setupStepStatus, type SetupCode, type SetupCodeFile, type SetupStatus, type SetupWizardState } from "../lib/instance";
import { settings } from "../lib/settings";
import { isMinecraftKind, type TopologyName, type UpOptions } from "../lib/state";
import { defineScenario, type ScenarioContext } from "./framework";
import { linkOwnerAccount } from "./support";

const SETUP_TOPOLOGIES: readonly TopologyName[] = ["standalone", "network", "remote"];
const HOST_RESTART_MARGIN_MS = 120_000;
const CONNECTED_SERVER_TIMEOUT_MS = 30_000;
const UNUSED_LOOPBACK_PORT = 1;
const TEST_MAIL_SENDER = "harness@modl.test";
const INVITED_ROLE = "Moderator";
const NON_HTTP_ORIGIN = "ftp://modl.test";
const CODE_FILE_INSTRUCTION = /Open .+\/setup and enter the code\./;
const REACHABILITY_VERDICTS: readonly string[] = [PublicUrlVerdicts.reachable, PublicUrlVerdicts.unreachable, PublicUrlVerdicts.otherService];

type ReadableCodeFile = SetupCodeFile & { code: string };

function refusalEvidence(result: HttpResult): Record<string, unknown> {
  return { status: result.status, code: apiErrorCode(result), body: result.json };
}

function expectRefusal(t: ScenarioContext, name: string, result: HttpResult, status: number, code: string): boolean {
  return t.check(name, result.status === status && apiErrorCode(result) === code, refusalEvidence(result));
}

function expectSessionIssued(t: ScenarioContext, name: string, result: HttpResult): boolean {
  return t.check(name, result.status === HttpStatus.ok && jsonField(result.json, "success") !== false, refusalEvidence(result));
}

function isOwner(t: ScenarioContext, email: string | null | undefined): boolean {
  return email?.toLowerCase() === t.instance.ownerEmail.toLowerCase();
}

export function requireNoPresetOwner(options: UpOptions): string | null {
  return options.presetAdminEmail === null ? null : "the owner email is preset (--admin-email), so nobody can claim the instance";
}

function requirePresetOwner(options: UpOptions): string | null {
  return options.presetAdminEmail === null ? "needs a preset owner email (up --admin-email <email>)" : null;
}

function expectedSetupHost(t: ScenarioContext): string {
  return t.env.server(t.noticeServer).role === "backend" ? SetupHosts.standalone : SetupHosts.plugin;
}

function checkHostStatus(t: ScenarioContext, status: SetupStatus): void {
  const expected = expectedSetupHost(t);
  t.check(`the status names the ${expected} host`, status.host === expected, status);
}

async function expectNoServerPathInStatus(t: ScenarioContext): Promise<void> {
  const raw = await t.instance.anonymousSession().request(Endpoints.setupStatus, { auth: "none" });
  t.check("the anonymous status names no server file", jsonField(raw.json, "codeFile") === undefined, raw.json);
}

async function requireCodeFile(t: ScenarioContext, path: string): Promise<ReadableCodeFile> {
  const file = await t.instance.readSetupCodeFile(path);
  t.evidence("code file", file);
  const code = file?.code;
  t.require(file && code, `the code file ${path} is missing or holds no code`);
  return { ...file, code };
}

async function requirePrintedCode(t: ScenarioContext): Promise<SetupCode> {
  const code = await t.instance.latestSetupCode();
  t.require(code, `no setup code was printed to the ${t.noticeServer} console`);
  t.evidence("printed setup code", code);
  return code;
}

function wrongCode(real: string): string {
  const characters = () => Array.from({ length: 4 }, () => SETUP_CODE_ALPHABET.charAt(randomInt(SETUP_CODE_ALPHABET.length))).join("");
  const candidate = `${characters()}-${characters()}`;
  return candidate === real ? wrongCode(real) : candidate;
}

async function markStep(t: ScenarioContext, step: SetupStep, status: string): Promise<void> {
  const state = await t.instance.updateSetupStep(step, status);
  t.check(`the ${step} step is ${status}`, setupStepStatus(state, step) === status, state.steps);
}

async function anonymousClaim(t: ScenarioContext, code: string): Promise<HttpResult> {
  return t.instance.anonymousSession().request(Endpoints.setupClaim, t.instance.claimRequest(code));
}

async function anonymousOwnerSignIn(t: ScenarioContext, code: string): Promise<HttpResult> {
  return t.instance.anonymousSession().request(Endpoints.setupSignIn, t.instance.ownerSignInRequest(code));
}

async function checkPublicUrl(t: ScenarioContext, url: string): Promise<{ verdict: string | null; normalizedUrl: string | null }> {
  const result = await t.instance.api(Endpoints.setupPublicUrlCheck, { method: "POST", json: { url } });
  t.require(result.status === HttpStatus.ok, `the URL check for ${url} answered ${result.status}: ${result.text}`);
  const verdict = { verdict: jsonString(result.json, "verdict"), normalizedUrl: jsonString(result.json, "normalizedUrl") };
  t.evidence(`URL check ${url}`, verdict);
  return verdict;
}

async function awaitConnectedServer(t: ScenarioContext, platform: string): Promise<unknown[]> {
  const deadline = Date.now() + CONNECTED_SERVER_TIMEOUT_MS;
  let servers: unknown[] = [];
  while (Date.now() < deadline) {
    const result = await t.instance.api(Endpoints.connectedServers);
    t.require(result.status === HttpStatus.ok, `the connected servers list answered ${result.status}: ${result.text}`);
    servers = jsonArray(result.json, "servers");
    if (servers.some((server) => jsonString(server, "platform") === platform)) return servers;
    await t.sleep(1000);
  }
  return t.fail(`no ${platform} server appeared in the connected servers list within ${CONNECTED_SERVER_TIMEOUT_MS}ms: ${JSON.stringify(servers)}`);
}

async function claimWithCode(t: ScenarioContext, code: string): Promise<void> {
  t.instance.http.clearSession();
  expectSessionIssued(t, "the claim issues a session", await t.instance.submitClaim(code));
  const profile = await t.instance.profile();
  t.check("the session is the owner's", isOwner(t, profile?.email), profile);
}

async function expectCodeFileDeleted(t: ScenarioContext, path: string): Promise<void> {
  const status = await t.instance.setupStatus();
  t.check("the phase is READY", status.claimed && status.phase === SetupPhases.ready, status);
  t.check(`the code file ${path} was deleted`, (await t.instance.readSetupCodeFile(path)) === null, { path });
}

async function lockOutWithWrongCodes(t: ScenarioContext, code: SetupCode): Promise<void> {
  for (let attempt = 1; attempt <= SETUP_CODE_ATTEMPTS_PER_IP; attempt += 1) {
    const result = await anonymousClaim(t, wrongCode(code.code));
    expectRefusal(t, `wrong code ${attempt} is ${SetupErrors.codeInvalid}`, result, HttpStatus.unauthorized, SetupErrors.codeInvalid);
  }
  const locked = await anonymousClaim(t, code.code);
  expectRefusal(t, `the right code after them is ${SetupErrors.codeLocked}`, locked, HttpStatus.unauthorized, SetupErrors.codeLocked);
  t.check("the instance is still unclaimed", !(await t.instance.isClaimed()));
}

async function checkWizardOpened(t: ScenarioContext): Promise<SetupWizardState> {
  const state = await t.instance.setupWizard();
  t.evidence("wizard", state);
  t.check("the wizard is not completed", !state.completed, state);
  t.check("the wizard lists all steps in order", JSON.stringify(state.steps.map((entry) => entry.step)) === JSON.stringify(SETUP_STEPS.map(setupStepEnum)), state.steps);
  t.check("the sign-in step waits for the owner's choice", setupStepStatus(state, "account") === SetupStepStatuses.pending, state.steps);
  t.check("the wizard names the same host as the status", state.environment.host === expectedSetupHost(t), state.environment);
  return state;
}

async function confirmSignInStep(t: ScenarioContext): Promise<void> {
  const ahead = await t.instance.api(Endpoints.setupStep("identity"), { method: "PUT", json: { status: SetupStepStatuses.done } });
  expectRefusal(t, `a later step before the sign-in step is ${SetupErrors.stepOutOfOrder}`, ahead, HttpStatus.conflict, SetupErrors.stepOutOfOrder);
  const skipped = await t.instance.api(Endpoints.setupStep("account"), { method: "PUT", json: { status: SetupStepStatuses.skipped } });
  expectRefusal(t, `skipping a step is ${SetupErrors.stepNotSkippable}`, skipped, HttpStatus.badRequest, SetupErrors.stepNotSkippable);
  const status = await t.instance.setupStatus();
  t.check("the owner sign-in phase lasts until the sign-in step", status.phase === SetupPhases.ownerSignIn, status);
  await markStep(t, "account", SetupStepStatuses.done);
  const confirmed = await t.instance.setupStatus();
  t.check("the sign-in step ends the code phase", confirmed.phase === SetupPhases.ready, confirmed);
}

async function patchServerName(t: ScenarioContext, expectedVersion: unknown, name: string): Promise<HttpResult> {
  return t.instance.api(Endpoints.generalSettings, { method: "PATCH", json: { expectedVersion, serverDisplayName: name } });
}

async function checkConfiguredServerName(t: ScenarioContext, expectedVersion: unknown, configured: string): Promise<void> {
  const renamed = await patchServerName(t, expectedVersion, `${configured} renamed`);
  expectRefusal(t, `another name is refused with ${SettingsErrors.serverNameManaged}`, renamed, HttpStatus.conflict, SettingsErrors.serverNameManaged);
  const resubmitted = await patchServerName(t, expectedVersion, configured);
  t.check("resubmitting the configured name is accepted", resubmitted.status === HttpStatus.ok, refusalEvidence(resubmitted));
}

async function setServerName(t: ScenarioContext): Promise<void> {
  const current = await t.instance.api(Endpoints.generalSettings);
  t.require(current.status === HttpStatus.ok, `reading the general settings answered ${current.status}: ${current.text}`);
  const expectedVersion = jsonField(jsonField(current.json, "_meta"), "version") ?? 0;
  const data = jsonField(current.json, "data");
  if (jsonField(data, "serverNameManaged") === true) {
    await checkConfiguredServerName(t, expectedVersion, jsonString(data, "serverDisplayName") ?? "");
    return;
  }
  const name = settings().serverName;
  const patched = await patchServerName(t, expectedVersion, name);
  t.check("the general settings accept the name", patched.status === HttpStatus.ok, refusalEvidence(patched));
  t.check("the name is saved", jsonString(jsonField(patched.json, "data"), "serverDisplayName") === name, patched.json);
}

async function testMailAgainstClosedPort(t: ScenarioContext): Promise<void> {
  const mail = { host: "127.0.0.1", port: UNUSED_LOOPBACK_PORT, username: "", from: TEST_MAIL_SENDER, starttls: false };
  const result = await t.instance.api(Endpoints.mailTest, { method: "POST", json: { settings: mail } });
  t.evidence("mail test", { status: result.status, body: result.json });
  t.check("the test answers 200", result.status === HttpStatus.ok, refusalEvidence(result));
  t.check(`the outcome is ${MailTestOutcomes.connectFailed}`, jsonString(result.json, "outcome") === MailTestOutcomes.connectFailed, result.json);
  t.check("the test mail would only go to the owner", isOwner(t, jsonString(result.json, "recipient")), result.json);
  const profile = await t.instance.profile();
  t.check("the owner session still works", isOwner(t, profile?.email), profile);
}

async function checkHostConnected(t: ScenarioContext): Promise<void> {
  const kind = t.env.server(t.coreServer).kind;
  t.require(isMinecraftKind(kind), `${t.coreServer} is not a Minecraft server`);
  const servers = await awaitConnectedServer(t, connectedPlatform(kind));
  t.evidence("connected servers", servers);
  const members = servers.filter(isLinkedMember);
  if (members.length > 0) t.evidence("servers behind the proxy", members);
}

function isLinkedMember(server: unknown): boolean {
  return jsonString(server, "role") === "SERVER_ROLE_MEMBER" && jsonField(server, "hubConnected") === true;
}

async function inviteStaff(t: ScenarioContext): Promise<void> {
  const email = `staff-${Date.now().toString(36)}@modl.test`;
  const result = await t.instance.api(Endpoints.staffInvite, { method: "POST", json: { email, role: INVITED_ROLE } });
  const invited = jsonArray(result.json, "success");
  t.evidence("invitation", { status: result.status, success: invited, failed: jsonArray(result.json, "failed"), links: jsonArray(result.json, "inviteLinks").length });
  t.check("the invitation is created", result.status === HttpStatus.created, { status: result.status });
  t.check("the invited address is listed", invited.includes(email), invited);
}

async function finishWizard(t: ScenarioContext): Promise<SetupWizardState> {
  const state = await t.instance.completeOpenSteps();
  t.evidence("completed wizard", state);
  t.check("the wizard is completed", state.completed, state);
  t.check("the wizard reads back as completed", (await t.instance.setupWizard()).completed);
  return state;
}

export const setupWizard = defineScenario({
  name: "setup-wizard",
  description:
    "a fresh instance: wrong codes lock the address out, a restart of the host issues a new code, the owner claims with the code and the email, confirms the sign-in step, walks every wizard step through the API, and the code cannot be used again",
  topologies: SETUP_TOPOLOGIES,
  setupPhase: SetupPhases.unclaimed,
  unsupportedBy: requireNoPresetOwner,
  async run(t: ScenarioContext) {
    const boot = await t.step("the console and the code file show the same setup code", async () => {
      const status = await t.instance.setupStatus();
      t.evidence("setup status", status);
      t.check("the instance is unclaimed", !status.claimed && status.phase === SetupPhases.unclaimed, status);
      checkHostStatus(t, status);
      await expectNoServerPathInStatus(t);
      const printed = await requirePrintedCode(t);
      t.check("the console code is a setup code", printed.kind === "setup", printed);
      const file = await requireCodeFile(t, t.instance.setupCodeFilePath());
      t.check("the code file holds the printed code", file.code === printed.code);
      return { printed, codeFile: file.path };
    });

    await t.step(`${SETUP_CODE_ATTEMPTS_PER_IP} wrong codes lock this address out of the code, even with the right code`, async () =>
      lockOutWithWrongCodes(t, boot.printed),
    );

    const issued = await t.step(
      `a restart of the ${t.noticeServer} prints a new code`,
      async () => {
        t.quitAllBots();
        await t.env.restartForNewSetupCode();
        const code = await requirePrintedCode(t);
        t.check("the new code replaces the locked one", code.code !== boot.printed.code);
        const file = await requireCodeFile(t, boot.codeFile);
        t.check("the code file holds the new code", file.code === code.code);
        return code;
      },
      { timeoutMs: settings().bootTimeoutMs + HOST_RESTART_MARGIN_MS, allowConsoleErrors: true },
    );

    await t.step("the new code claims the instance for the owner and opens the wizard", async () => {
      await claimWithCode(t, issued.code);
      const state = await checkWizardOpened(t);
      t.check("the other steps are pending", SETUP_STEPS.slice(1).every((step) => setupStepStatus(state, step) === SetupStepStatuses.pending), state.steps);
    });

    await t.step("sign-in: the wizard cannot finish or skip past the sign-in step, and confirming it ends the code phase", async () =>
      confirmSignInStep(t),
    );

    await t.step("import: starting fresh completes the import step", async () => markStep(t, "import", SetupStepStatuses.done));

    await t.step("identity: the general settings save the server name unless the server configuration sets it", async () => {
      await setServerName(t);
      await markStep(t, "identity", SetupStepStatuses.done);
    });

    await t.step("address: the panel URL check gives a verdict", async () => {
      const panel = await checkPublicUrl(t, t.instance.baseUrl);
      t.check("the panel URL gets a reachability verdict", REACHABILITY_VERDICTS.includes(panel.verdict ?? ""), panel);
      const invalid = await checkPublicUrl(t, NON_HTTP_ORIGIN);
      t.check(`a non-http origin is ${PublicUrlVerdicts.invalid}`, invalid.verdict === PublicUrlVerdicts.invalid, invalid);
      await markStep(t, "address", SetupStepStatuses.done);
    });

    await t.step("email: a test mail to a closed port reports the connection failure", async () => {
      await testMailAgainstClosedPort(t);
      await markStep(t, "email", SetupStepStatuses.done);
    });

    await t.connectPendingServers("servers: the servers waiting for the API key connect");

    await t.step(`servers: the connected list shows ${t.coreServer}`, async () => {
      await checkHostConnected(t);
      await markStep(t, "servers", SetupStepStatuses.done);
    });

    await t.step(
      "minecraft account: /modl link links the owner's player",
      async () => {
        t.evidence("linked profile", await linkOwnerAccount(t, await t.bot(settings().ownerPlayer)));
        await markStep(t, "minecraft-account", SetupStepStatuses.done);
      },
      { timeoutMs: 180_000 },
    );

    await t.step("staff: an invitation is created", async () => {
      await inviteStaff(t);
      await markStep(t, "staff", SetupStepStatuses.done);
    });

    await t.step("player addresses: nothing is preselected and choosing no lookups sends no player IP anywhere", async () => {
      const before = await t.instance.api(Endpoints.playerAddressSettings);
      t.evidence("player address settings", { status: before.status, json: before.json });
      t.check("the settings load", before.status === HttpStatus.ok, { status: before.status });
      t.check("no provider is preselected", jsonString(before.json, "provider") === null, before.json);
      t.check("the owner is asked to choose", jsonField(before.json, "lookupChoicePending") === true, before.json);
      const chosen = await t.instance.api(Endpoints.playerAddressSettings, { method: "PUT", json: { provider: "" } });
      t.check("choosing no lookups is accepted", chosen.status === HttpStatus.ok, refusalEvidence(chosen));
      t.check("lookups stay off", jsonField(chosen.json, "lookupsActive") !== true, chosen.json);
      t.check("the choice is made", jsonField(chosen.json, "lookupChoicePending") !== true, chosen.json);
      await markStep(t, "player-addresses", SetupStepStatuses.done);
    });

    await t.step("the AI step is done without AI and an unknown step is refused", async () => {
      await markStep(t, "ai", SetupStepStatuses.done);
      const unknown = await t.instance.api(Endpoints.setupStep("unknown"), { method: "PUT", json: { status: SetupStepStatuses.done } });
      t.check("an unknown step is 400", unknown.status === HttpStatus.badRequest, refusalEvidence(unknown));
    });

    await t.step("replays: nothing is chosen before a save, and the step waits for the choice", async () => {
      const before = await t.instance.api(Endpoints.replaySettings);
      t.check("the replay settings load", before.status === HttpStatus.ok, { status: before.status });
      t.check("nothing is chosen yet", jsonField(before.json, "choiceMadeAt") === undefined, before.json);
      t.check("replays and the anticheat integration start off", jsonField(jsonField(before.json, "data"), "enabled") !== true
        && jsonField(jsonField(before.json, "data"), "anticheatIntegration") !== true, before.json);
      const early = await t.instance.api(Endpoints.setupStep("replays"), { method: "PUT", json: { status: SetupStepStatuses.done } });
      expectRefusal(t, `marking the step before a choice is ${ReplayErrors.notChosen}`, early, HttpStatus.conflict, ReplayErrors.notChosen);
      await t.instance.updateReplaySettings({ enabled: false, anticheatIntegration: false });
      const after = await t.instance.api(Endpoints.replaySettings);
      t.check("the choice is recorded", jsonField(after.json, "choiceMadeAt") !== undefined, after.json);
      await markStep(t, "replays", SetupStepStatuses.done);
    });

    await t.step("the content step, the last one, completes the wizard with every step done", async () => {
      const state = await finishWizard(t);
      t.check("every step is done", SETUP_STEPS.every((step) => setupStepStatus(state, step) === SetupStepStatuses.done), state.steps);
    });

    await t.step("after setup nobody can claim again and the code file is gone", async () => {
      const again = await anonymousClaim(t, issued.code);
      expectRefusal(t, `a second claim is ${SetupErrors.alreadyClaimed}`, again, HttpStatus.conflict, SetupErrors.alreadyClaimed);
      await expectCodeFileDeleted(t, boot.codeFile);
    });
  },
});

export const setupNoConsole = defineScenario({
  name: "setup-no-console",
  description: "an owner without a working console claims a fresh instance with only the code from the setup code file at its documented location",
  topologies: SETUP_TOPOLOGIES,
  setupPhase: SetupPhases.unclaimed,
  unsupportedBy: requireNoPresetOwner,
  async run(t: ScenarioContext) {
    const file = await t.step("the setup code file at its documented location holds a setup code", async () => {
      const status = await t.instance.setupStatus();
      t.evidence("setup status", status);
      t.check("the instance is unclaimed", !status.claimed && status.phase === SetupPhases.unclaimed, status);
      checkHostStatus(t, status);
      const codeFile = await requireCodeFile(t, t.instance.setupCodeFilePath());
      t.check("the code file holds a setup code", codeFile.kind === "setup", { kind: codeFile.kind });
      t.expectMatch("the file says where to enter the code", codeFile.text, CODE_FILE_INSTRUCTION);
      return codeFile;
    });

    await t.step("the code from the file claims the instance", async () => {
      await claimWithCode(t, file.code);
      await checkWizardOpened(t);
      await confirmSignInStep(t);
      await finishWizard(t);
    });

    await t.step("the code file is deleted after the claim", async () => expectCodeFileDeleted(t, file.path));
  },
});

export const setupConfigClaim = defineScenario({
  name: "setup-config-claim",
  description: "with the owner email preset in the configuration nobody can claim the instance, and the owner signs in once with the owner sign-in code from the code file",
  topologies: SETUP_TOPOLOGIES,
  setupPhase: SetupPhases.ownerSignIn,
  unsupportedBy: requirePresetOwner,
  async run(t: ScenarioContext) {
    const file = await t.step("the configuration claimed the instance and the owner sign-in code is in the code file", async () => {
      const status = await t.instance.setupStatus();
      t.evidence("setup status", status);
      t.check("the instance waits for the owner's first sign-in", status.claimed && status.phase === SetupPhases.ownerSignIn, status);
      checkHostStatus(t, status);
      const codeFile = await requireCodeFile(t, t.instance.setupCodeFilePath());
      t.check("the code file holds an owner sign-in code", codeFile.kind === "owner", { kind: codeFile.kind });
      const printed = await requirePrintedCode(t);
      t.check("the console shows the same owner sign-in code", printed.kind === "owner" && printed.code === codeFile.code, printed);
      return codeFile;
    });

    await t.step("nobody can claim the instance", async () => {
      const claim = await anonymousClaim(t, wrongCode(file.code));
      expectRefusal(t, `a claim is ${SetupErrors.alreadyClaimed}`, claim, HttpStatus.conflict, SetupErrors.alreadyClaimed);
    });

    await t.step("the owner signs in with the code from the file and the wizard opens", async () => {
      t.instance.http.clearSession();
      expectSessionIssued(t, "the owner sign-in issues a session", await t.instance.submitOwnerSignIn(file.code));
      const profile = await t.instance.profile();
      t.check("the session is the preset owner's", isOwner(t, profile?.email), profile);
      await checkWizardOpened(t);
      await confirmSignInStep(t);
    });

    await t.step("the owner sign-in code is used up", async () => {
      await expectCodeFileDeleted(t, file.path);
      const again = await anonymousOwnerSignIn(t, file.code);
      expectRefusal(t, `a second owner sign-in is ${SetupErrors.signInNotAvailable}`, again, HttpStatus.conflict, SetupErrors.signInNotAvailable);
      await finishWizard(t);
    });
  },
});
