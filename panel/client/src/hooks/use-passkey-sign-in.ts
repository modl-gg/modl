import { useState } from 'react';
import { useAuth } from '@/hooks/use-auth';

export function usePasskeySignIn(onSignedIn: () => void) {
  const { checkPasskeyOptions, loginWithPasskey, loginWithDiscoverablePasskey } = useAuth();
  const [pending, setPending] = useState(false);

  const signIn = async (email: string): Promise<void> => {
    setPending(true);
    try {
      const offered = email === '' ? null : await checkPasskeyOptions(email);
      const signedIn = offered?.hasPasskeys
        ? await loginWithPasskey(offered.challengeId, offered.options)
        : await loginWithDiscoverablePasskey();
      if (signedIn) onSignedIn();
    } finally {
      setPending(false);
    }
  };

  return { pending, signIn };
}
