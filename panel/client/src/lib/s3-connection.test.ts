import test from 'node:test';
import assert from 'node:assert/strict';

import { isS3Complete } from './s3-connection.ts';

const STORED = { endpoint: 'https://s3.example.com', bucket: 'media', accessKey: 'key', hasSecretKey: true };
const ENTRY = { endpoint: 'https://s3.example.com', bucket: 'media', accessKey: 'key', secretKey: '' };

await test('a connection needs a bucket, an access key and a secret key, but no endpoint', () => {
  const empty = { endpoint: '', bucket: '', accessKey: '', hasSecretKey: false };
  assert.equal(isS3Complete({ endpoint: '', bucket: 'media', accessKey: 'key', secretKey: 'secret' }, empty), true);
  assert.equal(isS3Complete({ endpoint: '', bucket: ' ', accessKey: 'key', secretKey: 'secret' }, empty), false);
  assert.equal(isS3Complete({ endpoint: '', bucket: 'media', accessKey: '', secretKey: 'secret' }, empty), false);
  assert.equal(isS3Complete({ endpoint: '', bucket: 'media', accessKey: 'key', secretKey: ' ' }, empty), false);
});

await test('the saved secret key counts only for the same access key, endpoint and bucket', () => {
  assert.equal(isS3Complete(ENTRY, STORED), true);
  assert.equal(isS3Complete({ ...ENTRY, endpoint: ' https://s3.example.com/ ', bucket: 'media ' }, STORED), true);
  assert.equal(isS3Complete({ ...ENTRY, accessKey: 'other' }, STORED), false);
  assert.equal(isS3Complete({ ...ENTRY, endpoint: '' }, STORED), false);
  assert.equal(isS3Complete({ ...ENTRY, bucket: 'other' }, STORED), false);
  assert.equal(isS3Complete(ENTRY, { ...STORED, hasSecretKey: false }), false);
  assert.equal(isS3Complete({ ...ENTRY, accessKey: 'other', secretKey: 'secret' }, STORED), true);
});
