import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { sha1Hex } from './sha1.ts';

const encoder = new TextEncoder();

await test('sha1 matches the standard test vectors', () => {
  assert.equal(sha1Hex(encoder.encode('')), 'da39a3ee5e6b4b0d3255bfef95601890afd80709');
  assert.equal(sha1Hex(encoder.encode('abc')), 'a9993e364706816aba3e25717850c26c9cd0d89d');
  assert.equal(sha1Hex(encoder.encode('abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq')), '84983e441c3bd26ebaae4aa1f95129e5e54670f1');
});

await test('sha1 agrees with node crypto across block boundaries', () => {
  for (const length of [55, 56, 63, 64, 65, 119, 120, 128, 1000, 100_000]) {
    const bytes = new Uint8Array(length).map((_, index) => (index * 31 + 7) & 0xff);
    assert.equal(sha1Hex(bytes), createHash('sha1').update(bytes).digest('hex'), `length ${String(length)}`);
  }
});
