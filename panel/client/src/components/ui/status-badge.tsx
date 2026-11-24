import type { ReactNode } from 'react';
import { Badge } from '@/components/ui/badge';

export type StatusIntent = 'info' | 'success' | 'warning' | 'destructive' | 'neutral';

const VARIANTS = {
  info: 'info',
  success: 'success',
  warning: 'warning',
  destructive: 'destructive',
  neutral: 'secondary',
} as const satisfies Record<StatusIntent, string>;

export function StatusBadge({
  intent = 'neutral',
  children,
  className,
}: {
  intent?: StatusIntent;
  children: ReactNode;
  className?: string;
}) {
  return (
    <Badge variant={VARIANTS[intent]} className={className}>
      {children}
    </Badge>
  );
}
