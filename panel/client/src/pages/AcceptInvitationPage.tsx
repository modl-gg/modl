import { useEffect, useRef, useState } from 'react';
import { useLocation } from 'wouter';
import PageContainer from '@/components/layout/PageContainer';
import { Button } from '@/components/ui/button';
import { Notice } from '@/components/ui/notice';
import { NewPasswordFields } from '@/components/auth/NewPasswordFields';
import { useAuth } from '@/hooks/use-auth';
import { useAuthFailureMessage } from '@/hooks/use-auth-failure-message';
import { useNewPassword } from '@/hooks/use-new-password';
import { useToast } from '@/hooks/use-toast';
import { useTranslation } from 'react-i18next';
import type { TFunction } from 'i18next';
import {
  classifyAcceptInvitationFailure,
  useAcceptInvitation,
  type AcceptInvitationFailure,
} from '@/hooks/data/staff';
import { toSignInChallenge, useInvitationInspection } from '@/hooks/data/sign-in';
import { authFailureOf } from '@/lib/auth-errors';
import { apiErrorCode } from '@/lib/http-error';
import { PANEL_SIGN_IN_PATH } from '@/lib/return-path';

type InvitationStatus = 'verifying' | 'accepted' | AcceptInvitationFailure;

function statusMessage(status: InvitationStatus, t: TFunction): string {
  switch (status) {
    case 'verifying':
      return t('pages.acceptInvitation.verifying');
    case 'accepted':
      return t('pages.acceptInvitation.accepted');
    case 'already-staff':
      return t('pages.acceptInvitation.alreadyStaff');
    case 'invalid-link':
      return t('pages.acceptInvitation.invalidLink');
    case 'unknown':
      return t('pages.acceptInvitation.error');
  }
}

function invitationToken(): string {
  return new URLSearchParams(window.location.search).get('token') ?? '';
}

const AcceptInvitationPage = () => {
  const { t } = useTranslation();
  const [, navigate] = useLocation();
  const { user, isLoading, receiveSignInResponse } = useAuth();
  const { toast } = useToast();
  const failureMessage = useAuthFailureMessage();
  const [token] = useState(invitationToken);
  const newPassword = useNewPassword();
  const acceptance = useAcceptInvitation();
  const { mutate: accept } = acceptance;
  const inspection = useInvitationInspection(token, !isLoading && !user && !acceptance.isSuccess);
  const { refetch: inspectAgain } = inspection;
  const redirectedRef = useRef(false);
  const submittedRef = useRef(false);
  const invitation = inspection.data;

  useEffect(() => {
    if (isLoading || !user || submittedRef.current || redirectedRef.current) return;
    redirectedRef.current = true;
    toast({
      title: t('pages.acceptInvitation.alreadyLoggedInTitle'),
      description: t('pages.acceptInvitation.alreadyLoggedInDesc'),
      variant: 'destructive',
    });
    navigate('/panel');
  }, [user, isLoading, navigate, toast, t]);

  useEffect(() => {
    if (user || invitation?.method !== 'email-code' || submittedRef.current) return;
    submittedRef.current = true;
    accept({ token }, {
      onSuccess: () => {
        window.location.href = '/panel';
      },
      onError: (error) => {
        if (authFailureOf(apiErrorCode(error)) !== null || apiErrorCode(error) === 'PASSWORD_REQUIRED') void inspectAgain();
      },
    });
  }, [user, invitation, token, accept, inspectAgain]);

  const join = () => {
    submittedRef.current = true;
    accept({ token, password: newPassword.password }, {
      onSuccess: (response) => {
        if (toSignInChallenge(response) === null) {
          window.location.href = '/panel';
          return;
        }
        void receiveSignInResponse(response).then(() => navigate(PANEL_SIGN_IN_PATH), () => navigate(PANEL_SIGN_IN_PATH));
      },
    });
  };

  const passwordRejected = acceptance.isError
    && (authFailureOf(apiErrorCode(acceptance.error)) !== null || apiErrorCode(acceptance.error) === 'PASSWORD_REQUIRED');
  const asksForPassword = invitation?.method === 'password' && !acceptance.isSuccess && (!acceptance.isError || passwordRejected);

  const status = ((): InvitationStatus => {
    if (token === '') return 'invalid-link';
    if (acceptance.isSuccess) return 'accepted';
    if (acceptance.isError) return classifyAcceptInvitationFailure(acceptance.error);
    if (inspection.isError) return classifyAcceptInvitationFailure(inspection.error);
    return 'verifying';
  })();

  return (
    <PageContainer>
      <div className="flex items-center justify-center h-screen">
        <div className="w-full max-w-sm text-center">
          <h1 className="text-2xl font-bold mb-4">{t('pages.acceptInvitation.title')}</h1>
          {asksForPassword ? (
            <form
              className="space-y-4 text-left"
              onSubmit={(event) => {
                event.preventDefault();
                join();
              }}
            >
              <p className="break-all">{t('pages.acceptInvitation.choosePassword', { email: invitation.email })}</p>
              <NewPasswordFields control={newPassword.fields} account={invitation.email} />
              {passwordRejected && <Notice variant="error" announce>{failureMessage(acceptance.error)}</Notice>}
              <Button type="submit" className="w-full" disabled={acceptance.isPending || !newPassword.ready}>
                {t('pages.acceptInvitation.submit')}
              </Button>
            </form>
          ) : (
            <p>{statusMessage(status, t)}</p>
          )}
        </div>
      </div>
    </PageContainer>
  );
};

export default AcceptInvitationPage;
