import { useMemo, useState, type ReactNode } from 'react';
import { useLocation } from 'wouter';
import { PublicFooterSlotProvider } from '@/components/layout/PublicFooterSlot';
import { PageEndSourceCodeLink } from '@/components/layout/SourceCodeLink';
import { usePublicSettings } from '@/hooks/use-public-settings';
import {
  useCustomStylesheet,
  useDocumentStyleRules,
  usePublicColorScheme,
  usePublicFont,
  usePublicTheme,
} from '@/hooks/use-public-theme';
import { publicThemeRules } from '@/lib/public-theme';
import { publicPageOf, type PublicPageId } from '@/utils/routes';

const SCREEN_PAGES_FILL_ABOVE_LINK = '[&>.min-h-screen]:min-h-0 [&>.min-h-screen]:flex-1';

function ThemedPublicMain({ page, children }: { page: PublicPageId; children: ReactNode }) {
  const theme = usePublicTheme();
  const { scheme } = usePublicColorScheme();
  const customCssRevision = usePublicSettings().data?.customCssRevision ?? '';
  const rules = useMemo(() => publicThemeRules(theme, scheme), [theme, scheme]);
  const [footerSlot, setFooterSlot] = useState<HTMLDivElement | null>(null);

  useDocumentStyleRules(rules);
  usePublicFont(theme.font);
  useCustomStylesheet(customCssRevision);

  return (
    <main data-modl="page" data-modl-page={page} className="flex min-h-dvh flex-col bg-background">
      <div className={`flex flex-1 flex-col ${SCREEN_PAGES_FILL_ABOVE_LINK}`}>
        <PublicFooterSlotProvider slot={footerSlot}>{children}</PublicFooterSlotProvider>
      </div>
      <footer data-modl="footer">
        <div ref={setFooterSlot} className="contents" />
        <PageEndSourceCodeLink />
      </footer>
    </main>
  );
}

export function PublicMain({ children, withSourceLink }: { children: ReactNode; withSourceLink: boolean }) {
  const [location] = useLocation();
  const page = publicPageOf(location);
  if (page !== null) {
    return <ThemedPublicMain page={page}>{children}</ThemedPublicMain>;
  }
  if (!withSourceLink) {
    return <main className="h-full bg-background">{children}</main>;
  }
  return (
    <main className="flex min-h-dvh flex-col bg-background">
      <div className={`flex flex-1 flex-col ${SCREEN_PAGES_FILL_ABOVE_LINK}`}>{children}</div>
      <PageEndSourceCodeLink />
    </main>
  );
}
