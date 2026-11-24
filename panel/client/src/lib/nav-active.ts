import { LOOKUP_PATH, PLAYER_PAGE_PREFIX } from './player-links.ts';

const PANEL_HOME = '/panel';

export function isNavPathActive(itemPath: string, location: string): boolean {
  if (location === itemPath) return true;
  if (itemPath === LOOKUP_PATH) return location.startsWith(PLAYER_PAGE_PREFIX);
  return itemPath !== PANEL_HOME && location.startsWith(`${itemPath}/`);
}
