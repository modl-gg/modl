import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Loader2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Notice } from '@/components/ui/notice';
import { useAuth } from '@/hooks/use-auth';
import { useAuthFailureMessage } from '@/hooks/use-auth-failure-message';
import { usePasskeySignIn } from '@/hooks/use-passkey-sign-in';
import { arePasskeysAvailable } from '@/lib/passkeys';
import { acceptsCode, acceptsPasskey, type SignInChallenge } from '@/lib/sign-in-outcome';

interface SecondFactorChallengeProps {
  challenge: SignInChallenge;
  onSignedIn: () => void;
}

export function SecondFactorChallenge({ challenge, onSignedIn }: SecondFactorChallengeProps) {
  const { t } = useTranslation();
  const { cancelChallenge, completeSecondFactor } = useAuth();
  const failureMessage = useAuthFailureMessage();
  const passkey = usePasskeySignIn(onSignedIn);
  const [usesRecoveryCode, setUsesRecoveryCode] = useState(false);
  const [code, setCode] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);
  const passkeysAvailable = arePasskeysAvailable();

  const submit = async () => {
    setSubmitting(true);
    setFailure(null);
    try {
      if (await completeSecondFactor(challenge.challenge, code) === 'signed-in') onSignedIn();
    } catch (error) {
      setFailure(failureMessage(error));
      setCode('');
    } finally {
      setSubmitting(false);
    }
  };

  const switchCodeKind = () => {
    setUsesRecoveryCode((current) => !current);
    setCode('');
    setFailure(null);
  };

  return (
    <div className="space-y-4">
      <p className="font-medium">{t('pages.secondFactor.title')}</p>
      <p className="break-all text-sm text-muted-foreground">{challenge.email}</p>
      {acceptsCode(challenge) && (
        <form
          className="space-y-3"
          onSubmit={(event) => {
            event.preventDefault();
            void submit();
          }}
        >
          <Label htmlFor="second-factor-code">
            {t(usesRecoveryCode ? 'pages.secondFactor.recoveryLabel' : 'pages.secondFactor.codeLabel')}
          </Label>
          <Input
            id="second-factor-code"
            autoComplete="one-time-code"
            inputMode={usesRecoveryCode ? 'text' : 'numeric'}
            maxLength={32}
            value={code}
            onChange={(event) => setCode(event.target.value)}
          />
          <Button type="submit" className="w-full" disabled={submitting || code.trim() === ''}>
            {submitting && <Loader2 className="mr-2 h-4 w-4 animate-spin" aria-hidden="true" />}
            {t('pages.secondFactor.submit')}
          </Button>
          <Button type="button" variant="ghost" size="sm" className="w-full" onClick={switchCodeKind}>
            {t(usesRecoveryCode ? 'pages.secondFactor.useAuthenticator' : 'pages.secondFactor.useRecovery')}
          </Button>
        </form>
      )}
      {failure !== null && <Notice variant="error" announce>{failure}</Notice>}
      {acceptsPasskey(challenge) && (passkeysAvailable ? (
        <Button
          type="button"
          variant="outline"
          className="w-full"
          disabled={passkey.pending}
          onClick={() => { void passkey.signIn(challenge.email); }}
        >
          {t('pages.secondFactor.usePasskey')}
        </Button>
      ) : (
        <Notice variant="warning">{t('pages.secondFactor.passkeyUnavailable')}</Notice>
      ))}
      <Button type="button" variant="ghost" className="w-full" onClick={cancelChallenge}>{t('common.cancel')}</Button>
    </div>
  );
}
