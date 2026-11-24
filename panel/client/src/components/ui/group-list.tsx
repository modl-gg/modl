import type { ReactNode, Ref } from 'react';
import { useTranslation } from 'react-i18next';
import { GripVertical } from 'lucide-react';
import type { SortHandle } from '@/hooks/use-sortable';
import { cn } from '@/lib/utils';

type GroupHandle = SortHandle | 'spacer';

function hasContent(node: ReactNode): boolean {
  if (Array.isArray(node)) return node.length > 0;
  return node !== undefined && node !== null && node !== false && node !== '';
}

function Grip({ handle }: { handle: SortHandle }) {
  const { t } = useTranslation();
  return (
    <button
      ref={handle.ref}
      type="button"
      aria-label={handle.groupLabel === null
        ? t('common.reorderItem', { item: handle.itemLabel })
        : t('common.reorderItemInGroup', { item: handle.itemLabel, group: handle.groupLabel })}
      aria-keyshortcuts="ArrowUp ArrowDown"
      onKeyDown={handle.onKeyDown}
      className="-m-1 shrink-0 cursor-grab rounded p-1 text-muted-foreground hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
    >
      <GripVertical className="h-4 w-4" aria-hidden="true" />
    </button>
  );
}

function Handle({ handle }: { handle: GroupHandle | undefined }) {
  if (handle === undefined) return null;
  if (handle === 'spacer') return <span className="h-4 w-4 shrink-0" aria-hidden="true" />;
  return <Grip handle={handle} />;
}

function Actions({ children }: { children: ReactNode }) {
  if (!hasContent(children)) return null;
  return <div className="flex shrink-0 items-center gap-1">{children}</div>;
}

type HeadingLevel = 3 | 4;

interface GroupCardProps {
  ref?: Ref<HTMLDivElement>;
  headingLevel?: HeadingLevel;
  title: ReactNode;
  meta?: ReactNode;
  description?: ReactNode;
  actions?: ReactNode;
  handle?: GroupHandle | undefined;
  dragging?: boolean;
  children?: ReactNode;
}

export function GroupCard({ ref, headingLevel = 4, title, meta, description, actions, handle, dragging = false, children }: GroupCardProps) {
  const Heading = headingLevel === 3 ? 'h3' : 'h4';
  return (
    <div ref={ref} className={cn('space-y-3 rounded-lg border p-3', dragging && 'opacity-50')}>
      <div className="flex items-center gap-3">
        <Handle handle={handle} />
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
            <Heading className="min-w-0 truncate text-sm font-semibold">{title}</Heading>
            {meta}
          </div>
          {hasContent(description) && <p className="text-sm text-muted-foreground">{description}</p>}
        </div>
        <Actions>{actions}</Actions>
      </div>
      {children}
    </div>
  );
}

export function GroupRows({ ref, children }: { ref?: Ref<HTMLDivElement>; children: ReactNode }) {
  return <div ref={ref} className="space-y-2">{children}</div>;
}

interface GroupRowProps {
  ref?: Ref<HTMLDivElement>;
  title: ReactNode;
  meta?: ReactNode;
  description?: ReactNode;
  detail?: ReactNode;
  leading?: ReactNode;
  actions?: ReactNode;
  handle?: GroupHandle | undefined;
  dragging?: boolean;
}

export function GroupRow({ ref, title, meta, description, detail, leading, actions, handle, dragging = false }: GroupRowProps) {
  return (
    <div ref={ref} className={cn('flex items-center gap-3 rounded-md border bg-background px-3 py-2', dragging && 'opacity-50')}>
      <Handle handle={handle} />
      {leading}
      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
          <span className="min-w-0 truncate text-sm font-medium">{title}</span>
          {meta}
        </div>
        {hasContent(description) && <p className="line-clamp-2 text-sm text-muted-foreground">{description}</p>}
        {hasContent(detail) && <p className="text-xs text-muted-foreground">{detail}</p>}
      </div>
      <Actions>{actions}</Actions>
    </div>
  );
}
