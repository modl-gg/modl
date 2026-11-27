import { Endpoints, SetupPhases, SetupStepStatuses } from "../lib/contract";
import { HttpStatus, apiErrorCode, jsonArray, jsonField, jsonString, type HttpResult, type HttpSession } from "../lib/http";
import {
  HARNESS_OWNER_PASSWORD,
  SignInClient,
  SignInEndpoints,
  SignInErrors,
  SignInMethods,
  challengeOf,
  issuedSessionKey,
} from "../lib/sign-in";
import { setupStepStatus } from "../lib/instance";
import type { TotpDevice } from "../lib/totp";
import { defineScenario, type ScenarioContext } from "./framework";

const UNKNOWN_EMAIL = "nobody-here@modl.invalid";
const TOO_SHORT_PASSWORD = "too short";
const STAFF_PASSWORD = "modl harness staff password";
const STAFF_RESET_PASSWORD = "modl harness staff password after reset";
const INVITED_ROLE = "Moderator";
const FORBIDDEN = 403;
const LOGIN_CODE_PRINT_WINDOW_MS = 3000;
const RECOVERY_CODE_COUNT = 10;
const RESTART_TIMEOUT_MS = 420_000;
const RATE_LIMITED_STEP_TIMEOUT_MS = 240_000;

function ownerClient(t: ScenarioContext): SignInClient {
  return new SignInClient(t.instance.http);
}

function freshClient(t: ScenarioContext): { session: HttpSession; client: SignInClient } {
  const session = t.instance.anonymousSession();
  return { session, client: new SignInClient(session) };
}

async function ensureOwnerPassword(t: ScenarioContext, owner: SignInClient): Promise<void> {
  const set = await owner.setPassword(HARNESS_OWNER_PASSWORD);
  if (set.status === HttpStatus.ok) return;
  const changed = await owner.setPassword(HARNESS_OWNER_PASSWORD, HARNESS_OWNER_PASSWORD);
  t.check("the owner has the harness password", changed.status === HttpStatus.ok, { set: set.status, changed: changed.status, code: apiErrorCode(changed) });
}

async function restoreDefaults(t: ScenarioContext, device: TotpDevice | null): Promise<void> {
  const owner = ownerClient(t);
  const restored = await owner.updateSettings(SignInMethods.emailCode, false);
  t.evidence("restore sign-in settings", { status: restored.status, code: apiErrorCode(restored) });
  if (device !== null) {
    const disabled = await owner.disableTotp(await device.nextCode());
    t.evidence("restore authenticator", { status: disabled.status, code: apiErrorCode(disabled) });
  }
}

export const passwordLogin = defineScenario({
  name: "password-login",
  description: "the owner sets a password and switches to password sign-in; failures look the same, codes are refused, changing the password ends other sessions",
  topologies: ["standalone", "network", "remote"],
  async run(t: ScenarioContext) {
    await t.instance.ensureOwnerSession();
    const owner = ownerClient(t);
    try {
      await t.step("the owner sets a password and switches the instance to password sign-in", async () => {
        await ensureOwnerPassword(t, owner);
        const switched = await owner.updateSettings(SignInMethods.password, false);
        t.check("the switch is accepted", switched.status === HttpStatus.ok, { status: switched.status, code: apiErrorCode(switched) });
        const settings = await t.instance.anonymousSession().request(SignInEndpoints.publicSettings, { auth: "none" });
        t.check("public settings announce password sign-in", jsonString(settings.json, "signInMethod") === SignInMethods.password, settings.json);
      });

      await t.step("the owner signs in with email and password", async () => {
        const { client } = freshClient(t);
        const login = await client.passwordLogin(t.instance.ownerEmail, HARNESS_OWNER_PASSWORD);
        t.check("password login issues a session key", login.status === HttpStatus.ok && issuedSessionKey(login) !== null, { status: login.status });
      });

      await t.step("a wrong password and an unknown account answer identically", async () => {
        const { client } = freshClient(t);
        const wrong = await client.passwordLogin(t.instance.ownerEmail, "definitely not the password");
        const unknown = await client.passwordLogin(UNKNOWN_EMAIL, "definitely not the password");
        t.check("both are 401 INVALID_CREDENTIALS", wrong.status === 401 && unknown.status === 401
          && apiErrorCode(wrong) === SignInErrors.invalidCredentials && apiErrorCode(unknown) === SignInErrors.invalidCredentials,
        { wrong: wrong.json, unknown: unknown.json });
        t.check("the bodies are the same", JSON.stringify(wrong.json) === JSON.stringify(unknown.json));
      });

      await t.step("email codes are refused while password sign-in is on", async () => {
        const sent = await t.instance.anonymousSession().request(SignInEndpoints.sendEmailCode, {
          method: "POST", auth: "none", json: { email: t.instance.ownerEmail },
        });
        t.check("send-email-code answers 409 SIGN_IN_METHOD_DISABLED", sent.status === HttpStatus.conflict
          && apiErrorCode(sent) === SignInErrors.methodDisabled, { status: sent.status, body: sent.json });
      });

      await t.step("changing the password signs out the other sessions", async () => {
        const { client: other } = freshClient(t);
        const second = await other.passwordLogin(t.instance.ownerEmail, HARNESS_OWNER_PASSWORD);
        t.require(second.status === HttpStatus.ok, "the second session could not sign in");
        await ensureOwnerPassword(t, owner);
        const after = await other.request(SignInEndpoints.security);
        t.check("the second session is gone", after.status === 401, { status: after.status });
      });

      await t.step("an unknown account runs out of attempts with a retry hint", async () => {
        const { client } = freshClient(t);
        let last = await client.passwordLogin(UNKNOWN_EMAIL, "attempt");
        for (let attempt = 0; attempt < 5 && apiErrorCode(last) !== SignInErrors.tooManyAttempts; attempt++) {
          last = await client.passwordLogin(UNKNOWN_EMAIL, "attempt");
        }
        t.check("the sixth attempt is 429 TOO_MANY_ATTEMPTS", last.status === HttpStatus.tooManyRequests
          && apiErrorCode(last) === SignInErrors.tooManyAttempts, { status: last.status, body: last.json });
        t.check("a retry hint is given", jsonField(last.json, "retryAfterSeconds") !== undefined, last.json);
      });
    } finally {
      await restoreDefaults(t, null);
    }
  },
});

export const totpLogin = defineScenario({
  name: "totp-login",
  description: "the owner enrols an authenticator; a replayed code is refused, a password sign-in is challenged, codes and recovery codes complete it once",
  topologies: ["standalone", "network", "remote"],
  async run(t: ScenarioContext) {
    await t.instance.ensureOwnerSession();
    const owner = ownerClient(t);
    let device: TotpDevice | null = null;
    try {
      const recoveryCodes = await t.step("the owner enrols an authenticator app", async () => {
        await ensureOwnerPassword(t, owner);
        const enrolled = await owner.enrollTotp({ currentPassword: HARNESS_OWNER_PASSWORD });
        device = enrolled.device;
        t.check("ten recovery codes are shown", enrolled.recoveryCodes.length === 10, enrolled.recoveryCodes.length);
        const switched = await owner.updateSettings(SignInMethods.password, false);
        t.check("password sign-in is on", switched.status === HttpStatus.ok, { status: switched.status });
        return enrolled.recoveryCodes;
      });

      await t.step("a password sign-in is challenged without a session", async () => {
        const { client } = freshClient(t);
        const login = await client.passwordLogin(t.instance.ownerEmail, HARNESS_OWNER_PASSWORD);
        const challenge = challengeOf(login);
        t.check("a challenge, no session key", login.status === HttpStatus.ok && challenge !== null && issuedSessionKey(login) === null, login.json);
        t.check("no session cookie was set", login.setCookies.length === 0, login.setCookies);
        t.require(challenge !== null && device !== null, "no challenge to complete");
        const code = await device.nextCode();
        const done = await client.completeSecondFactor(challenge, code);
        t.check("the code completes the sign-in", done.status === HttpStatus.ok && issuedSessionKey(done) !== null, { status: done.status });
        const replayChallenge = challengeOf(await client.passwordLogin(t.instance.ownerEmail, HARNESS_OWNER_PASSWORD));
        t.require(replayChallenge !== null, "no second challenge");
        const replay = await client.completeSecondFactor(replayChallenge, code);
        t.check("the same code is refused again", apiErrorCode(replay) === SignInErrors.secondFactorInvalid, { status: replay.status, body: replay.json });
      });

      await t.step("a recovery code works exactly once", async () => {
        const recovery = recoveryCodes[0];
        t.require(recovery !== undefined, "no recovery code");
        const { client } = freshClient(t);
        const first = challengeOf(await client.passwordLogin(t.instance.ownerEmail, HARNESS_OWNER_PASSWORD));
        t.require(first !== null, "no challenge");
        const used = await client.completeSecondFactor(first, recovery);
        t.check("the recovery code signs in", used.status === HttpStatus.ok, { status: used.status });
        const second = challengeOf(await client.passwordLogin(t.instance.ownerEmail, HARNESS_OWNER_PASSWORD));
        t.require(second !== null, "no challenge");
        const reused = await client.completeSecondFactor(second, recovery);
        t.check("a reused recovery code is refused", apiErrorCode(reused) === SignInErrors.secondFactorInvalid, { status: reused.status });
      });
    } finally {
      await restoreDefaults(t, device);
    }
  },
});

export const secondFactorRequired = defineScenario({
  name: "second-factor-required",
  description: "the owner cannot require 2FA without one; once required, the last factor cannot be removed",
  topologies: ["standalone", "remote"],
  async run(t: ScenarioContext) {
    await t.instance.ensureOwnerSession();
    const owner = ownerClient(t);
    let device: TotpDevice | null = null;
    try {
      await t.step("requiring two-factor authentication without one is refused", async () => {
        const refused = await owner.updateSettings(SignInMethods.emailCode, true);
        t.check("409 OWNER_SECOND_FACTOR_REQUIRED", refused.status === HttpStatus.conflict
          && apiErrorCode(refused) === SignInErrors.ownerFactorRequired, { status: refused.status, body: refused.json });
      });

      await t.step("after enrolling, the requirement can be turned on", async () => {
        await ensureOwnerPassword(t, owner);
        device = (await owner.enrollTotp({ currentPassword: HARNESS_OWNER_PASSWORD })).device;
        const required = await owner.updateSettings(SignInMethods.emailCode, true);
        t.check("the requirement is saved", required.status === HttpStatus.ok, { status: required.status, body: required.json });
      });

      await t.step("the last second factor cannot be removed while required", async () => {
        t.require(device !== null, "no authenticator");
        const removed = await owner.disableTotp(await device.nextCode());
        t.check("409 LAST_SECOND_FACTOR", removed.status === HttpStatus.conflict && apiErrorCode(removed) === SignInErrors.lastSecondFactor,
          { status: removed.status, body: removed.json });
      });

      await t.step("the owner's own factor cannot be reset from the staff security page", async () => {
        const listed = await owner.request(SignInEndpoints.staffSecurity);
        t.check("the staff security list loads", listed.status === HttpStatus.ok, { status: listed.status });
      });
    } finally {
      await restoreDefaults(t, device);
    }
  },
});

function outcome(result: HttpResult): { status: number; code: string | null } {
  return { status: result.status, code: apiErrorCode(result) };
}

function queryToken(link: string | null): string | null {
  return link === null ? null : new URL(link).searchParams.get("token");
}

function fragmentToken(link: string | null): string | null {
  return link === null ? null : new URLSearchParams(new URL(link).hash.slice(1)).get("token");
}

async function announcedMethod(t: ScenarioContext): Promise<string | null> {
  const result = await t.instance.anonymousSession().request(SignInEndpoints.publicSettings, { auth: "none" });
  return jsonString(result.json, "signInMethod");
}

async function staffIdOf(t: ScenarioContext, owner: SignInClient, email: string): Promise<string | null> {
  const listed = await owner.request(SignInEndpoints.staffSecurity);
  t.require(listed.status === HttpStatus.ok, `the staff security list answered ${listed.status}`);
  const entry = jsonArray(listed.json, "entries").find((candidate) => jsonString(candidate, "email")?.toLowerCase() === email.toLowerCase());
  return jsonString(entry, "staffId");
}

async function ownerStaffId(t: ScenarioContext, owner: SignInClient): Promise<string> {
  const existing = await staffIdOf(t, owner, t.instance.ownerEmail);
  if (existing !== null) return existing;
  const saved = await t.instance.api(SignInEndpoints.ownProfile, { method: "PATCH", json: { language: "en" } });
  t.require(saved.status === HttpStatus.ok, `saving the owner's profile answered ${saved.status}`);
  const created = await staffIdOf(t, owner, t.instance.ownerEmail);
  t.require(created !== null, "the owner has no staff id");
  return created;
}

export const setupPasswordClaim = defineScenario({
  name: "setup-password-claim",
  description: "a fresh instance is claimed with the code and the email, then the sign-in step turns on password sign-in: password sign-in needs the owner's password, a bad length is refused, password login works and no login code is sent or printed",
  topologies: ["standalone", "network", "remote"],
  setupPhase: SetupPhases.unclaimed,
  unsupportedBy: (options) => (options.presetAdminEmail === null ? null : "the owner email is preset (--admin-email), so nobody can claim the instance"),
  async run(t: ScenarioContext) {
    const progress = { claimed: false };
    try {
      await t.step("the code and the owner email claim the instance", async () => {
        t.instance.http.clearSession();
        const printed = await t.instance.latestSetupCode();
        t.require(printed !== null, `no setup code was printed to the ${t.noticeServer} console`);
        const claim = await t.instance.submitClaim(printed.code);
        t.require(claim.status === HttpStatus.ok, `the claim answered ${claim.status} ${apiErrorCode(claim) ?? ""}`);
        progress.claimed = true;
        t.check("a session is issued", issuedSessionKey(claim) !== null, outcome(claim));
        t.check("email codes stay the sign-in method until the sign-in step", (await announcedMethod(t)) === SignInMethods.emailCode);
      });

      await t.step("the sign-in step sets the owner's password and turns on password sign-in", async () => {
        const owner = ownerClient(t);
        const early = await owner.updateSettings(SignInMethods.password, false);
        t.check("409 OWNER_PASSWORD_REQUIRED before a password is set", early.status === HttpStatus.conflict && apiErrorCode(early) === SignInErrors.ownerPasswordRequired, outcome(early));
        const tooShort = await owner.setPassword(TOO_SHORT_PASSWORD);
        t.check("400 PASSWORD_POLICY", tooShort.status === HttpStatus.badRequest && apiErrorCode(tooShort) === SignInErrors.passwordPolicy, outcome(tooShort));
        const set = await owner.setPassword(HARNESS_OWNER_PASSWORD);
        t.check("the password is set", set.status === HttpStatus.ok, outcome(set));
        const switched = await owner.updateSettings(SignInMethods.password, false);
        t.check("password sign-in is on", switched.status === HttpStatus.ok, outcome(switched));
        const confirmed = await t.instance.updateSetupStep("account", SetupStepStatuses.done);
        t.check("the sign-in step is done", setupStepStatus(confirmed, "account") === SetupStepStatuses.done, confirmed.steps);
        t.check("the wizard completes", (await t.instance.completeOpenSteps()).completed);
        t.check("public settings announce password sign-in", (await announcedMethod(t)) === SignInMethods.password);
      });

      await t.step("the owner signs out and back in with the password", async () => {
        const signedOut = await t.instance.api(SignInEndpoints.logout, { method: "POST" });
        t.check("the sign-out is accepted", signedOut.status === HttpStatus.ok || signedOut.status === HttpStatus.noContent, outcome(signedOut));
        t.instance.http.clearSession();
        const login = await t.instance.api(SignInEndpoints.passwordLogin, {
          method: "POST",
          json: { email: t.instance.ownerEmail, password: HARNESS_OWNER_PASSWORD },
        });
        t.check("password login issues a session key", login.status === HttpStatus.ok && issuedSessionKey(login) !== null, outcome(login));
        t.check("the session is the owner's", (await t.instance.profile())?.email.toLowerCase() === t.instance.ownerEmail.toLowerCase());
      });

      await t.step("a login code is neither sent nor printed", async () => {
        const notices = t.env.log(t.noticeServer);
        const since = await notices.mark();
        const sent = await t.instance.anonymousSession().request(SignInEndpoints.sendEmailCode, {
          method: "POST", auth: "none", json: { email: t.instance.ownerEmail },
        });
        t.check("409 SIGN_IN_METHOD_DISABLED", sent.status === HttpStatus.conflict && apiErrorCode(sent) === SignInErrors.methodDisabled, outcome(sent));
        await t.sleep(LOGIN_CODE_PRINT_WINDOW_MS);
        t.check("no login code is on the console", !(await notices.text(since)).some((line) => /login code/i.test(line)));
      });
    } finally {
      if (progress.claimed) {
        await t.instance.ensureOwnerSession();
        await restoreDefaults(t, null);
      }
    }
  },
});

export const passwordStaff = defineScenario({
  name: "password-staff",
  description: "an invited staff member joins with a password, cannot manage credentials, and gets a single-use reset link from the owner that ends old sessions",
  topologies: ["standalone", "remote"],
  async run(t: ScenarioContext) {
    await t.instance.ensureOwnerSession();
    const owner = ownerClient(t);
    const email = `staff-${Date.now().toString(36)}@modl.test`;
    const { client: staff } = freshClient(t);
    const cleanup: { staffId: string | null } = { staffId: null };
    try {
      await t.step("the owner turns on password sign-in", async () => {
        await ensureOwnerPassword(t, owner);
        const switched = await owner.updateSettings(SignInMethods.password, false);
        t.check("the switch is accepted", switched.status === HttpStatus.ok, outcome(switched));
      }, { timeoutMs: RATE_LIMITED_STEP_TIMEOUT_MS });

      const staffId = await t.step("an invitation is inspected and accepted with a password", async () => {
        const invited = await t.instance.api(Endpoints.staffInvite, { method: "POST", json: { email, role: INVITED_ROLE } });
        t.require(invited.status === HttpStatus.created, `the invitation answered ${invited.status}`);
        const token = queryToken(jsonString(jsonArray(invited.json, "inviteLinks")[0], "url"));
        t.require(token !== null, "the invitation has no link");
        const inspected = await staff.request(SignInEndpoints.invitationInspect, "POST", { token });
        t.check("the invitation names the address and asks for a password", inspected.status === HttpStatus.ok
          && jsonString(inspected.json, "email") === email && jsonString(inspected.json, "method") === SignInMethods.password, outcome(inspected));
        const accepted = await staff.request(SignInEndpoints.invitationAccept, "POST", { token, password: STAFF_PASSWORD });
        t.check("accepting issues a session key", accepted.status === HttpStatus.ok && issuedSessionKey(accepted) !== null, outcome(accepted));
        const id = await staffIdOf(t, owner, email);
        cleanup.staffId = id;
        t.require(id !== null, "the new staff member is not listed");
        return id;
      }, { timeoutMs: RATE_LIMITED_STEP_TIMEOUT_MS });

      await t.step("staff can manage neither sign-in settings nor other accounts' credentials", async () => {
        const refusals = {
          list: await staff.request(SignInEndpoints.staffSecurity),
          reset: await staff.request(SignInEndpoints.staffPasswordReset(staffId), "POST"),
          settings: await staff.request(SignInEndpoints.signInSettings),
        };
        for (const [name, refused] of Object.entries(refusals)) t.check(`${name} is 403`, refused.status === FORBIDDEN, outcome(refused));
      });

      await t.step("the owner's own account cannot get a reset link", async () => {
        const refused = await owner.request(SignInEndpoints.staffPasswordReset(await ownerStaffId(t, owner)), "POST");
        t.check("403 OWNER_ACCOUNT_PROTECTED", refused.status === FORBIDDEN && apiErrorCode(refused) === SignInErrors.ownerAccountProtected, outcome(refused));
      });

      await t.step("a reset link works once and ends the old sessions", async () => {
        const created = await owner.request(SignInEndpoints.staffPasswordReset(staffId), "POST");
        const token = fragmentToken(jsonString(created.json, "url"));
        t.require(created.status === HttpStatus.ok && token !== null, `the reset link answered ${created.status}`);
        const { client: visitor } = freshClient(t);
        const used = await visitor.request(SignInEndpoints.passwordReset, "POST", { token, password: STAFF_RESET_PASSWORD });
        t.check("the link sets the password", used.status === HttpStatus.ok && issuedSessionKey(used) === null, { ...outcome(used), sessionKeyIssued: issuedSessionKey(used) !== null, body: used.json });
        const reused = await visitor.request(SignInEndpoints.passwordReset, "POST", { token, password: STAFF_RESET_PASSWORD });
        t.check("a second use is 400 RESET_LINK_INVALID", reused.status === HttpStatus.badRequest
          && apiErrorCode(reused) === SignInErrors.resetLinkInvalid, outcome(reused));
        const old = await staff.request(SignInEndpoints.security);
        t.check("the old session is gone", old.status === HttpStatus.unauthorized, outcome(old));
        const login = await visitor.passwordLogin(email, STAFF_RESET_PASSWORD);
        t.check("the new password signs in", login.status === HttpStatus.ok && issuedSessionKey(login) !== null, outcome(login));
      }, { timeoutMs: RATE_LIMITED_STEP_TIMEOUT_MS });
    } finally {
      if (cleanup.staffId !== null) {
        const removed = await owner.request(SignInEndpoints.staffMember(cleanup.staffId), "DELETE");
        t.evidence("remove the invited staff member", outcome(removed));
      }
      await restoreDefaults(t, null);
    }
  },
});

export const passwordRestart = defineScenario({
  name: "password-restart",
  description: "the password, the sign-in method and the authenticator survive a restart of the server that holds the data",
  topologies: ["standalone"],
  async run(t: ScenarioContext) {
    await t.instance.ensureOwnerSession();
    const owner = ownerClient(t);
    let device: TotpDevice | null = null;
    try {
      await t.step("the owner sets a password, enrols an authenticator and switches to password sign-in", async () => {
        await ensureOwnerPassword(t, owner);
        device = (await owner.enrollTotp({ currentPassword: HARNESS_OWNER_PASSWORD })).device;
        const switched = await owner.updateSettings(SignInMethods.password, false);
        t.check("password sign-in is on", switched.status === HttpStatus.ok, outcome(switched));
      }, { timeoutMs: RATE_LIMITED_STEP_TIMEOUT_MS });

      const server = t.env.state.dataServer;
      await t.step(`graceful restart of ${server}`, async () => {
        const restarted = await t.restart(server);
        t.check("the server stopped cleanly", restarted.stop?.graceful === true, restarted.stop);
      }, { timeoutMs: RESTART_TIMEOUT_MS, allowConsoleErrors: true });

      await t.step("the method, the password and the authenticator survived", async () => {
        t.check("public settings still announce password sign-in", (await announcedMethod(t)) === SignInMethods.password);
        const { client } = freshClient(t);
        const challenge = challengeOf(await client.passwordLogin(t.instance.ownerEmail, HARNESS_OWNER_PASSWORD));
        t.require(challenge !== null && device !== null, "the password sign-in was not challenged");
        const completed = await client.completeSecondFactor(challenge, await device.nextCode());
        t.check("the authenticator completes the sign-in", completed.status === HttpStatus.ok && issuedSessionKey(completed) !== null, outcome(completed));
        const security = await client.request(SignInEndpoints.security);
        t.check("the account still has its password and authenticator", jsonField(security.json, "hasPassword") === true
          && jsonField(security.json, "totpEnabled") === true, outcome(security));
        t.check("the recovery codes are still there", jsonField(security.json, "recoveryCodesRemaining") === RECOVERY_CODE_COUNT, outcome(security));
      }, { timeoutMs: RATE_LIMITED_STEP_TIMEOUT_MS });
    } finally {
      await restoreDefaults(t, device);
    }
  },
});
