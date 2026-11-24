import { useEffect, type ReactNode } from 'react';
import { useLocation } from 'wouter';
import { ThemeProvider } from 'next-themes';
import { usePublicColorScheme, usePublicTheme } from '@/hooks/use-public-theme';
import { rememberOwnerColorScheme } from '@/lib/public-color-scheme';
import { publicPageOf } from '@/utils/routes';

const THEME_SCRIPT_AS_DATA_BLOCK = { type: 'application/json' } as const;

export function AppThemeProvider({ children }: { children: ReactNode }) {
  const [location] = useLocation();
  const { scheme } = usePublicColorScheme();
  const ownerDefault = usePublicTheme().colorScheme;
  const forcedTheme = publicPageOf(location) === null ? undefined : scheme;

  useEffect(() => {
    rememberOwnerColorScheme(ownerDefault);
  }, [ownerDefault]);

  return (
    <ThemeProvider attribute="class" defaultTheme="system" enableSystem forcedTheme={forcedTheme} scriptProps={THEME_SCRIPT_AS_DATA_BLOCK}>
      {children}
    </ThemeProvider>
  );
}
