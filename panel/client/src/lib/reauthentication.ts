export type ProofKind = 'password' | 'code' | 'email-code';

export type SensitiveOperation = 'change-password' | 'manage-totp' | 'add-credential' | 'change-email';

export interface ProofFactors {
  readonly totpEnabled: boolean;
  readonly hasPassword: boolean;
}

export function passwordOperation(factors: ProofFactors): SensitiveOperation {
  return factors.hasPassword ? 'change-password' : 'add-credential';
}

export function authenticatorOperation(factors: ProofFactors): SensitiveOperation {
  return factors.totpEnabled ? 'manage-totp' : 'add-credential';
}

function emailChangeProofKind(factors: ProofFactors): ProofKind {
  if (factors.totpEnabled) return 'code';
  return factors.hasPassword ? 'password' : 'email-code';
}

export function proofKindFor(operation: SensitiveOperation, factors: ProofFactors): ProofKind {
  switch (operation) {
    case 'change-password':
      return 'password';
    case 'manage-totp':
      return 'code';
    case 'add-credential':
      return factors.totpEnabled ? 'code' : 'password';
    case 'change-email':
      return emailChangeProofKind(factors);
  }
}
