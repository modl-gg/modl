import { useEffect, useState } from 'react';
import i18n from '@/lib/i18n';
import { useAuth } from '@/hooks/use-auth';
import { usePublicSettings } from '@/hooks/use-public-settings';
import { DEFAULT_LANGUAGE_CODE, isSupportedLanguage } from '@/lib/languages';

export function resolveLanguage(
  userLanguage: string | null | undefined,
  serverDefaultLanguage: string | null | undefined,
): string {
  return [userLanguage, serverDefaultLanguage, DEFAULT_LANGUAGE_CODE]
    .find((code) => isSupportedLanguage(code)) ?? DEFAULT_LANGUAGE_CODE;
}

export function useLanguagePreference(): boolean {
  const { user, isLoading: isLoadingUser } = useAuth();
  const { data: publicSettings, isLoading: isLoadingSettings } = usePublicSettings();
  const [initialLanguageApplied, setInitialLanguageApplied] = useState(false);

  const preferencesKnown = !isLoadingUser && !isLoadingSettings;
  const resolved = resolveLanguage(user?.language, publicSettings?.defaultLanguage);
  const resolvedLanguageActive = preferencesKnown && resolved === i18n.language;

  if (resolvedLanguageActive && !initialLanguageApplied) {
    setInitialLanguageApplied(true);
  }

  useEffect(() => {
    if (!preferencesKnown || resolved === i18n.language) return;
    let cancelled = false;
    const markApplied = () => {
      if (!cancelled) setInitialLanguageApplied(true);
    };
    i18n.changeLanguage(resolved).then(markApplied, markApplied);
    return () => {
      cancelled = true;
    };
  }, [preferencesKnown, resolved]);

  return initialLanguageApplied || resolvedLanguageActive;
}
