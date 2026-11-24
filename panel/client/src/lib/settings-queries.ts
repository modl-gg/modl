import { PUBLIC_SETTINGS_PATH } from './public-queries.ts';

const PANEL_SETTINGS_ROOT = '/v1/panel/settings';
const SETTINGS_ROOTS: ReadonlySet<string> = new Set(['/v1/settings', PUBLIC_SETTINGS_PATH, '/v1/panel/updates']);

export function isSettingsQueryKey(queryKey: readonly unknown[]): boolean {
  const [root] = queryKey;
  if (typeof root !== 'string') return false;
  return root === PANEL_SETTINGS_ROOT || root.startsWith(`${PANEL_SETTINGS_ROOT}/`) || SETTINGS_ROOTS.has(root);
}
