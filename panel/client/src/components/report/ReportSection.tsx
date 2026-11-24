import type { ReactNode } from 'react';
import { cn } from '@/lib/utils';

interface ReportSectionProps {
  title: ReactNode;
  actions?: ReactNode;
  className?: string;
  children: ReactNode;
}

export function ReportSection({ title, actions, className, children }: ReportSectionProps) {
  return (
    <section className={cn('min-w-0 space-y-3', className)}>
      <div className="flex min-h-8 flex-wrap items-center justify-between gap-2">
        <h3 className="text-sm font-medium">{title}</h3>
        {actions}
      </div>
      {children}
    </section>
  );
}

export function ReportColumns({ children }: { children: ReactNode }) {
  return <div className="grid grid-cols-1 gap-x-10 gap-y-8 lg:grid-cols-2">{children}</div>;
}
