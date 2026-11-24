import { useId, type ReactNode } from 'react';
import { cn } from '@/lib/utils';
import { Label } from '@/components/ui/label';
import { isRenderable } from '@/components/ui/renderable';

export interface FieldControlProps {
  id: string;
  'aria-describedby': string | undefined;
  'aria-invalid': boolean;
}

interface FieldProps {
  label: ReactNode;
  hint?: ReactNode;
  error?: ReactNode;
  managed?: ReactNode;
  className?: string;
  children: (control: FieldControlProps) => ReactNode;
}

export function Field({ label, hint, error, managed, className, children }: FieldProps) {
  const id = useId();
  const messageId = `${id}-message`;
  const managedId = `${id}-managed`;
  const invalid = isRenderable(error);
  const message = invalid ? error : hint;
  const hasMessage = isRenderable(message);
  const hasManaged = isRenderable(managed);
  const describedBy = [hasMessage ? messageId : null, hasManaged ? managedId : null].filter((entry) => entry !== null).join(' ');

  return (
    <div className={cn('space-y-2', className)}>
      <Label htmlFor={id}>{label}</Label>
      {children({ id, 'aria-describedby': describedBy === '' ? undefined : describedBy, 'aria-invalid': invalid })}
      {hasMessage && (
        <p id={messageId} className={invalid ? 'text-sm font-medium text-destructive-text' : 'text-xs text-muted-foreground'}>{message}</p>
      )}
      {hasManaged && <p id={managedId} className="text-xs text-muted-foreground">{managed}</p>}
    </div>
  );
}
