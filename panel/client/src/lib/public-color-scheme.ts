import { PUBLIC_SCHEME_DEFAULTS, resolvePublicColorScheme, type ColorScheme, type PublicSchemeDefault } from './public-theme.ts';

const VISITOR_KEY = 'modl-public-color-scheme';
const OWNER_DEFAULT_KEY = 'modl-public-color-scheme-default';
const PANEL_KEY = 'theme';
const DARK_QUERY = '(prefers-color-scheme: dark)';
const COLOR_SCHEMES: readonly ColorScheme[] = ['light', 'dark'];
const listeners = new Set<() => void>();

function readStored(key: string): string | null {
  try {
    return window.localStorage.getItem(key);
  } catch {
    return null;
  }
}

function store(key: string, value: string): boolean {
  try {
    window.localStorage.setItem(key, value);
    return true;
  } catch {
    return false;
  }
}

function storedScheme(key: string): ColorScheme | null {
  const stored = readStored(key);
  return COLOR_SCHEMES.find((scheme) => scheme === stored) ?? null;
}

let visitorScheme = storedScheme(VISITOR_KEY);

function publish(): void {
  for (const listener of listeners) listener();
}

function reloadFromStorage(event: StorageEvent): void {
  if (event.key !== VISITOR_KEY) return;
  visitorScheme = storedScheme(VISITOR_KEY);
  publish();
}

export function visitorColorScheme(): ColorScheme | null {
  return visitorScheme;
}

export function chooseVisitorColorScheme(scheme: ColorScheme): void {
  visitorScheme = scheme;
  store(VISITOR_KEY, scheme);
  publish();
}

export function subscribeVisitorColorScheme(listener: () => void): () => void {
  listeners.add(listener);
  if (listeners.size === 1) window.addEventListener('storage', reloadFromStorage);
  return () => {
    listeners.delete(listener);
    if (listeners.size === 0) window.removeEventListener('storage', reloadFromStorage);
  };
}

export function rememberOwnerColorScheme(ownerDefault: PublicSchemeDefault): void {
  if (readStored(OWNER_DEFAULT_KEY) !== ownerDefault) store(OWNER_DEFAULT_KEY, ownerDefault);
}

function rememberedOwnerColorScheme(): PublicSchemeDefault {
  const stored = readStored(OWNER_DEFAULT_KEY);
  return PUBLIC_SCHEME_DEFAULTS.find((scheme) => scheme === stored) ?? 'system';
}

export function systemColorScheme(): ColorScheme {
  return window.matchMedia(DARK_QUERY).matches ? 'dark' : 'light';
}

export function subscribeSystemColorScheme(listener: () => void): () => void {
  const query = window.matchMedia(DARK_QUERY);
  query.addEventListener('change', listener);
  return () => {
    query.removeEventListener('change', listener);
  };
}

export function initialColorScheme(onPublicPage: boolean): ColorScheme {
  if (onPublicPage) return resolvePublicColorScheme(visitorScheme, rememberedOwnerColorScheme(), systemColorScheme());
  return storedScheme(PANEL_KEY) ?? systemColorScheme();
}

export function applyInitialColorScheme(onPublicPage: boolean): void {
  const scheme = initialColorScheme(onPublicPage);
  const root = document.documentElement;
  root.classList.remove(...COLOR_SCHEMES);
  root.classList.add(scheme);
  root.style.colorScheme = scheme;
}
