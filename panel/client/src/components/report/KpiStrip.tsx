import type { ReactNode } from 'react';

export function KpiStrip({ children }: { children: ReactNode }) {
  return <dl className="grid grid-cols-2 gap-y-4 sm:flex sm:flex-wrap sm:divide-x">{children}</dl>;
}

interface KpiProps {
  label: string;
  value: ReactNode;
  detail?: ReactNode;
}

export function Kpi({ label, value, detail }: KpiProps) {
  return (
    <div className="min-w-0 sm:px-6 sm:first:pl-0">
      <dt className="text-xs text-muted-foreground">{label}</dt>
      <dd className="mt-1 flex items-baseline gap-2">
        <span className="text-2xl font-semibold tabular-nums">{value}</span>
        {detail !== undefined && <span className="text-xs text-muted-foreground">{detail}</span>}
      </dd>
    </div>
  );
}
