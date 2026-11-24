import type { ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { LoadFailure } from '@/components/ui/load-failure';
import { LoadingSpinner } from '@/components/ui/loading-spinner';
import { usePermissions } from '@/hooks/use-permissions';

interface PermissionWrapperProps {
  children: ReactNode;
  permissions?: readonly string[];
  fallback?: ReactNode;
}

export function PermissionWrapper({
  children,
  permissions = [],
  fallback = null
}: PermissionWrapperProps) {
  const { t } = useTranslation();
  const { hasAllPermissions, isPermissionsLoading, isPermissionsError, retryPermissions } = usePermissions();

  if (permissions.length === 0) return <>{children}</>;

  if (isPermissionsError) {
    return <LoadFailure message={t('common.unknownError')} onRetry={retryPermissions} />;
  }

  if (isPermissionsLoading) {
    return (
      <div className="flex items-center justify-center py-8">
        <LoadingSpinner className="h-6 w-6 text-muted-foreground" />
      </div>
    );
  }

  return hasAllPermissions(permissions) ? <>{children}</> : <>{fallback}</>;
}
