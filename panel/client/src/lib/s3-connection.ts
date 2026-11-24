interface S3Account {
  endpoint: string;
  bucket: string;
  accessKey: string;
}

export interface S3ConnectionEntry extends S3Account {
  secretKey: string;
}

export interface StoredS3Connection extends S3Account {
  hasSecretKey: boolean;
}

function setting(value: string): string {
  return value.trim();
}

function endpointOf(value: string): string {
  return setting(value).replace(/\/+$/u, '');
}

function isSameAccount(entry: S3Account, stored: S3Account): boolean {
  return setting(entry.accessKey) === setting(stored.accessKey)
    && endpointOf(entry.endpoint) === endpointOf(stored.endpoint)
    && setting(entry.bucket) === setting(stored.bucket);
}

function hasSecretKey(entry: S3ConnectionEntry, stored: StoredS3Connection): boolean {
  return setting(entry.secretKey) !== '' || (stored.hasSecretKey && isSameAccount(entry, stored));
}

export function isS3Complete(entry: S3ConnectionEntry, stored: StoredS3Connection): boolean {
  return setting(entry.bucket) !== '' && setting(entry.accessKey) !== '' && hasSecretKey(entry, stored);
}
