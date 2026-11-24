import { Redirect, useLocation, useParams } from 'wouter';
import { useTranslation } from 'react-i18next';
import { Loader2 } from 'lucide-react';
import PageContainer from '@/components/layout/PageContainer';
import { Button } from '@/components/ui/button';
import { useAuth } from '@/hooks/use-auth';
import { useStaffTwoFactorRequest, useVerifyStaffTwoFactor, type StaffTwoFactorRequest } from '@/hooks/data/account';
import { ApiHttpError } from '@/lib/http-error';
import { PANEL_SIGN_IN_PATH, signInPathReturningTo } from '@/lib/return-path';
import { formatDateWithTime } from '@/utils/date-utils';

type VerifyState = 'checking' | 'confirm' | 'verifying' | 'verified' | 'invalid' | 'failed';

function failureState(error: unknown): VerifyState {
  return error instanceof ApiHttpError && error.status === 404 ? 'invalid' : 'failed';
}

const STATE_MESSAGES: Record<VerifyState, string> = {
  checking: 'pages.verify.checking',
  confirm: 'pages.verify.confirmPrompt',
  verifying: 'pages.verify.verifying',
  verified: 'pages.verify.success',
  invalid: 'pages.verify.invalid',
  failed: 'pages.verify.failed',
};

function ConnectionDetails({ request }: { request: StaffTwoFactorRequest }) {
  const { t } = useTranslation();
  return (
    <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-left text-sm">
      <dt className="text-muted-foreground">{t('pages.verify.ipAddress')}</dt>
      <dd className="break-all font-mono">{request.ip}</dd>
      <dt className="text-muted-foreground">{t('pages.verify.requestedAt')}</dt>
      <dd>{formatDateWithTime(request.requestedAt)}</dd>
    </dl>
  );
}

const VerifyPage = () => {
  const { t } = useTranslation();
  const { token } = useParams<{ token: string }>();
  const [location] = useLocation();
  const { user, isLoading } = useAuth();
  const request = useStaffTwoFactorRequest(token, Boolean(user));
  const approval = useVerifyStaffTwoFactor();

  if (!isLoading && !user) {
    return <Redirect to={signInPathReturningTo(PANEL_SIGN_IN_PATH, location)} />;
  }

  const currentState = (): VerifyState => {
    if (!token) return 'invalid';
    if (approval.isPending) return 'verifying';
    if (approval.isSuccess) return 'verified';
    if (approval.isError) return failureState(approval.error);
    if (request.isError) return failureState(request.error);
    return request.data ? 'confirm' : 'checking';
  };
  const state = currentState();

  const approve = () => {
    if (token) approval.mutate(token);
  };
  const retry = () => {
    if (approval.isError) {
      approve();
      return;
    }
    void request.refetch();
  };

  return (
    <PageContainer>
      <div className="flex items-center justify-center min-h-[calc(100vh-4rem)]">
        <div className="text-center max-w-md space-y-4">
          <h1 className="text-xl font-semibold">{t('pages.verify.title')}</h1>
          <div className="flex flex-col items-center gap-4">
            {(state === 'checking' || state === 'verifying') && <Loader2 className="h-6 w-6 animate-spin text-primary" aria-hidden="true" />}
            <p role="status" className={state === 'invalid' || state === 'failed' ? 'text-sm text-destructive' : 'text-sm text-muted-foreground'}>{t(STATE_MESSAGES[state])}</p>
            {state === 'confirm' && request.data && (
              <>
                <ConnectionDetails request={request.data} />
                <Button type="button" onClick={approve}>{t('pages.verify.confirm')}</Button>
              </>
            )}
            {state === 'invalid' && user && (
              <p className="text-sm text-muted-foreground">{t('pages.verify.signedInAs', { email: user.email })}</p>
            )}
            {state === 'failed' && (
              <Button type="button" variant="outline" onClick={retry}>
                {t('common.retry')}
              </Button>
            )}
          </div>
        </div>
      </div>
    </PageContainer>
  );
};

export default VerifyPage;
