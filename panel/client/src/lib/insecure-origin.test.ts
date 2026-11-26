import test from 'node:test';
import assert from 'node:assert/strict';
import { readdirSync, readFileSync } from 'node:fs';
import path from 'node:path';

const CLIENT = path.resolve(import.meta.dirname, '..');
const SOURCE = /\.(ts|tsx)$/;
const SECURE_ONLY = /crypto\.subtle|\.subtle\./;

function sources(directory: string): string[] {
  const found: string[] = [];
  for (const entry of readdirSync(directory, { withFileTypes: true })) {
    const full = path.join(directory, entry.name);
    if (entry.isDirectory()) found.push(...sources(full));
    else if (SOURCE.test(entry.name) && !entry.name.endsWith('.test.ts')) found.push(full);
  }
  return found;
}

void test('the panel never needs Web Crypto, which plain http panels do not have', () => {
  const offending = sources(CLIENT)
    .filter((file) => SECURE_ONLY.test(readFileSync(file, 'utf8')))
    .map((file) => path.relative(CLIENT, file));
  assert.deepEqual(offending, []);
});
