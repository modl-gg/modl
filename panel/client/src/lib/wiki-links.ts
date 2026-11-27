import { UPSTREAM_SOURCE_URL } from './upstream.ts';

export const WIKI_URL = `${UPSTREAM_SOURCE_URL}/wiki`;

export interface WikiLink {
  readonly page: string;
  readonly section?: string | undefined;
}

export const API_PAGE: WikiLink = { page: 'API' };
export const PANEL_PORT_PAGE: WikiLink = { page: 'Panel-Port-and-Tunnels' };
export const MIGRATING_PAGE: WikiLink = { page: 'Migrating-from-Other-Plugins' };

export function wikiUrl({ page, section }: WikiLink): string {
  return section === undefined ? `${WIKI_URL}/${page}` : `${WIKI_URL}/${page}#${section}`;
}
