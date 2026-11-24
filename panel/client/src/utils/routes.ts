export const PUBLIC_PAGE_IDS = ['home', 'knowledgebase', 'article', 'appeal', 'submit-ticket', 'ticket', 'upload-evidence'] as const;

export type PublicPageId = (typeof PUBLIC_PAGE_IDS)[number];

const PUBLIC_PAGE_PREFIXES: readonly (readonly [string, PublicPageId])[] = [
  ['/knowledgebase', 'knowledgebase'],
  ['/article/', 'article'],
  ['/appeal', 'appeal'],
  ['/submit-ticket', 'submit-ticket'],
  ['/ticket/', 'ticket'],
  ['/upload-evidence/', 'upload-evidence'],
];

export function publicPageOf(path: string): PublicPageId | null {
  if (path === '/') return 'home';
  return PUBLIC_PAGE_PREFIXES.find(([prefix]) => path.startsWith(prefix))?.[1] ?? null;
}

export function isPublicPage(path?: string): boolean {
  const page = publicPageOf(path || window.location.pathname);
  return page !== null && page !== 'upload-evidence';
}

export function articlePath(slug: string): string {
  return `/article/${encodeURIComponent(slug)}`;
}
