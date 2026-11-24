import test from 'node:test';
import assert from 'node:assert/strict';

import {
  createMediaTrustPolicy,
  getTrustedEvidenceMediaType,
  isLocalStorageObjectUrl,
  isObjectStoreUrl,
  isTrustedMediaUrl,
} from './evidence-utils.ts';

const ORIGIN = 'http://203.0.113.7:8080';
const localOnly = createMediaTrustPolicy(ORIGIN, '');
const withObjectStore = createMediaTrustPolicy(ORIGIN, 'https://media.example.com/bucket');
const withBucketHost = createMediaTrustPolicy(ORIGIN, 'https://bucket.example.com');

await test('relative and same-origin local storage objects are trusted', () => {
  assert.equal(isTrustedMediaUrl('/v1/storage/objects/evidence/a.png', localOnly), true);
  assert.equal(isTrustedMediaUrl(`${ORIGIN}/v1/storage/objects/evidence/a.png`, localOnly), true);
  assert.equal(isLocalStorageObjectUrl('/v1/storage/objects/evidence/a.png', localOnly), true);
});

await test('same-origin URLs outside the storage path are not trusted', () => {
  assert.equal(isTrustedMediaUrl('/uploads/a.png', localOnly), false);
  assert.equal(isTrustedMediaUrl(`${ORIGIN}/v1/panel/players/a.png`, localOnly), false);
  assert.equal(isTrustedMediaUrl('/v1/storage/objectsx/a.png', localOnly), false);
  assert.equal(isTrustedMediaUrl('/v1/storage/objects/../../v1/panel/a.png', localOnly), false);
  assert.equal(isTrustedMediaUrl('/v1/storage/objects/%2e%2e/%2e%2e/v1/panel/a.png', localOnly), false);
});

await test('encoded path separators are never trusted', () => {
  assert.equal(isTrustedMediaUrl('/v1/storage/objects/..%2F..%2Fv1%2Fpanel%2Fa.png', localOnly), false);
  assert.equal(isTrustedMediaUrl('/v1/storage/objects/evidence%5ca.png', localOnly), false);
  assert.equal(isTrustedMediaUrl('https://media.example.com/bucket/..%2Fother%2Fa.png', withObjectStore), false);
  assert.equal(isTrustedMediaUrl('https://media.example.com/bucket/%2e%2e/other/a.png', withObjectStore), false);
});

await test('the object store is trusted only on its origin and below its base path', () => {
  assert.equal(isTrustedMediaUrl('https://media.example.com/bucket/a.png', withObjectStore), true);
  assert.equal(isObjectStoreUrl('https://media.example.com/bucket/evidence/a.png', withObjectStore), true);
  assert.equal(isTrustedMediaUrl('https://media.example.com/bucket/a.png', localOnly), false);
});

await test('the same object store host with another bucket or path is not trusted', () => {
  assert.equal(isTrustedMediaUrl('https://media.example.com/other-bucket/a.png', withObjectStore), false);
  assert.equal(isTrustedMediaUrl('https://media.example.com/bucket-evil/a.png', withObjectStore), false);
  assert.equal(isTrustedMediaUrl('https://media.example.com/bucket/../other/a.png', withObjectStore), false);
  assert.equal(isTrustedMediaUrl('http://media.example.com/bucket/a.png', withObjectStore), false);
  assert.equal(isTrustedMediaUrl('https://media.example.com:8443/bucket/a.png', withObjectStore), false);
});

await test('an object store base without a path trusts its whole origin', () => {
  assert.equal(isTrustedMediaUrl('https://bucket.example.com/evidence/a.png', withBucketHost), true);
  assert.equal(isTrustedMediaUrl('https://other.example.com/evidence/a.png', withBucketHost), false);
});

await test('foreign, protocol-relative and non-http URLs are not trusted', () => {
  assert.equal(isTrustedMediaUrl('https://evil.example.net/a.png', withObjectStore), false);
  assert.equal(isTrustedMediaUrl('//evil.example.net/v1/storage/objects/a.png', localOnly), false);
  assert.equal(isTrustedMediaUrl('/\\evil.example.net/v1/storage/objects/a.png', localOnly), false);
  assert.equal(isTrustedMediaUrl('http://203.0.113.7:9090/v1/storage/objects/a.png', localOnly), false);
  assert.equal(isTrustedMediaUrl('javascript:alert(1)//a.png', localOnly), false);
  assert.equal(isTrustedMediaUrl('a.png', localOnly), false);
  assert.equal(isTrustedMediaUrl(null, localOnly), false);
});

await test('untrusted media downgrades to a link or plain text', () => {
  assert.equal(getTrustedEvidenceMediaType('/v1/storage/objects/clip.mp4', localOnly), 'video');
  assert.equal(getTrustedEvidenceMediaType('/uploads/clip.mp4', localOnly), 'link');
  assert.equal(getTrustedEvidenceMediaType('https://evil.example.net/a.png', localOnly), 'link');
  assert.equal(getTrustedEvidenceMediaType('a.png', localOnly), 'text');
});
