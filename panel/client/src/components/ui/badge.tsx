import type { ComponentProps } from 'react';
import { cva, type VariantProps } from 'class-variance-authority';
import { cn } from '@/lib/utils';

const badgeVariants = cva(
  "inline-flex items-center whitespace-nowrap font-mono text-xs font-medium focus:outline-hidden focus-visible:ring-2 focus-visible:ring-ring",
  {
    variants: {
      variant: {
        default: 'text-primary',
        secondary: 'text-muted-foreground',
        destructive: 'text-destructive-text',
        outline: 'text-foreground/80',
        success: 'text-success',
        warning: 'text-warning',
        info: 'text-info',
      },
    },
    defaultVariants: {
      variant: 'default',
    },
  },
);

function Badge({ className, variant, children, ...props }: ComponentProps<'span'> & VariantProps<typeof badgeVariants>) {
  return (
    <span className={cn(badgeVariants({ variant }), className)} {...props}>
      <span aria-hidden="true">{'['}</span>
      {children}
      <span aria-hidden="true">{']'}</span>
    </span>
  );
}

export { Badge };
