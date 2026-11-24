import { type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { Redirect } from 'wouter';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { LoadingSpinner } from '@/components/ui/loading-spinner';
import { Notice } from '@/components/ui/notice';
import { useSetupStatus } from '@/hooks/data/setup';
import { ClaimForm } from './ClaimForm';
import { OwnerSignInForm } from './OwnerSignInForm';

function SetupAccessCard({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div className="min-h-screen flex items-center justify-center bg-background p-4">
      <Card className="w-full max-w-md shadow-card">
        <CardHeader>
          <CardTitle>
            <h1>{title}</h1>
          </CardTitle>
        </CardHeader>
        <CardContent className="space-y-4">{children}</CardContent>
      </Card>
    </div>
  );
}

export function SetupAccessLoading() {
  const { t } = useTranslation();

  return (
    <SetupAccessCard title={t('pages.setup.title')}>
      <div className="flex justify-center py-6">
        <LoadingSpinner className="h-6 w-6 text-primary" />
      </div>
    </SetupAccessCard>
  );
}

export function SetupAccess() {
  const { t } = useTranslation();
  const { data: status, isLoading, isError, refetch } = useSetupStatus();

  if (isLoading) return <SetupAccessLoading />;

  if (isError || !status) {
    return (
      <SetupAccessCard title={t('pages.setup.title')}>
        <Notice variant="error" announce>{t('pages.setup.statusFailed')}</Notice>
        <Button type="button" variant="outline" className="w-full" onClick={() => { void refetch(); }}>
          {t('common.retry')}
        </Button>
      </SetupAccessCard>
    );
  }

  switch (status.phase) {
    case 'ready':
      return <Redirect to="/" replace />;
    case 'unclaimed':
      return <ClaimForm />;
    case 'owner-sign-in':
      return <OwnerSignInForm />;
  }
}
