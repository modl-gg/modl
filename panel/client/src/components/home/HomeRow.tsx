import type { ReactNode } from 'react';
import type { HomepageIcon } from '@/lib/homepage-icons';
import { cn } from '@/lib/utils';

export const HOME_ROW_CLASS = 'rounded-lg border border-border/60 bg-card/60 shadow-sm transition-colors hover:border-border hover:bg-card';
export const HOME_ROW_ACTION_CLASS = 'group flex w-full items-center gap-4 rounded-lg px-4 py-3.5 text-left focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring';
export const HOME_ROW_CHEVRON_CLASS = 'h-4 w-4 shrink-0 text-muted-foreground transition-transform';

interface HomeRowBodyProps {
  icon: HomepageIcon;
  title: string;
  description: string;
  accent: boolean;
  trailing: ReactNode;
}

export function HomeRowBody({ icon: Icon, title, description, accent, trailing }: HomeRowBodyProps) {
  return (
    <>
      <span className={cn('flex h-10 w-10 shrink-0 items-center justify-center rounded-md', accent ? 'bg-accent/10' : 'bg-primary/10')}>
        <Icon data-modl="card-icon" className={cn('h-5 w-5', accent ? 'text-accent' : 'text-primary')} aria-hidden />
      </span>
      <span className="min-w-0 flex-1">
        <span data-modl="card-title" className="block truncate font-medium">{title}</span>
        {description !== '' && (
          <span data-modl="card-description" className="mt-0.5 block truncate text-sm text-muted-foreground">{description}</span>
        )}
      </span>
      {trailing}
    </>
  );
}
