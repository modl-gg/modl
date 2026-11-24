export interface EvidenceItem {
  text: string;
  issuerName: string;
  date: string | Date;
  type: 'text' | 'url' | 'file';
  fileUrl?: string;
  fileName?: string;
  fileType?: string;
  fileSize?: number;
}

export type EvidenceLike = string | Partial<EvidenceItem>;

/** Prevents "[object Object]" display issues */
export function getEvidenceDisplayText(evidenceItem: EvidenceLike): string {
  if (typeof evidenceItem === 'string') {
    return evidenceItem;
  }
  
  if (evidenceItem.type === 'file' && evidenceItem.fileName) {
    return evidenceItem.fileName;
  }

  return evidenceItem.text || evidenceItem.fileName || 'Evidence';
}

export function getEvidenceClickUrl(evidenceItem: EvidenceLike): string {
  if (typeof evidenceItem === 'string') {
    return evidenceItem.startsWith('http') ? evidenceItem : `/uploads/evidence/${evidenceItem}`;
  }
  
  if (evidenceItem.type === 'file') {
    return evidenceItem.fileUrl || `/uploads/evidence/${evidenceItem.fileName || evidenceItem.text || ''}`;
  }

  if (evidenceItem.type === 'url' && evidenceItem.text) {
    return evidenceItem.text;
  }

  if (evidenceItem.text) {
    return evidenceItem.text.startsWith('http') ? evidenceItem.text : `/uploads/evidence/${evidenceItem.text}`;
  }

  return '';
}

export function isEvidenceClickable(evidenceItem: EvidenceLike): boolean {
  const url = getEvidenceClickUrl(evidenceItem);
  // Coerce to a real boolean: a falsy url ('') would otherwise leak through the `&&`
  // as the empty string, contradicting the declared boolean return type.
  return Boolean(url) && (url.startsWith('http') || url.startsWith('/'));
}

export function getEvidenceShortName(evidenceItem: EvidenceLike, maxLength = 15): string {
  const displayText = getEvidenceDisplayText(evidenceItem);
  const fileName = displayText.includes('/') ? displayText.split('/').pop() : displayText;
  
  if (!fileName || fileName.length <= maxLength) {
    return fileName || displayText;
  }
  
  return fileName.substring(0, maxLength) + '...';
}

export type EvidenceMediaType = 'image' | 'video' | 'link' | 'text';

const IMAGE_EXTENSIONS = ['.jpg', '.jpeg', '.png', '.gif', '.webp'];
const VIDEO_EXTENSIONS = ['.mp4', '.webm', '.ogg', '.mov'];

function isAbsoluteOrRootRelative(url: string): boolean {
  return /^https?:\/\//i.test(url) || url.startsWith('/');
}

export function getEvidenceMediaType(value: string): EvidenceMediaType {
  const [path = ''] = value.toLowerCase().split(/[?#]/);

  if (IMAGE_EXTENSIONS.some((ext) => path.endsWith(ext))) return 'image';
  if (VIDEO_EXTENSIONS.some((ext) => path.endsWith(ext))) return 'video';
  if (isAbsoluteOrRootRelative(value)) return 'link';

  return 'text';
}

const LOCAL_STORAGE_OBJECT_PATH = '/v1/storage/objects/';
const ENCODED_PATH_SEPARATOR = /%(2f|5c|2e)/i;

interface ObjectStoreBase {
  readonly origin: string;
  readonly pathPrefix: string;
}

export interface MediaTrustPolicy {
  readonly origin: string;
  readonly objectStoreBase: ObjectStoreBase | null;
}

function isHttpUrl(url: URL): boolean {
  return url.protocol === 'http:' || url.protocol === 'https:';
}

function objectStoreBaseOf(publicBaseUrl: string | null | undefined): ObjectStoreBase | null {
  const raw = publicBaseUrl?.trim();
  if (!raw) return null;
  try {
    const base = new URL(raw);
    if (!isHttpUrl(base)) return null;
    const pathPrefix = base.pathname.endsWith('/') ? base.pathname : `${base.pathname}/`;
    return { origin: base.origin, pathPrefix };
  } catch {
    return null;
  }
}

export function createMediaTrustPolicy(origin: string, publicBaseUrl: string | null | undefined): MediaTrustPolicy {
  return { origin, objectStoreBase: objectStoreBaseOf(publicBaseUrl) };
}

function resolveMediaUrl(url: string | null | undefined, policy: MediaTrustPolicy): URL | null {
  const candidate = url?.trim();
  if (!candidate || !isAbsoluteOrRootRelative(candidate)) return null;
  try {
    const resolved = new URL(candidate, policy.origin);
    return isHttpUrl(resolved) && !ENCODED_PATH_SEPARATOR.test(resolved.pathname) ? resolved : null;
  } catch {
    return null;
  }
}

export function isLocalStorageObjectUrl(url: string | null | undefined, policy: MediaTrustPolicy): boolean {
  const resolved = resolveMediaUrl(url, policy);
  return resolved !== null
    && resolved.origin === policy.origin
    && resolved.pathname.startsWith(LOCAL_STORAGE_OBJECT_PATH);
}

export function isObjectStoreUrl(url: string | null | undefined, policy: MediaTrustPolicy): boolean {
  const base = policy.objectStoreBase;
  const resolved = resolveMediaUrl(url, policy);
  return base !== null
    && resolved !== null
    && resolved.origin === base.origin
    && resolved.pathname.startsWith(base.pathPrefix);
}

export function isTrustedMediaUrl(url: string | null | undefined, policy: MediaTrustPolicy): boolean {
  return isLocalStorageObjectUrl(url, policy) || isObjectStoreUrl(url, policy);
}

export function getTrustedEvidenceMediaType(value: string, policy: MediaTrustPolicy): EvidenceMediaType {
  const mediaType = getEvidenceMediaType(value);
  if (mediaType !== 'image' && mediaType !== 'video') {
    return mediaType;
  }
  if (isTrustedMediaUrl(value, policy)) {
    return mediaType;
  }
  return isAbsoluteOrRootRelative(value) ? 'link' : 'text';
}
