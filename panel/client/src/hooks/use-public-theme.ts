import { useEffect, useLayoutEffect, useState, useSyncExternalStore } from 'react';
import { usePublicSettings } from '@/hooks/use-public-settings';
import { PUBLIC_STYLESHEET_PATH } from '@/hooks/data/public-theme';
import { loadPublicFont } from '@/lib/public-fonts';
import {
  chooseVisitorColorScheme,
  subscribeSystemColorScheme,
  subscribeVisitorColorScheme,
  systemColorScheme,
  visitorColorScheme,
} from '@/lib/public-color-scheme';
import {
  DEFAULT_PUBLIC_THEME,
  resolvePublicColorScheme,
  type ColorScheme,
  type PublicFontId,
  type PublicTheme,
  type PublicThemeVariables,
} from '@/lib/public-theme';

const SAFE_MODE_PARAMETER = 'nocss';
const CUSTOM_CSS_SAFE_MODE = new URLSearchParams(window.location.search).has(SAFE_MODE_PARAMETER);

export function usePublicTheme(): PublicTheme {
  return usePublicSettings().data?.theme ?? DEFAULT_PUBLIC_THEME;
}

export interface PublicColorSchemeControl {
  scheme: ColorScheme;
  toggle: () => void;
}

export function usePublicColorScheme(): PublicColorSchemeControl {
  const ownerDefault = usePublicTheme().colorScheme;
  const visitor = useSyncExternalStore(subscribeVisitorColorScheme, visitorColorScheme);
  const system = useSyncExternalStore(subscribeSystemColorScheme, systemColorScheme);
  const scheme = resolvePublicColorScheme(visitor, ownerDefault, system);
  return {
    scheme,
    toggle: () => {
      chooseVisitorColorScheme(scheme === 'dark' ? 'light' : 'dark');
    },
  };
}

export function useStyleVariables(element: HTMLElement | null, variables: PublicThemeVariables): void {
  useLayoutEffect(() => {
    if (element === null) return undefined;
    const entries = Object.entries(variables);
    for (const [name, value] of entries) element.style.setProperty(name, value);
    return () => {
      for (const [name] of entries) element.style.removeProperty(name);
    };
  }, [element, variables]);
}

export function useDocumentStyleRules(rules: readonly string[]): void {
  const [element] = useState(() => document.createElement('style'));

  useLayoutEffect(() => {
    document.head.appendChild(element);
    return () => {
      element.remove();
    };
  }, [element]);

  useLayoutEffect(() => {
    const { sheet } = element;
    if (sheet === null) return;
    while (sheet.cssRules.length > 0) sheet.deleteRule(0);
    for (const rule of rules) sheet.insertRule(rule, sheet.cssRules.length);
  }, [element, rules]);
}

export function usePublicFont(font: PublicFontId): void {
  useEffect(() => {
    loadPublicFont(font).catch(() => undefined);
  }, [font]);
}

export function useCustomStylesheet(revision: string): void {
  useLayoutEffect(() => {
    if (revision === '' || CUSTOM_CSS_SAFE_MODE) return undefined;
    const link = document.createElement('link');
    link.rel = 'stylesheet';
    link.href = `${PUBLIC_STYLESHEET_PATH}?v=${encodeURIComponent(revision)}`;
    link.dataset['modlCustomCss'] = '';
    document.head.appendChild(link);
    return () => {
      link.remove();
    };
  }, [revision]);
}
