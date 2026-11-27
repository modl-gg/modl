import { createContext, use, useEffect, useMemo, useState, type ComponentProps, type ReactNode } from 'react';
import { createPortal } from 'react-dom';
import { IconAction } from '@/components/ui/icon-action';
import { WikiHelpLink } from '@/components/ui/wiki-help-link';
import type { WikiLink } from '@/lib/wiki-links';

interface PageFrame {
  actionSlot: HTMLElement | null;
  canModify: boolean;
}

const PageFrameContext = createContext<PageFrame | undefined>(undefined);

function usePageFrame(): PageFrame {
  const frame = use(PageFrameContext);
  if (frame === undefined) throw new Error('SettingsPageAction must be rendered inside a SettingsPageFrame');
  return frame;
}

interface SettingsPageFrameProps {
  title: string;
  help?: WikiLink | undefined;
  canModify: boolean;
  children: ReactNode;
}

export function SettingsPageFrame({ title, help, canModify, children }: SettingsPageFrameProps) {
  const [actionSlot, setActionSlot] = useState<HTMLDivElement | null>(null);
  const frame = useMemo(() => ({ actionSlot, canModify }), [actionSlot, canModify]);

  useEffect(() => {
    window.scrollTo(0, 0);
  }, []);

  return (
    <>
      <div className="mb-6 flex min-h-9 items-center justify-between gap-4">
        <div className="flex min-w-0 items-center gap-1">
          <h2 className="min-w-0 text-xl font-semibold">{title}</h2>
          {help !== undefined && <WikiHelpLink link={help} />}
        </div>
        <div ref={setActionSlot} className="flex shrink-0 items-center gap-2 empty:hidden" />
      </div>
      <PageFrameContext value={frame}>{children}</PageFrameContext>
    </>
  );
}

export function useSettingsPageCanModify(): boolean {
  return usePageFrame().canModify;
}

interface SettingsPageActionProps {
  children: ReactNode;
  requiresModify?: boolean;
}

export function SettingsPageAction({ children, requiresModify = true }: SettingsPageActionProps) {
  const { actionSlot, canModify } = usePageFrame();
  if (actionSlot === null || (requiresModify && !canModify)) return null;
  return createPortal(children, actionSlot);
}

export function SettingsPageActionButton(props: Omit<ComponentProps<typeof IconAction>, 'variant'>) {
  return <IconAction variant="outline" {...props} />;
}
