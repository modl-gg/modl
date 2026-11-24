import { useState } from 'react';
import { useAuthFailureMessage } from '@/hooks/use-auth-failure-message';
import { authFailureOf } from '@/lib/auth-errors';
import { apiErrorCode } from '@/lib/http-error';
import type { CredentialProofInput } from '@/hooks/data/sign-in';
import type { ProofKind } from '@/lib/reauthentication';
import { isWebAuthnCancellation } from '@/utils/webauthn';

type ProvenAction = (proof: CredentialProofInput) => Promise<void>;

export interface CredentialProofRequest {
  kind: ProofKind;
  confirming: boolean;
  rejection: string | null;
  confirm: (value: string) => Promise<void>;
  cancel: () => void;
}

interface PendingAction {
  kind: ProofKind;
  action: ProvenAction;
  settle: (proven: boolean) => void;
}

export function useCredentialProof(onFailure: (error: unknown) => void) {
  const failureMessage = useAuthFailureMessage();
  const [pending, setPending] = useState<PendingAction | null>(null);
  const [confirming, setConfirming] = useState(false);
  const [rejection, setRejection] = useState<string | null>(null);

  const run = async (kind: ProofKind, action: ProvenAction): Promise<boolean> => {
    try {
      await action({});
      return true;
    } catch (error) {
      if (authFailureOf(apiErrorCode(error)) === 'reauthentication-required') {
        setRejection(null);
        return new Promise<boolean>((settle) => {
          setPending({ kind, action, settle });
        });
      }
      onFailure(error);
      return false;
    }
  };

  const request: CredentialProofRequest | null = pending === null ? null : {
    kind: pending.kind,
    confirming,
    rejection,
    confirm: async (value) => {
      setConfirming(true);
      setRejection(null);
      try {
        await pending.action(pending.kind === 'password' ? { currentPassword: value } : { code: value });
        setPending(null);
        pending.settle(true);
      } catch (error) {
        if (isWebAuthnCancellation(error)) {
          setPending(null);
          pending.settle(false);
          return;
        }
        setRejection(failureMessage(error));
      } finally {
        setConfirming(false);
      }
    },
    cancel: () => {
      setPending(null);
      pending.settle(false);
    },
  };

  return { run, request };
}
