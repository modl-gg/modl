import test from 'node:test';
import assert from 'node:assert/strict';
import { readdirSync } from 'node:fs';
import path from 'node:path';

const PANEL = path.resolve(import.meta.dirname, '../../../..');
const SKIPPED = new Set(['node_modules', 'dist', '.git']);
const FORBIDDEN = [
  /(^|\/)assets\/minecraft\//,
  /(^|\/)textures\/(block|blocks|item|items|entity|gui|colormap|font)\//,
  /(^|\/)public\/atlas(\/|$)/,
  /\.(png|mcmeta|jar|ogg)$/i,
  /(^|\/)en_us\.json$/i,
];

function files(directory: string): string[] {
  const found: string[] = [];
  for (const entry of readdirSync(directory, { withFileTypes: true })) {
    if (SKIPPED.has(entry.name)) continue;
    const full = path.join(directory, entry.name);
    if (entry.isDirectory()) found.push(...files(full));
    else found.push(path.relative(PANEL, full).split(path.sep).join('/'));
  }
  return found;
}

void test('the panel source contains no Minecraft game assets', () => {
  const offending = files(PANEL).filter((file) => FORBIDDEN.some((pattern) => pattern.test(file)));
  assert.deepEqual(offending, []);
});
