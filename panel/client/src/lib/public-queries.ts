import type { QueryKey } from '@tanstack/react-query';

const PUBLIC_API_PREFIX = '/v1/public/';

export const PUBLIC_SETTINGS_PATH = '/v1/public/settings';
export const PUBLIC_SETTINGS_QUERY_KEY = [PUBLIC_SETTINGS_PATH] as const;

export function isPublicQueryKey(queryKey: QueryKey): boolean {
  const [scope] = queryKey;
  return typeof scope === 'string' && scope.startsWith(PUBLIC_API_PREFIX);
}
