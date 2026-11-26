import { HttpStatus, apiErrorCode, jsonField, jsonString, retryAfterSeconds, type HttpResult, type HttpSession } from "./http";
import { TotpDevice } from "./totp";
import { sleep } from "./util";

export const SignInEndpoints = {
  passwordLogin: "/v1/panel/auth/password/login",
  secondFactor: "/v1/panel/auth/sign-in/second-factor",
  security: "/v1/panel/auth/security",
  password: "/v1/panel/auth/password",
  totp: "/v1/panel/auth/totp",
  totpConfirm: "/v1/panel/auth/totp/confirm",
  totpDisable: "/v1/panel/auth/totp/disable",
  signInSettings: "/v1/panel/settings/sign-in",
  sendEmailCode: "/v1/panel/auth/send-email-code",
  staffSecurity: "/v1/panel/staff-security",
  passwordReset: "/v1/panel/auth/password/reset",
  staffPasswordReset: (staffId: string) => `/v1/panel/staff-security/${encodeURIComponent(staffId)}/password-reset`,
  staffMember: (staffId: string) => `/v1/panel/staff/${encodeURIComponent(staffId)}`,
  invitationInspect: "/v1/public/staff/invitations/inspect",
  invitationAccept: "/v1/public/staff/invitations/accept",
  ownProfile: "/v1/panel/auth/profile",
  logout: "/v1/panel/auth/logout",
  publicSettings: "/v1/public/settings",
} as const;

export const SignInErrors = {
  invalidCredentials: "INVALID_CREDENTIALS",
  methodDisabled: "SIGN_IN_METHOD_DISABLED",
  tooManyAttempts: "TOO_MANY_ATTEMPTS",
  secondFactorInvalid: "SECOND_FACTOR_INVALID",
  enrollmentRequired: "SECOND_FACTOR_ENROLLMENT_REQUIRED",
  ownerFactorRequired: "OWNER_SECOND_FACTOR_REQUIRED",
  lastSecondFactor: "LAST_SECOND_FACTOR",
  ownerAccountProtected: "OWNER_ACCOUNT_PROTECTED",
  ownerPasswordRequired: "OWNER_PASSWORD_REQUIRED",
  passwordPolicy: "PASSWORD_POLICY",
  resetLinkInvalid: "RESET_LINK_INVALID",
  rateLimited: "RATE_LIMITED",
} as const;

const RATE_LIMIT_WAIT_CAP_SECONDS = 60;

export const SignInMethods = { emailCode: "SIGN_IN_METHOD_EMAIL_CODE", password: "SIGN_IN_METHOD_PASSWORD" } as const;

export const HARNESS_OWNER_PASSWORD = "modl harness owner password";

export class SignInError extends Error {
  readonly result: HttpResult;

  constructor(action: string, result: HttpResult) {
    super(`${action} failed with ${result.status} ${apiErrorCode(result) ?? ""}`.trim());
    this.result = result;
  }
}

export function challengeOf(result: HttpResult): string | null {
  const pending = jsonField(result.json, "secondFactor");
  return jsonString(pending, "challenge");
}

export function issuedSessionKey(result: HttpResult): string | null {
  const key = jsonString(result.json, "sessionKey");
  return key === null || key === "" ? null : key;
}

export interface SignInSettings {
  method: string;
  secondFactorRequired: boolean;
  version: number;
}

export class SignInClient {
  private readonly session: HttpSession;

  constructor(session: HttpSession) {
    this.session = session;
  }

  async request(path: string, method: "GET" | "POST" | "PUT" | "DELETE" = "GET", json?: unknown): Promise<HttpResult> {
    const send = async () => this.session.request(path, json === undefined ? { method } : { method, json });
    const result = await send();
    if (result.status !== HttpStatus.tooManyRequests || apiErrorCode(result) !== SignInErrors.rateLimited) return result;
    const seconds = retryAfterSeconds(result) ?? RATE_LIMIT_WAIT_CAP_SECONDS;
    await sleep(Math.min(seconds, RATE_LIMIT_WAIT_CAP_SECONDS) * 1000);
    return send();
  }

  async settings(): Promise<SignInSettings> {
    const result = await this.request(SignInEndpoints.signInSettings);
    if (result.status !== HttpStatus.ok) throw new SignInError("reading the sign-in settings", result);
    return {
      method: jsonString(result.json, "method") ?? SignInMethods.emailCode,
      secondFactorRequired: jsonField(result.json, "secondFactorRequired") === true,
      version: Number(jsonField(result.json, "version") ?? 0),
    };
  }

  async updateSettings(method: string, secondFactorRequired: boolean): Promise<HttpResult> {
    const current = await this.settings();
    return this.request(SignInEndpoints.signInSettings, "PUT", { expectedVersion: String(current.version), method, secondFactorRequired });
  }

  async setPassword(newPassword: string, currentPassword?: string): Promise<HttpResult> {
    const proof = currentPassword === undefined ? {} : { currentPassword };
    return this.request(SignInEndpoints.password, "PUT", { newPassword, proof });
  }

  async passwordLogin(email: string, password: string): Promise<HttpResult> {
    return this.request(SignInEndpoints.passwordLogin, "POST", { email, password });
  }

  async completeSecondFactor(challenge: string, code: string): Promise<HttpResult> {
    return this.request(SignInEndpoints.secondFactor, "POST", { challenge, code });
  }

  async enrollTotp(proof: Record<string, string> = {}): Promise<{ device: TotpDevice; recoveryCodes: string[] }> {
    const started = await this.request(SignInEndpoints.totp, "POST", { proof });
    const secret = jsonString(started.json, "secret");
    if (started.status !== HttpStatus.ok || secret === null) throw new SignInError("starting authenticator enrolment", started);
    const device = new TotpDevice(secret);
    const confirmed = await this.request(SignInEndpoints.totpConfirm, "POST", { code: await device.nextCode() });
    const codes = jsonField(confirmed.json, "codes");
    if (confirmed.status !== HttpStatus.ok || !Array.isArray(codes)) throw new SignInError("confirming authenticator enrolment", confirmed);
    return { device, recoveryCodes: codes.filter((code): code is string => typeof code === "string") };
  }

  async disableTotp(code: string): Promise<HttpResult> {
    return this.request(SignInEndpoints.totpDisable, "POST", { proof: { code } });
  }
}
