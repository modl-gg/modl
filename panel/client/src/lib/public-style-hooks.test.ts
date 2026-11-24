import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { PUBLIC_MESSAGE_SENDERS } from './public-style-hooks.ts';
import { PUBLIC_PAGE_IDS } from '../utils/routes.ts';

const SOURCE_ROOT = fileURLToPath(new URL('..', import.meta.url));
const GOLDEN = fileURLToPath(new URL('./public-style-hooks.golden.txt', import.meta.url));
const WIKI_PAGE = fileURLToPath(new URL('../../../../wiki/Customizing-the-Public-Pages.md', import.meta.url));
const HOOK_IN_JSX = /data-modl="([a-z-]+)"/g;
const HOOK_IN_PROPS = /'data-modl': '([a-z-]+)'/g;

function sourceFiles(directory: string): string[] {
  return readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const path = join(directory, entry.name);
    if (entry.isDirectory()) return sourceFiles(path);
    return entry.name.endsWith('.tsx') ? [path] : [];
  });
}

function hooksInSource(): string[] {
  return sourceFiles(SOURCE_ROOT).flatMap((file) => {
    const source = readFileSync(file, 'utf8');
    return [...source.matchAll(HOOK_IN_JSX), ...source.matchAll(HOOK_IN_PROPS)].map((match) => `data-modl=${match[1] ?? ''}`);
  });
}

function publishedHooks(): string[] {
  return [...new Set([
    ...hooksInSource(),
    ...PUBLIC_PAGE_IDS.map((page) => `data-modl-page=${page}`),
    ...PUBLIC_MESSAGE_SENDERS.map((sender) => `data-modl-sender=${sender}`),
  ])].sort();
}

await test('the public styling hooks match the published contract', () => {
  const golden = readFileSync(GOLDEN, 'utf8').trim().split('\n');
  assert.deepEqual(publishedHooks(), golden);
});

await test('every public styling hook is documented in the wiki', () => {
  const wiki = readFileSync(WIKI_PAGE, 'utf8');
  for (const hook of publishedHooks()) {
    const value = hook.slice(hook.indexOf('=') + 1);
    assert.ok(wiki.includes(`\`${value}\``), `${hook} is missing from the wiki`);
  }
});
