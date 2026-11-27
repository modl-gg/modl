import type { ComponentProps } from 'react';
import type { LucideIcon } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';
import { cn } from '@/lib/utils';

type IconActionVariant = 'ghost' | 'outline';

const VARIANT_CLASSES: Record<IconActionVariant, string> = {
  ghost: 'h-8 w-8 text-muted-foreground hover:text-foreground',
  outline: '',
};

interface IconActionProps extends Omit<ComponentProps<typeof Button>, 'variant' | 'size' | 'children' | 'asChild'> {
  icon: LucideIcon;
  label: string;
  variant?: IconActionVariant;
  destructive?: boolean;
  iconClassName?: string | undefined;
}

export function IconAction({ icon: Icon, label, variant = 'ghost', destructive = false, className, iconClassName, ...props }: IconActionProps) {
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <Button
          type="button"
          variant={variant}
          size="icon"
          aria-label={label}
          className={cn(VARIANT_CLASSES[variant], destructive && 'hover:text-destructive-text', className)}
          {...props}
        >
          <Icon className={cn('h-4 w-4', iconClassName)} aria-hidden="true" />
        </Button>
      </TooltipTrigger>
      <TooltipContent>{label}</TooltipContent>
    </Tooltip>
  );
}

interface IconLinkProps {
  icon: LucideIcon;
  label: string;
  href: string;
  className?: string | undefined;
}

export function IconLink({ icon: Icon, label, href, className }: IconLinkProps) {
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <Button variant="ghost" size="icon" className={cn(VARIANT_CLASSES.ghost, className)} asChild>
          <a href={href} target="_blank" rel="noopener noreferrer" aria-label={label}>
            <Icon className="h-4 w-4" aria-hidden="true" />
          </a>
        </Button>
      </TooltipTrigger>
      <TooltipContent>{label}</TooltipContent>
    </Tooltip>
  );
}
