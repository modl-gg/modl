const HTTP_PROTOCOLS = new Set(['http:', 'https:']);

export function normalizeHttpOrigin(value: string): string | null {
  const trimmed = value.trim();
  if (!trimmed) return null;

  let parsed: URL;
  try {
    parsed = new URL(trimmed);
  } catch {
    return null;
  }

  const isBareOrigin = parsed.pathname === '/' && !parsed.search && !parsed.hash && !trimmed.endsWith('?') && !trimmed.endsWith('#');
  const hasCredentials = Boolean(parsed.username || parsed.password);
  if (!HTTP_PROTOCOLS.has(parsed.protocol) || !isBareOrigin || hasCredentials || !parsed.hostname) {
    return null;
  }
  return parsed.origin;
}

export type PanelUrlWarning = 'local-only' | 'insecure';

const LOCAL_ONLY_HOSTNAMES = new Set(['localhost', '0.0.0.0', '[::1]', '[::]']);
const LOOPBACK_IPV4 = /^127(?:\.\d{1,3}){3}$/;

function isLocalOnlyHostname(hostname: string): boolean {
  return LOCAL_ONLY_HOSTNAMES.has(hostname) || hostname.endsWith('.localhost') || LOOPBACK_IPV4.test(hostname);
}

export function panelUrlWarning(origin: string): PanelUrlWarning | null {
  let parsed: URL;
  try {
    parsed = new URL(origin);
  } catch {
    return null;
  }
  if (isLocalOnlyHostname(parsed.hostname)) return 'local-only';
  return parsed.protocol === 'http:' ? 'insecure' : null;
}
