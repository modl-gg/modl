import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'wouter';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { SignInBrand } from '@/components/brand/BrandLogo';
import { LoadingSpinner } from '@/components/ui/loading-spinner';
import { Notice } from '@/components/ui/notice';
import { NewPasswordFields } from '@/components/auth/NewPasswordFields';
import { usePasswordResetInspection, useResetPassword } from '@/hooks/data/sign-in';
import { useAuthFailureMessage } from '@/hooks/use-auth-failure-message';
import { useNewPassword } from '@/hooks/use-new-password';
import { authFailureOf } from '@/lib/auth-errors';
import { apiErrorCode } from '@/lib/http-error';
import { PANEL_SIGN_IN_PATH } from '@/lib/return-path';

function tokenFromFragment(): string {
  return new URLSearchParams(window.location.hash.slice(1)).get('token') ?? '';
}

function ResetPasswordForm({ token }: { token: string }) {
  const { t } = useTranslation();
  const failureMessage = useAuthFailureMessage();
  const inspection = usePasswordResetInspection(token);
  const reset = useResetPassword();
  const newPassword = useNewPassword();

  if (reset.isSuccess) {
    return (
      <div className="space-y-4">
        <Notice variant="success" announce>{t('pages.resetPassword.done')}</Notice>
        <Button asChild className="w-full">
          <Link href={PANEL_SIGN_IN_PATH}>{t('auth.signIn')}</Link>
        </Button>
      </div>
    );
  }

  if (inspection.isError) {
    return <Notice variant="error" announce>{failureMessage(inspection.error)}</Notice>;
  }

  if (!inspection.data) {
    return <LoadingSpinner className="h-6 w-6 text-primary" />;
  }

  if (reset.isError && authFailureOf(apiErrorCode(reset.error)) === 'reset-link-invalid') {
    return <Notice variant="error" announce>{t('pages.resetPassword.invalid')}</Notice>;
  }

  return (
    <form
      className="space-y-4"
      onSubmit={(event) => {
        event.preventDefault();
        reset.mutate({ token, password: newPassword.password });
      }}
    >
      <p className="break-all text-sm text-muted-foreground">{t('pages.resetPassword.account', { email: inspection.data.email })}</p>
      <NewPasswordFields control={newPassword.fields} account={inspection.data.email} />
      {reset.isError && <Notice variant="error" announce>{failureMessage(reset.error)}</Notice>}
      <Button type="submit" className="w-full" disabled={reset.isPending || !newPassword.ready}>
        {t('pages.resetPassword.submit')}
      </Button>
    </form>
  );
}

const ResetPasswordPage = () => {
  const { t } = useTranslation();
  const [token] = useState(tokenFromFragment);

  useEffect(() => {
    window.history.replaceState(null, '', window.location.pathname);
  }, []);

  return (
    <div className="flex min-h-screen items-center justify-center bg-background p-4">
      <div className="w-full max-w-md">
        <SignInBrand />
        <Card className="shadow-card">
          <CardHeader>
            <CardTitle>
              <h1>{t('pages.resetPassword.title')}</h1>
            </CardTitle>
          </CardHeader>
          <CardContent>
            {token === '' ? <Notice variant="error">{t('pages.resetPassword.invalid')}</Notice> : <ResetPasswordForm token={token} />}
          </CardContent>
        </Card>
      </div>
    </div>
  );
};

export default ResetPasswordPage;
