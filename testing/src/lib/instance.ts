import {
  ConsolePatterns,
  Endpoints,
  findLoginCode,
  isSetupPhase,
  SETUP_CODE_FILE_CODE,
  SETUP_STEPS,
  setupCodeKind,
  SetupErrors,
  SetupPhases,
  setupStepEnum,
  SetupStepStatuses,
  SETUP_REPLAY_CHOICE,
  type ReplaySettings,
  type SetupCodeKind,
  type SetupPhase,
  type SetupStep,
} from "./contract";
import { apiErrorCode, HttpSession, HttpStatus, jsonArray, jsonField, jsonString, type ByteResult, type HttpRequest, type HttpResult } from "./http";
import type { Logger } from "./log";
import type { ContainerLog, LogLine } from "./logs";
import { HARNESS_OWNER_PASSWORD, SignInClient, SignInEndpoints, SignInMethods, issuedSessionKey } from "./sign-in";
import type { InstanceState } from "./state";
import { sleep, truncate } from "./util";

export interface SetupCode {
  code: string;
  url: string;
  kind: SetupCodeKind;
  codeFile: string | null;
  ts: string;
}

export interface SetupCodeFile {
  path: string;
  code: string | null;
  kind: SetupCodeKind;
  text: string;
}

export interface NoticeHost {
  readFile(path: string): Promise<string | null>;
  setupCodeFile(): string;
  dataDirectory(): string;
  restartForNewSetupCode(): Promise<void>;
}

export const SETUP_CODE_SOURCES = ["console", "file"] as const;
export type SetupCodeSource = (typeof SETUP_CODE_SOURCES)[number];

export interface SetupStatus {
  claimed: boolean;
  phase: string;
  host: string;
}

export interface SetupStepState {
  step: string;
  status: string;
}

export interface SetupWizardState {
  completed: boolean;
  steps: SetupStepState[];
  environment: { host: string; reverseProxySuspected: boolean };
}

export interface Profile {
  email: string;
  username: string;
  role: string;
  minecraftUuid: string;
  minecraftUsername: string;
}

export type SessionPath = "existing-session" | "claim" | "owner-code" | "email-code" | "password";

type NewSessionPath = Exclude<SessionPath, "existing-session">;

export interface SessionOutcome {
  path: SessionPath;
  profile: Profile;
}

export interface MinecraftLinkCode {
  code: string;
  command: string;
  expiresInSeconds: number;
}

export class InstanceError extends Error {
  readonly result: HttpResult | undefined;

  constructor(message: string, result?: HttpResult) {
    super(result ? `${message} (HTTP ${result.status}: ${truncate(result.text, 300)})` : message);
    this.result = result;
  }
}

export function setupStepStatus(state: SetupWizardState, step: SetupStep): string | null {
  return state.steps.find((entry) => entry.step === setupStepEnum(step))?.status ?? null;
}

const LOGIN_CODE_COOLDOWN_MS = 61_000;
const NOTICE_TIMEOUT_MS = 25_000;
const RENEWABLE_CODE_ERRORS: ReadonlySet<string> = new Set([SetupErrors.codeExpired, SetupErrors.codeInvalid, SetupErrors.codeLocked]);

const SESSION_PATHS: Record<SetupPhase, NewSessionPath> = {
  [SetupPhases.unclaimed]: "claim",
  [SetupPhases.ownerSignIn]: "owner-code",
  [SetupPhases.ready]: "email-code",
};

function printedCodeFile(following: readonly LogLine[]): string | null {
  for (const line of following) {
    const match = ConsolePatterns.setupCodeFile.exec(line.text);
    if (match?.[1]) return match[1];
  }
  return null;
}

function parseWizardState(json: unknown): SetupWizardState {
  const environment = jsonField(json, "environment");
  return {
    completed: jsonField(json, "completed") === true,
    steps: jsonArray(json, "steps").map((entry) => ({ step: jsonString(entry, "step") ?? "", status: jsonString(entry, "status") ?? "" })),
    environment: {
      host: jsonString(environment, "host") ?? "",
      reverseProxySuspected: jsonField(environment, "reverseProxySuspected") === true,
    },
  };
}

export class InstanceClient {
  readonly http: HttpSession;
  private readonly instance: InstanceState;
  private readonly notices: ContainerLog;
  private readonly noticeHost: NoticeHost;
  private readonly persist: (instance: InstanceState) => void;
  private readonly logger: Logger;

  constructor(
    instance: InstanceState,
    notices: ContainerLog,
    noticeHost: NoticeHost,
    persist: (instance: InstanceState) => void,
    logger: Logger,
  ) {
    this.instance = instance;
    this.notices = notices;
    this.noticeHost = noticeHost;
    this.persist = persist;
    this.logger = logger;
    this.http = new HttpSession(instance.baseUrl, () => this.instance.apiKey, {
      cookies: instance.cookies,
      sessionKey: instance.sessionKey ?? null,
    });
  }

  get baseUrl(): string {
    return this.instance.baseUrl;
  }

  get ownerEmail(): string {
    return this.instance.ownerEmail;
  }

  get apiKey(): string | null {
    return this.instance.apiKey;
  }

  anonymousSession(): HttpSession {
    return new HttpSession(this.instance.baseUrl, () => this.instance.apiKey);
  }

  async api(path: string, request?: HttpRequest): Promise<HttpResult> {
    return this.http.request(path, request).finally(() => this.saveSession());
  }

  async apiWithCredentials(path: string, request: HttpRequest = {}): Promise<HttpResult> {
    const auth = request.auth ?? "session";
    if (auth === "session") await this.ensureOwnerSession();
    if (auth === "api-key" && !this.apiKey) {
      await this.ensureOwnerSession();
      await this.revealApiKey();
    }
    return this.api(path, request);
  }

  async bytesWithCredentials(path: string, range?: string): Promise<ByteResult> {
    await this.ensureOwnerSession();
    return this.http.bytes(path, range === undefined ? { auth: "session" } : { auth: "session", range });
  }

  async isHealthy(): Promise<boolean> {
    try {
      const result = await this.http.request(Endpoints.health, { auth: "none", timeoutMs: 5000 });
      return result.status === 200;
    } catch {
      return false;
    }
  }

  async waitHealthy(timeoutMs: number): Promise<void> {
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
      if (await this.isHealthy()) return;
      await sleep(1000);
    }
    throw new InstanceError(`panel at ${this.baseUrl} did not report healthy within ${timeoutMs}ms`);
  }

  async setupStatus(): Promise<SetupStatus> {
    const result = await this.http.request(Endpoints.setupStatus, { auth: "none" });
    if (result.status !== 200) throw new InstanceError("setup status request failed", result);
    return {
      claimed: jsonField(result.json, "claimed") === true,
      phase: jsonString(result.json, "phase") ?? "",
      host: jsonString(result.json, "host") ?? "",
    };
  }

  async isClaimed(): Promise<boolean> {
    return (await this.setupStatus()).claimed;
  }

  async latestSetupCode(): Promise<SetupCode | null> {
    const lines = await this.notices.lines();
    const index = lines.findLastIndex((line) => ConsolePatterns.setupCode.test(line.text));
    const line = lines[index];
    const match = line ? ConsolePatterns.setupCode.exec(line.text) : null;
    if (!line || !match?.[1] || !match[2]) return null;
    return { code: match[1], url: match[2], kind: setupCodeKind(match[0]), codeFile: printedCodeFile(lines.slice(index + 1)), ts: line.ts };
  }

  setupCodeFilePath(): string {
    return this.noticeHost.setupCodeFile();
  }

  dataDirectoryPath(): string {
    return this.noticeHost.dataDirectory();
  }

  async readSetupCodeFile(path?: string): Promise<SetupCodeFile | null> {
    const location = path ?? this.setupCodeFilePath();
    const text = await this.noticeHost.readFile(location);
    if (text === null) return null;
    return { path: location, code: SETUP_CODE_FILE_CODE.exec(text)?.[1] ?? null, kind: setupCodeKind(text), text };
  }

  async setupCode(source: SetupCodeSource): Promise<SetupCode | SetupCodeFile | null> {
    switch (source) {
      case "console":
        return this.latestSetupCode();
      case "file":
        return this.readSetupCodeFile();
    }
  }

  claimRequest(code: string): HttpRequest {
    return { method: "POST", json: { code, owner: { email: this.instance.ownerEmail }, publicUrl: this.baseUrl } };
  }

  ownerSignInRequest(code: string): HttpRequest {
    return { method: "POST", json: { code } };
  }

  async submitClaim(code: string): Promise<HttpResult> {
    return this.api(Endpoints.setupClaim, this.claimRequest(code));
  }

  async submitOwnerSignIn(code: string): Promise<HttpResult> {
    return this.api(Endpoints.setupSignIn, this.ownerSignInRequest(code));
  }

  async claim(): Promise<HttpResult> {
    const claimed = await this.redeemSetupCode("the setup claim", async (code) => this.submitClaim(code));
    await this.completeOpenSteps();
    return claimed;
  }

  async signInWithOwnerCode(): Promise<HttpResult> {
    const signedIn = await this.redeemSetupCode("the owner sign-in", async (code) => this.submitOwnerSignIn(code));
    await this.completeOpenSteps();
    return signedIn;
  }

  async setupWizard(): Promise<SetupWizardState> {
    return this.wizardRequest("reading the setup wizard", Endpoints.setupState);
  }

  async updateSetupStep(step: SetupStep, status: string): Promise<SetupWizardState> {
    return this.wizardRequest(`updating the ${step} setup step`, Endpoints.setupStep(step), { method: "PUT", json: { status } });
  }

  async completeOpenSteps(): Promise<SetupWizardState> {
    let state = await this.setupWizard();
    for (const step of SETUP_STEPS) {
      if (setupStepStatus(state, step) === SetupStepStatuses.done) continue;
      state = await this.completeStep(step);
    }
    return state;
  }

  async completeStep(step: SetupStep): Promise<SetupWizardState> {
    if (step === "player-addresses") await this.chooseNoAddressLookups();
    if (step === "replays") await this.updateReplaySettings(SETUP_REPLAY_CHOICE);
    return this.updateSetupStep(step, SetupStepStatuses.done);
  }

  async replaySettings(): Promise<{ version: number; settings: ReplaySettings }> {
    const result = await this.apiWithCredentials(Endpoints.replaySettings);
    if (result.status !== HttpStatus.ok) throw new InstanceError("reading the replay settings failed", result);
    const data = jsonField(result.json, "data");
    return {
      version: Number(jsonField(jsonField(result.json, "_meta"), "version") ?? 0),
      settings: {
        enabled: jsonField(data, "enabled") === true,
        recordEveryPlayer: jsonField(data, "recordEveryPlayer") === true,
        recordOnAnticheatFlag: jsonField(data, "recordOnAnticheatFlag") === true,
        anticheatIntegration: jsonField(data, "anticheatIntegration") === true,
        windowSeconds: Number(jsonField(data, "windowSeconds") ?? 0),
        radiusBlocks: Number(jsonField(data, "radiusBlocks") ?? 0),
        memoryBudgetMb: Number(jsonField(data, "memoryBudgetMb") ?? 0),
        anticheatName: jsonString(data, "anticheatName") ?? "",
      },
    };
  }

  async updateReplaySettings(change: Partial<ReplaySettings>): Promise<ReplaySettings> {
    const current = await this.replaySettings();
    const settings = { ...current.settings, ...change };
    const result = await this.apiWithCredentials(Endpoints.replaySettings, {
      method: "PUT",
      json: { expectedVersion: String(current.version), settings },
    });
    if (result.status !== HttpStatus.ok) throw new InstanceError("saving the replay settings failed", result);
    return settings;
  }

  private async chooseNoAddressLookups(): Promise<void> {
    const result = await this.api(Endpoints.playerAddressSettings, { method: "PUT", json: { provider: "" } });
    if (result.status !== HttpStatus.ok) throw new InstanceError("choosing no player address lookups failed", result);
  }

  private async wizardRequest(action: string, path: string, request?: HttpRequest): Promise<SetupWizardState> {
    const result = await this.api(path, request);
    if (result.status !== HttpStatus.ok) throw new InstanceError(`${action} failed`, result);
    return parseWizardState(result.json);
  }

  private async requireLatestSetupCode(): Promise<SetupCode> {
    const code = await this.latestSetupCode();
    if (!code) throw new InstanceError(`no setup code was printed to the ${this.instance.noticeServer} console`);
    return code;
  }

  private async redeemSetupCode(action: string, submit: (code: string) => Promise<HttpResult>): Promise<HttpResult> {
    const first = await submit((await this.requireLatestSetupCode()).code);
    if (first.status === HttpStatus.ok) return first;
    const refusal = apiErrorCode(first);
    if (refusal === null || !RENEWABLE_CODE_ERRORS.has(refusal)) throw new InstanceError(`${action} failed`, first);
    await this.noticeHost.restartForNewSetupCode();
    this.logger.info(`${action} was refused (${refusal}); a restart of the ${this.instance.noticeServer} printed a new setup code`);
    const retry = await submit((await this.requireLatestSetupCode()).code);
    if (retry.status !== HttpStatus.ok) throw new InstanceError(`${action} failed with a new setup code`, retry);
    return retry;
  }

  async loginWithEmailCode(email = this.instance.ownerEmail): Promise<HttpResult> {
    const waitMs = (this.instance.lastLoginCodeAt ?? 0) + LOGIN_CODE_COOLDOWN_MS - Date.now();
    if (waitMs > 0) {
      this.logger.info(`waiting ${Math.ceil(waitMs / 1000)}s for the login code cooldown`);
      await sleep(waitMs);
    }
    const since = await this.notices.mark();
    const sent = await this.http.request(Endpoints.sendEmailCode, { method: "POST", json: { email }, auth: "none" });
    this.instance.lastLoginCodeAt = Date.now();
    this.persist(this.instance);
    if (sent.status !== 200) throw new InstanceError("sending the login code failed", sent);
    const code = await this.awaitLoginCode(since, email);
    const verified = await this.http.request(Endpoints.verifyEmailCode, { method: "POST", json: { email, code } });
    this.saveSession();
    if (verified.status !== 200 || jsonField(verified.json, "success") === false) {
      throw new InstanceError("verifying the login code failed", verified);
    }
    return verified;
  }

  private async awaitLoginCode(since: string, email: string): Promise<string> {
    const deadline = Date.now() + NOTICE_TIMEOUT_MS;
    while (Date.now() < deadline) {
      const code = findLoginCode(await this.notices.text(since), email);
      if (code) return code;
      await sleep(500);
    }
    throw new InstanceError(`no login code for ${email} appeared on the ${this.instance.noticeServer} console`);
  }

  async profile(): Promise<Profile | null> {
    if (!this.http.hasSession()) return null;
    const result = await this.api(Endpoints.profile);
    if (result.status !== 200) return null;
    return {
      email: jsonString(result.json, "email") ?? "",
      username: jsonString(result.json, "username") ?? "",
      role: jsonString(result.json, "role") ?? "",
      minecraftUuid: jsonString(result.json, "minecraftUuid") ?? "",
      minecraftUsername: jsonString(result.json, "minecraftUsername") ?? "",
    };
  }

  async ensureOwnerSession(): Promise<SessionOutcome> {
    const existing = await this.profile();
    if (existing) return { path: "existing-session", profile: existing };
    return this.newOwnerSession();
  }

  async newOwnerSession(): Promise<SessionOutcome> {
    this.http.clearSession();
    const { phase } = await this.setupStatus();
    if (!isSetupPhase(phase)) throw new InstanceError(`the panel reported an unknown setup phase '${phase}'`);
    const path = phase === SetupPhases.ready && (await this.usesPasswordSignIn()) ? "password" : SESSION_PATHS[phase];
    await this.openSession(path);
    const profile = await this.profile();
    if (!profile) throw new InstanceError(`owner session via ${path} did not yield a profile`);
    return { path, profile };
  }

  private async openSession(path: NewSessionPath): Promise<HttpResult> {
    switch (path) {
      case "claim":
        return this.claim();
      case "owner-code":
        return this.signInWithOwnerCode();
      case "email-code":
        return this.loginWithEmailCode();
      case "password":
        return this.loginWithHarnessPassword();
    }
  }

  private async usesPasswordSignIn(): Promise<boolean> {
    const result = await this.http.request(SignInEndpoints.publicSettings, { auth: "none" });
    if (result.status !== HttpStatus.ok) throw new InstanceError("reading the public settings failed", result);
    return jsonString(result.json, "signInMethod") === SignInMethods.password;
  }

  private async loginWithHarnessPassword(): Promise<HttpResult> {
    const signedIn = await new SignInClient(this.http).passwordLogin(this.instance.ownerEmail, HARNESS_OWNER_PASSWORD);
    if (signedIn.status !== HttpStatus.ok || issuedSessionKey(signedIn) === null) {
      throw new InstanceError("signing the owner in with the harness password failed", signedIn);
    }
    this.saveSession();
    return signedIn;
  }

  async revealApiKey(): Promise<string> {
    const result = await this.api(Endpoints.revealApiKey);
    const apiKey = jsonString(result.json, "apiKey");
    if (result.status !== 200 || !apiKey) throw new InstanceError("revealing the API key failed", result);
    this.instance.apiKey = apiKey;
    this.persist(this.instance);
    return apiKey;
  }

  async createMinecraftLinkCode(): Promise<MinecraftLinkCode> {
    const result = await this.api(Endpoints.minecraftLink, { method: "POST" });
    const code = jsonString(result.json, "code");
    if (result.status !== 200 || !code) throw new InstanceError("creating a Minecraft link code failed", result);
    return {
      code,
      command: jsonString(result.json, "command") ?? "",
      expiresInSeconds: Number(jsonField(result.json, "expiresInSeconds") ?? 0),
    };
  }

  async unlinkMinecraft(): Promise<HttpResult> {
    return this.api(Endpoints.minecraftLink, { method: "DELETE" });
  }

  async consumeLoginLink(token: string): Promise<{ result: HttpResult; session: HttpSession }> {
    const session = this.anonymousSession();
    const result = await session.request(Endpoints.consumeLoginLink, { method: "POST", json: { token } });
    return { result, session };
  }

  private saveSession(): void {
    const credentials = this.http.credentials();
    this.instance.cookies = credentials.cookies;
    this.instance.sessionKey = credentials.sessionKey;
    this.persist(this.instance);
  }
}
