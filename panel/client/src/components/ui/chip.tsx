import type { ComponentProps } from 'react';
import { Loader2, X } from 'lucide-react';
import { cn } from '@/lib/utils';

const CHIP_CLASS = 'inline-flex max-w-full items-center gap-1 rounded-full border border-border bg-muted/40 px-2 py-0.5 text-xs text-muted-foreground';
const FOCUS_RING_CLASS = 'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring';

function Chip({ className, ...props }: ComponentProps<'span'>) {
  return <span className={cn(CHIP_CLASS, className)} {...props} />;
}

function ColorDot({ color, className }: { color: string; className?: string }) {
  return <span className={cn('h-2 w-2 shrink-0 rounded-full', className)} style={{ backgroundColor: color }} aria-hidden="true" />;
}

function ChipButton({ className, ...props }: Omit<ComponentProps<'button'>, 'type'>) {
  return (
    <button
      type="button"
      className={cn(CHIP_CLASS, FOCUS_RING_CLASS, 'transition-colors hover:bg-muted hover:text-foreground disabled:pointer-events-none disabled:opacity-50', className)}
      {...props}
    />
  );
}

function ChipAction({ className, ...props }: Omit<ComponentProps<'button'>, 'type'>) {
  return (
    <button
      type="button"
      className={cn(FOCUS_RING_CLASS, 'inline-flex min-w-0 items-center gap-1 rounded-full transition-colors hover:text-foreground', className)}
      {...props}
    />
  );
}

interface ChipRemoveButtonProps extends Omit<ComponentProps<'button'>, 'type' | 'aria-label' | 'children'> {
  label: string;
  pending?: boolean;
}

function ChipRemoveButton({ label, pending = false, disabled, className, ...props }: ChipRemoveButtonProps) {
  return (
    <button
      type="button"
      aria-label={label}
      disabled={disabled === true || pending}
      className={cn(
        FOCUS_RING_CLASS,
        '-mr-1 inline-flex h-4 w-4 shrink-0 items-center justify-center rounded-full transition-colors hover:bg-destructive/10 hover:text-destructive-text disabled:pointer-events-none disabled:opacity-50',
        className,
      )}
      {...props}
    >
      {pending ? <Loader2 className="h-3 w-3 animate-spin" aria-hidden="true" /> : <X className="h-3 w-3" aria-hidden="true" />}
    </button>
  );
}

export { Chip, ChipAction, ChipButton, ChipRemoveButton, ColorDot };
