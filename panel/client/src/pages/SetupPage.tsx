import { Redirect, useParams } from 'wouter';
import { useAuth } from '@/hooks/use-auth';
import { SUPER_ADMIN_ROLE } from '@/hooks/use-permissions';
import { PANEL_HOME_PATH } from '@/lib/return-path';
import { SetupAccess, SetupAccessLoading } from '@/components/setup/SetupAccess';
import { SetupWizard } from '@/components/setup/SetupWizard';

const SetupPage = () => {
  const { user, isLoading } = useAuth();
  const { step } = useParams<{ step?: string }>();

  if (isLoading) return <SetupAccessLoading />;
  if (!user) return <SetupAccess />;
  if (user.role !== SUPER_ADMIN_ROLE) return <Redirect to={PANEL_HOME_PATH} />;
  return <SetupWizard requested={step} />;
};

export default SetupPage;
