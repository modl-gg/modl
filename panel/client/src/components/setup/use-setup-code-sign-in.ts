import { useState } from 'react';
import { useLocation } from 'wouter';
import { useQueryClient } from '@tanstack/react-query';
import type { PanelAuthResponse } from '@proto/modl/v1/auth_pb.ts';
import { useAuth } from '@/hooks/use-auth';
import { toSignInChallenge } from '@/hooks/data/sign-in';
import { useSecondsRemaining } from '@/hooks/use-seconds-remaining';
import { SETUP_STATUS_QUERY_KEY } from '@/hooks/data/setup';
import { serverErrorMessage } from '@/lib/http-error';
import { classifySetupCodeRejection, retryDeadlineOf, type SetupCodeRejection } from '@/lib/setup-code';
import { setupPath } from '@/lib/setup-steps';
import { PUBLIC_SETTINGS_QUERY_KEY } from '@/lib/public-queries';

interface ShownSetupCodeRejection {
  rejection: SetupCodeRejection;
  retryAt: number | null;
  detail: string | null;
}

export function useSetupCodeSignIn() {
  const queryClient = useQueryClient();
  const { refreshUser, challenge, receiveSignInResponse } = useAuth();
  const [, navigate] = useLocation();
  const [shownRejection, setShownRejection] = useState<ShownSetupCodeRejection | null>(null);
  const [sessionLost, setSessionLost] = useState(false);

  const signIn = async (send: () => Promise<PanelAuthResponse>): Promise<SetupCodeRejection | null> => {
    setShownRejection(null);
    try {
      const response = await send();
      if (toSignInChallenge(response) !== null) {
        await receiveSignInResponse(response);
        return null;
      }
    } catch (error) {
      const rejection = classifySetupCodeRejection(error);
      if (rejection.kind === 'phase-changed') {
        await queryClient.invalidateQueries({ queryKey: SETUP_STATUS_QUERY_KEY });
      } else {
        setShownRejection({ rejection, retryAt: retryDeadlineOf(rejection, Date.now()), detail: serverErrorMessage(error) });
      }
      return rejection;
    }
    void queryClient.invalidateQueries({ queryKey: PUBLIC_SETTINGS_QUERY_KEY });
    void queryClient.invalidateQueries({ queryKey: SETUP_STATUS_QUERY_KEY });
    const signedInUser = await refreshUser().catch(() => null);
    if (signedInUser) {
      navigate(setupPath(), { replace: true });
    } else {
      setSessionLost(true);
    }
    return null;
  };

  const coolingDown = useSecondsRemaining(shownRejection?.retryAt ?? null) > 0;

  const continueSetup = () => navigate(setupPath(), { replace: true });

  return { signIn, shownRejection, sessionLost, coolingDown, challenge, continueSetup };
}
