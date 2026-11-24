export type SecondFactorOption = 'totp' | 'recovery-code' | 'passkey';

export interface SignInChallenge {
  challenge: string;
  email: string;
  options: SecondFactorOption[];
}

export type SignInOutcome = 'signed-in' | 'challenge' | 'failed';

export function acceptsCode(challenge: SignInChallenge): boolean {
  return challenge.options.includes('totp');
}

export function acceptsPasskey(challenge: SignInChallenge): boolean {
  return challenge.options.includes('passkey');
}

export function signInOutcomeOf(signedIn: boolean, challenge: SignInChallenge | null): SignInOutcome {
  if (challenge !== null) return 'challenge';
  return signedIn ? 'signed-in' : 'failed';
}
