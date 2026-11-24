export type ConnectionMode = 'behind-proxy' | 'remote';

export interface ConnectionSnippetValues {
  mode: ConnectionMode;
  url: string;
  apiKey: string;
}

export interface ConnectionUrlPlan {
  primary: string;
  sameMachine: string | null;
  loopbackOnly: boolean;
}

const IPV4_ADDRESS = /^(\d{1,3})\.(\d{1,3})\.\d{1,3}\.\d{1,3}$/;
const PRIVATE_IPV6_ADDRESS = /^\[(?:::1\]|f[cd]|fe[89ab])/;
const DEFAULT_HTTP_PORT = '80';

function parseUrl(value: string): URL | null {
  return URL.canParse(value) ? new URL(value) : null;
}

function isLoopbackHost(hostname: string): boolean {
  return hostname === 'localhost' || hostname === '[::1]' || (IPV4_ADDRESS.test(hostname) && hostname.startsWith('127.'));
}

function isPrivateHost(hostname: string): boolean {
  if (isLoopbackHost(hostname) || PRIVATE_IPV6_ADDRESS.test(hostname) || hostname.endsWith('.local')) return true;
  const address = IPV4_ADDRESS.exec(hostname);
  if (!address) return !hostname.includes('.') && !hostname.startsWith('[');
  const first = Number(address[1]);
  const second = Number(address[2]);
  return first === 10
    || (first === 172 && second >= 16 && second <= 31)
    || (first === 192 && second === 168)
    || (first === 169 && second === 254)
    || (first === 100 && second >= 64 && second <= 127);
}

export function planConnectionUrl(panelUrl: string, origin: string): ConnectionUrlPlan {
  const primary = (panelUrl.trim() || origin).replace(/\/+$/, '');
  const url = parseUrl(primary);
  if (!url) return { primary, sameMachine: null, loopbackOnly: false };
  const loopbackOnly = isLoopbackHost(url.hostname);
  const sameMachine = !loopbackOnly && url.protocol === 'http:'
    ? `http://127.0.0.1:${url.port || DEFAULT_HTTP_PORT}`
    : null;
  return { primary, sameMachine, loopbackOnly };
}

export function isPlainHttpToPublicHost(value: string): boolean {
  const url = parseUrl(value);
  return url?.protocol === 'http:' && !isPrivateHost(url.hostname);
}

export function renderConnectionSnippet({ mode, url, apiKey }: ConnectionSnippetValues): string {
  return [
    `mode: ${mode}`,
    '',
    'remote:',
    `  url: ${JSON.stringify(url)}`,
    `  api-key: ${JSON.stringify(apiKey)}`,
  ].join('\n');
}
