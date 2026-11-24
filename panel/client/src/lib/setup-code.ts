import { ApiHttpError, apiErrorCode } from './http-error.ts';

const SETUP_CODE_PATTERN = /^[A-HJ-NP-Z2-9]{4}-?[A-HJ-NP-Z2-9]{4}$/;
const SEPARATORS = /[\s-]+/g;

export const SETUP_CODE_INPUT_MAX_LENGTH = 32;

export function normalizeSetupCode(input: string): string {
  return input.toUpperCase().replace(SEPARATORS, '');
}

export function isValidSetupCode(input: string): boolean {
  return SETUP_CODE_PATTERN.test(normalizeSetupCode(input));
}

interface Cooldown {
  kind: 'cooldown';
  retryAfterSeconds: number | null;
}

export type SetupCodeRejection =
  | { kind: 'invalid-code' }
  | { kind: 'locked' }
  | { kind: 'expired' }
  | { kind: 'phase-changed' }
  | { kind: 'invalid-input' }
  | Cooldown
  | { kind: 'failed' };

const REJECTED_CODE_KINDS: ReadonlyMap<string, 'locked' | 'expired'> = new Map<string, 'locked' | 'expired'>([
  ['SETUP_CODE_LOCKED', 'locked'],
  ['SETUP_CODE_EXPIRED', 'expired'],
]);

function cooldownOf(error: ApiHttpError): Cooldown {
  return { kind: 'cooldown', retryAfterSeconds: error.retryAfterSeconds };
}

export function classifySetupCodeRejection(error: unknown): SetupCodeRejection {
  if (!(error instanceof ApiHttpError)) return { kind: 'failed' };
  if (error.status === 401) {
    return { kind: REJECTED_CODE_KINDS.get(apiErrorCode(error) ?? '') ?? 'invalid-code' };
  }
  if (error.status === 400) return { kind: 'invalid-input' };
  if (error.status === 409) return { kind: 'phase-changed' };
  if (error.status === 429) return cooldownOf(error);
  return { kind: 'failed' };
}

const MILLIS_PER_SECOND = 1000;

export function retryDeadlineOf(rejection: SetupCodeRejection, now: number): number | null {
  if (rejection.kind !== 'cooldown' || rejection.retryAfterSeconds === null) return null;
  return now + rejection.retryAfterSeconds * MILLIS_PER_SECOND;
}
