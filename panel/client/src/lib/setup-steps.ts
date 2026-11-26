export const SETUP_STEP_IDS = [
  'account',
  'import',
  'identity',
  'address',
  'email',
  'servers',
  'minecraft-account',
  'staff',
  'player-addresses',
  'ai',
  'replays',
  'content',
] as const;

export type SetupStepId = (typeof SETUP_STEP_IDS)[number];

export type SetupStepStatusId = 'pending' | 'done';

export interface SetupStepProgress {
  step: SetupStepId;
  status: SetupStepStatusId;
}

export const SETUP_CODE_SCREEN = 'code';

export type SetupScreen = typeof SETUP_CODE_SCREEN | SetupStepId;

export const SETUP_CODE_POSITION = 1;

export const SETUP_SCREEN_COUNT = SETUP_CODE_POSITION + SETUP_STEP_IDS.length;

export const LAST_SETUP_STEP: SetupStepId = SETUP_STEP_IDS[SETUP_STEP_IDS.length - 1] ?? 'content';

const SETUP_PATH_PREFIX = '/setup';

function isSetupStepId(value: string): value is SetupStepId {
  return (SETUP_STEP_IDS as readonly string[]).includes(value);
}

export function orderedStepProgress(reported: readonly SetupStepProgress[]): SetupStepProgress[] {
  const statusByStep = new Map(reported.map((entry) => [entry.step, entry.status]));
  return SETUP_STEP_IDS.map((step) => ({ step, status: statusByStep.get(step) ?? 'pending' }));
}

export function firstPendingStep(progress: readonly SetupStepProgress[]): SetupStepId | null {
  return orderedStepProgress(progress).find((entry) => entry.status !== 'done')?.step ?? null;
}

export function landingStep(progress: readonly SetupStepProgress[]): SetupStepId {
  return firstPendingStep(progress) ?? LAST_SETUP_STEP;
}

export function parseSetupStep(segment: string | undefined): SetupStepId | null {
  return segment !== undefined && isSetupStepId(segment) ? segment : null;
}

export function adjacentStep(step: SetupStepId, offset: 1 | -1): SetupStepId | null {
  return SETUP_STEP_IDS[SETUP_STEP_IDS.indexOf(step) + offset] ?? null;
}

export function stepPosition(step: SetupStepId): number {
  return SETUP_CODE_POSITION + SETUP_STEP_IDS.indexOf(step) + 1;
}

export function reachableStep(requested: SetupStepId, progress: readonly SetupStepProgress[]): SetupStepId {
  const open = firstPendingStep(progress);
  if (open === null) return requested;
  return SETUP_STEP_IDS.indexOf(requested) <= SETUP_STEP_IDS.indexOf(open) ? requested : open;
}

export function isStepReachable(step: SetupStepId, progress: readonly SetupStepProgress[]): boolean {
  return reachableStep(step, progress) === step;
}

export interface StepChoiceSources<T extends string> {
  remembered: string | undefined;
  options: readonly T[];
  saved: T | null;
  done: boolean;
  none: T;
  persisted?: T | null | undefined;
}

export function stepChoiceOf<T extends string>({ remembered, options, saved, done, none, persisted = null }: StepChoiceSources<T>): T | null {
  if (saved !== null) return saved;
  const picked = options.find((option) => option === remembered);
  if (picked !== undefined) return picked;
  if (persisted !== null) return persisted;
  return done ? none : null;
}

export interface StepContinueSources<T extends string> {
  value: T | null;
  configured: boolean;
  done: boolean;
  none: T;
}

export function stepContinueReady<T extends string>({ value, configured, done, none }: StepContinueSources<T>): boolean {
  if (value === null) return false;
  return done || value === none || configured;
}

export function lockedStepChoices<T extends string>({ saved, none }: Pick<StepChoiceSources<T>, 'saved' | 'none'>): readonly T[] {
  return saved === null ? [] : [none];
}

export function setupPath(step?: SetupStepId): string {
  return step ? `${SETUP_PATH_PREFIX}/${step}` : SETUP_PATH_PREFIX;
}
