const RETURN_PATH_PARAM = 'next';
export const PANEL_HOME_PATH = '/panel';
export const PANEL_SIGN_IN_PATH = '/panel/auth';

const PARSE_BASE = 'http://return-path.invalid';

export function safeReturnPath(value: string | null | undefined): string | null {
  if (!value || !value.startsWith('/') || value.startsWith('//') || value.includes('\\')) return null;
  let parsed: URL;
  try {
    parsed = new URL(value, PARSE_BASE);
  } catch {
    return null;
  }
  if (parsed.origin !== PARSE_BASE || parsed.pathname === PANEL_SIGN_IN_PATH) return null;
  return `${parsed.pathname}${parsed.search}${parsed.hash}`;
}

export function readReturnPath(search: string): string | null {
  return safeReturnPath(new URLSearchParams(search).get(RETURN_PATH_PARAM));
}

export function signInPathReturningTo(signInPath: string, returnTo: string): string {
  const target = safeReturnPath(returnTo);
  if (target === null || target === PANEL_HOME_PATH) return signInPath;
  return `${signInPath}?${new URLSearchParams({ [RETURN_PATH_PARAM]: target }).toString()}`;
}
