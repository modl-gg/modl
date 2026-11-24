import { type ReactNode } from 'react';
import { Redirect } from 'wouter';
import { LoadingSpinner } from '@/components/ui/loading-spinner';
import { useAuth } from '@/hooks/use-auth';
import { SUPER_ADMIN_ROLE } from '@/hooks/use-permissions';
import { useSetupWizard } from '@/hooks/data/setup';
import { setupPath } from '@/lib/setup-steps';

export function SetupGate({ children }: { children: ReactNode }) {
  const { user } = useAuth();
  const isOwner = user?.role === SUPER_ADMIN_ROLE;
  const { data: wizard, isPending } = useSetupWizard({ enabled: isOwner });

  if (isOwner && isPending) {
    return (
      <div className="flex min-h-[200px] items-center justify-center">
        <LoadingSpinner className="h-6 w-6 text-primary" />
      </div>
    );
  }
  if (isOwner && wizard?.completed === false) return <Redirect to={setupPath()} />;
  return <>{children}</>;
}
