import type { SetupPhaseId } from '@/hooks/data/setup';

export const SETUP_TITLE_ID = 'setup-title';

export function setupCodeIntroKey(phase: SetupPhaseId): string {
  return phase === 'owner-sign-in' ? 'pages.setup.ownerSignIn.intro' : 'pages.setup.code.introClaim';
}
