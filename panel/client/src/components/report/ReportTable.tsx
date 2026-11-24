import type { ReactNode } from 'react';
import { cn } from '@/lib/utils';

export function ReportTable({ header, children }: { header: ReactNode; children: ReactNode }) {
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-sm">
        <thead>
          <tr className="border-b">{header}</tr>
        </thead>
        <tbody>{children}</tbody>
      </table>
    </div>
  );
}

export function ReportHeaderCell({ children, className }: { children: ReactNode; className?: string }) {
  return <th className={cn('p-2 text-left', className)}>{children}</th>;
}

export function ReportEmptyRow({ columns, children }: { columns: number; children: ReactNode }) {
  return (
    <tr>
      <td colSpan={columns} className="p-4 text-center text-muted-foreground">{children}</td>
    </tr>
  );
}
