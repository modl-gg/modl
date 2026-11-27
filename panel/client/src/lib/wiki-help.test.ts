import test from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { SETTINGS_PAGE_HELP, SETUP_STEP_HELP } from './wiki-help.ts';
import { API_PAGE, MIGRATING_PAGE, PANEL_PORT_PAGE, wikiUrl, type WikiLink } from './wiki-links.ts';

const WIKI_ROOT = fileURLToPath(new URL('../../../../wiki/', import.meta.url));
const HEADING = /^#{1,6}\s+(.+)$/;
const FENCE = /^\s*(`{3,}|~{3,})/;

function anchorOf(heading: string): string {
  return heading.trim().toLowerCase().replace(/[^\p{L}\p{M}\p{N}_\s-]/gu, '').replace(/\s/g, '-');
}

function headingsOf(markdown: string): string[] {
  const headings: string[] = [];
  let fence: string | null = null;
  for (const line of markdown.split('\n')) {
    const marker = FENCE.exec(line)?.[1];
    if (marker !== undefined) {
      if (fence === null) fence = marker;
      else if (marker.startsWith(fence.charAt(0)) && marker.length >= fence.length) fence = null;
      continue;
    }
    const heading = fence === null ? HEADING.exec(line)?.[1] : undefined;
    if (heading !== undefined) headings.push(heading);
  }
  return headings;
}

function anchorsIn(markdown: string): string[] {
  const seen = new Map<string, number>();
  return headingsOf(markdown).map((heading) => {
    const anchor = anchorOf(heading);
    const repeats = seen.get(anchor) ?? 0;
    seen.set(anchor, repeats + 1);
    return repeats === 0 ? anchor : `${anchor}-${String(repeats)}`;
  });
}

function anchorsOf(page: string): Set<string> {
  return new Set(anchorsIn(readFileSync(`${WIKI_ROOT}${page}.md`, 'utf8')));
}

const LINKS: readonly (readonly [string, WikiLink])[] = [
  ...Object.entries(SETTINGS_PAGE_HELP).map(([page, link]) => [`settings page ${page}`, link] as const),
  ...Object.entries(SETUP_STEP_HELP).map(([step, link]) => [`setup step ${step}`, link] as const),
  ['the API link', API_PAGE],
  ['the panel port link', PANEL_PORT_PAGE],
  ['the migration link', MIGRATING_PAGE],
];

await test('every help link points at a wiki page and section that exist', () => {
  for (const [owner, link] of LINKS) {
    assert.ok(existsSync(`${WIKI_ROOT}${link.page}.md`), `${owner} links to the missing wiki page ${link.page}`);
    if (link.section !== undefined) {
      assert.ok(anchorsOf(link.page).has(link.section), `${owner} links to the missing section ${link.page}#${link.section}`);
    }
  }
});

await test('a wiki link is built from the wiki base, the page, and the section', () => {
  assert.equal(wikiUrl({ page: 'Replays' }), 'https://github.com/modl-gg/modl/wiki/Replays');
  assert.equal(wikiUrl({ page: 'Replays', section: 'settings' }), 'https://github.com/modl-gg/modl/wiki/Replays#settings');
});

await test('headings become anchors the way GitHub writes them', () => {
  assert.equal(anchorOf('MySQL, MariaDB, PostgreSQL'), 'mysql-mariadb-postgresql');
  assert.equal(anchorOf('Email (SMTP)'), 'email-smtp');
  assert.equal(anchorOf('From modl.gg'), 'from-modlgg');
  assert.equal(anchorOf('connection_yml keys'), 'connection_yml-keys');
});

await test('repeated headings get numbered anchors and headings in code blocks are ignored', () => {
  const markdown = ['## Setup', '```', '## Not a heading', '~~~', '## Still code', '```', '## Setup'].join('\n');
  assert.deepEqual(anchorsIn(markdown), ['setup', 'setup-1']);
});
