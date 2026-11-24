import { useState } from 'react';
import { useLocation } from 'wouter';
import { useTranslation } from 'react-i18next';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { SignInBrand } from '@/components/brand/BrandLogo';
import { Notice } from '@/components/ui/notice';
import { LoadingSpinner } from '@/components/ui/loading-spinner';
import { SecondFactorChallenge } from '@/components/auth/SecondFactorChallenge';
import { useAuth } from '@/hooks/use-auth';
import { toSignInChallenge } from '@/hooks/data/sign-in';
import { useConsumeLoginLink } from '@/hooks/data/account';
import { useCapturedSearchParam } from '@/hooks/use-search-param';
import { ApiHttpError } from '@/lib/http-error';

type LoginLinkState = 'confirm' | 'signing-in' | 'second-factor' | 'expired' | 'rate-limited' | 'unavailable' | 'session-lost';

const REJECTED_LINK_STATUSES = new Set([400, 401, 404]);

function classifyLoginLinkFailure(error: unknown): LoginLinkState {
  if (error instanceof ApiHttpError) {
    if (REJECTED_LINK_STATUSES.has(error.status)) return 'expired';
    if (error.status === 429) return 'rate-limited';
  }
  return 'unavailable';
}

const FAILURE_COPY: Record<Exclude<LoginLinkState, 'confirm' | 'signing-in' | 'second-factor'>, { title: string; description: string }> = {
  expired: { title: 'pages.authLink.expiredTitle', description: 'pages.authLink.expiredDesc' },
  'rate-limited': { title: 'pages.authLink.rateLimitedTitle', description: 'pages.authLink.rateLimitedDesc' },
  unavailable: { title: 'pages.authLink.unavailableTitle', description: 'pages.authLink.unavailableDesc' },
  'session-lost': { title: 'pages.authLink.sessionLostTitle', description: 'pages.authLink.sessionLostDesc' },
};

const AuthLinkPage = () => {
  const { t } = useTranslation();
  const [, navigate] = useLocation();
  const { refreshUser, challenge, receiveSignInResponse } = useAuth();
  const { mutateAsync: consumeLoginLink, isPending: isConsumingLink } = useConsumeLoginLink();
  const token = useCapturedSearchParam('token');
  const [state, setState] = useState<LoginLinkState>(token ? 'confirm' : 'expired');

  const signIn = async (loginToken: string) => {
    setState('signing-in');
    try {
      const response = await consumeLoginLink(loginToken);
      if (toSignInChallenge(response) !== null) {
        await receiveSignInResponse(response);
        setState('second-factor');
        return;
      }
    } catch (error) {
      setState(classifyLoginLinkFailure(error));
      return;
    }
    const signedInUser = await refreshUser().catch(() => null);
    if (!signedInUser) {
      setState('session-lost');
      return;
    }
    navigate('/panel');
  };

  const failure = state === 'signing-in' || state === 'confirm' || state === 'second-factor' ? null : FAILURE_COPY[state];
  const canRetry = state === 'unavailable' || state === 'rate-limited';

  return (
    <div className="min-h-screen flex items-center justify-center bg-background p-4">
      <div className="w-full max-w-md">
        <SignInBrand />
        <Card className="shadow-card">
          <CardHeader>
            <CardTitle>{t('pages.authLink.title')}</CardTitle>
            {state === 'confirm' && <CardDescription>{t('pages.authLink.confirmDesc')}</CardDescription>}
          </CardHeader>
          <CardContent>
            {failure ? (
              <div className="space-y-4">
                <Notice variant="error" title={t(failure.title)} announce>
                  {t(failure.description)}
                </Notice>
                {canRetry && (
                  <Button type="button" className="w-full" onClick={() => { void signIn(token); }} disabled={isConsumingLink}>
                    {t('common.retry')}
                  </Button>
                )}
                <Button type="button" variant="outline" className="w-full" onClick={() => navigate('/panel/auth')}>
                  {t('pages.authLink.signInAnotherWay')}
                </Button>
              </div>
            ) : state === 'second-factor' ? (
              challenge ? (
                <SecondFactorChallenge challenge={challenge} onSignedIn={() => navigate('/panel')} />
              ) : (
                <Button type="button" variant="outline" className="w-full" onClick={() => navigate('/panel/auth')}>
                  {t('pages.authLink.signInAnotherWay')}
                </Button>
              )
            ) : state === 'confirm' ? (
              <Button type="button" className="w-full" onClick={() => { void signIn(token); }}>
                {t('pages.authLink.confirm')}
              </Button>
            ) : (
              <div className="flex justify-center py-6">
                <LoadingSpinner className="h-6 w-6 text-primary" label={t('pages.authLink.signingIn')} />
              </div>
            )}
          </CardContent>
        </Card>
      </div>
    </div>
  );
};

export default AuthLinkPage;
