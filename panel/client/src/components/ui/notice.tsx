import { type ReactNode } from 'react';
import { cn } from '@/lib/utils';
import { isRenderable } from '@/components/ui/renderable';
import { AlertCircle, Info, AlertTriangle, CheckCircle2 } from 'lucide-react';

type Variant = 'info' | 'success' | 'warning' | 'error';

const styles: Record<Variant, string> = {
  info: 'bg-info/10 text-info border-info/20',
  success: 'bg-success/10 text-success border-success/20',
  warning: 'bg-warning/10 text-warning border-warning/20',
  error: 'bg-destructive-text/10 text-destructive-text border-destructive-text/20',
};

const icons: Record<Variant, ReactNode> = {
  info: <Info className="h-4 w-4 shrink-0" aria-hidden="true" />,
  success: <CheckCircle2 className="h-4 w-4 shrink-0" aria-hidden="true" />,
  warning: <AlertTriangle className="h-4 w-4 shrink-0" aria-hidden="true" />,
  error: <AlertCircle className="h-4 w-4 shrink-0" aria-hidden="true" />,
};

const announcementRoles: Record<Variant, 'alert' | 'status'> = {
  info: 'status',
  success: 'status',
  warning: 'status',
  error: 'alert',
};

export interface NoticeProps {
  variant?: Variant;
  title?: string;
  children?: ReactNode;
  className?: string;
  announce?: boolean;
}

export function Notice({ variant = 'info', title, children, className, announce = false }: NoticeProps) {
  return (
    <div role={announce ? announcementRoles[variant] : undefined} className={cn('rounded-md border px-3 py-2 flex gap-2 items-start', styles[variant], className)}>
      {icons[variant]}
      <div className="text-sm">
        {title && <div className="font-medium">{title}</div>}
        {isRenderable(children) && <div className={cn(title && 'mt-1', 'text-foreground/80')}>{children}</div>}
      </div>
    </div>
  );
}
