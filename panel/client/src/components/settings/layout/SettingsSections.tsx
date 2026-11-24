import { useId, type ReactNode } from 'react';
import { LoadingSpinner } from '@/components/ui/loading-spinner';
import { cn } from '@/lib/utils';

export const FIELD_WIDTH = {
  number: 'max-w-40',
  short: 'max-w-xs',
  text: 'max-w-md',
  wide: 'max-w-2xl',
} as const;

export function SettingsSections({ children }: { children: ReactNode }) {
  return <div className="divide-y [&>*]:py-6 [&>*:first-child]:pt-0 [&>*:last-child]:pb-0">{children}</div>;
}

interface SettingsSectionProps {
  title?: string;
  action?: ReactNode;
  className?: string;
  children: ReactNode;
}

export function SettingsSection({ title, action, className, children }: SettingsSectionProps) {
  const titleId = useId();
  return (
    <section className={cn('space-y-5', className)} aria-labelledby={title === undefined ? undefined : titleId}>
      {title !== undefined && (
        <div className="flex items-center justify-between gap-4">
          <h3 id={titleId} className="text-sm font-semibold">{title}</h3>
          {action}
        </div>
      )}
      {children}
    </section>
  );
}

export function SettingsLoading() {
  return (
    <div className="flex justify-center py-8">
      <LoadingSpinner className="h-6 w-6 text-muted-foreground" />
    </div>
  );
}
