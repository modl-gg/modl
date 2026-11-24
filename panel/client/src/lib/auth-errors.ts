export type AuthFailure =
  | 'invalid-credentials'
  | 'too-many-attempts'
  | 'busy'
  | 'method-changed'
  | 'code-invalid'
  | 'challenge-expired'
  | 'reset-link-invalid'
  | 'password-policy'
  | 'current-password-invalid'
  | 'reauthentication-required'
  | 'enrollment-expired'
  | 'last-factor'
  | 'owner-password-required'
  | 'owner-factor-required';

const FAILURES_BY_CODE: ReadonlyMap<string, AuthFailure> = new Map<string, AuthFailure>([
  ['INVALID_CREDENTIALS', 'invalid-credentials'],
  ['TOO_MANY_ATTEMPTS', 'too-many-attempts'],
  ['RATE_LIMITED', 'too-many-attempts'],
  ['PASSWORD_HASHING_BUSY', 'busy'],
  ['SIGN_IN_METHOD_DISABLED', 'method-changed'],
  ['SECOND_FACTOR_INVALID', 'code-invalid'],
  ['SIGN_IN_CHALLENGE_EXPIRED', 'challenge-expired'],
  ['RESET_LINK_INVALID', 'reset-link-invalid'],
  ['PASSWORD_POLICY', 'password-policy'],
  ['CURRENT_PASSWORD_INVALID', 'current-password-invalid'],
  ['REAUTHENTICATION_REQUIRED', 'reauthentication-required'],
  ['TOTP_ENROLLMENT_EXPIRED', 'enrollment-expired'],
  ['LAST_SECOND_FACTOR', 'last-factor'],
  ['OWNER_PASSWORD_REQUIRED', 'owner-password-required'],
  ['OWNER_SECOND_FACTOR_REQUIRED', 'owner-factor-required'],
]);

export const AUTH_FAILURE_KEYS: Readonly<Record<AuthFailure, string>> = {
  'invalid-credentials': 'auth.invalidCredentials',
  'too-many-attempts': 'auth.tooManyAttempts',
  busy: 'auth.busy',
  'method-changed': 'auth.methodChanged',
  'code-invalid': 'pages.secondFactor.invalid',
  'challenge-expired': 'pages.secondFactor.expired',
  'reset-link-invalid': 'pages.resetPassword.invalid',
  'password-policy': 'auth.passwordRules',
  'current-password-invalid': 'settings.password.currentInvalid',
  'reauthentication-required': 'settings.reauth.signInAgain',
  'enrollment-expired': 'settings.authenticator.expired',
  'last-factor': 'settings.authenticator.lastFactor',
  'owner-password-required': 'settings.signIn.ownerPasswordRequired',
  'owner-factor-required': 'settings.signIn.ownerFactorRequired',
};

export function authFailureOf(code: string | null): AuthFailure | null {
  return code === null ? null : FAILURES_BY_CODE.get(code) ?? null;
}

export function authFailureKey(code: string | null, fallbackKey: string): string {
  const failure = authFailureOf(code);
  return failure === null ? fallbackKey : AUTH_FAILURE_KEYS[failure];
}
