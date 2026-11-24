const PREVIEWABLE_TYPES_BY_EXTENSION: ReadonlyMap<string, string> = new Map([
  ['jpg', 'image/jpg'],
  ['jpeg', 'image/jpeg'],
  ['png', 'image/png'],
  ['gif', 'image/gif'],
  ['webp', 'image/webp'],
  ['mp4', 'video/mp4'],
  ['webm', 'video/webm'],
  ['mov', 'video/mov'],
]);

const ELLIPSIS = '...';

export function truncateFileName(fileName: string, maxLength = 15): string {
  if (fileName.length <= maxLength) return fileName;
  const extensionStart = fileName.lastIndexOf('.');
  if (extensionStart <= 0) return `${fileName.substring(0, Math.max(0, maxLength - ELLIPSIS.length))}${ELLIPSIS}`;
  const extension = fileName.substring(extensionStart + 1);
  const name = fileName.substring(0, extensionStart);
  return `${name.substring(0, Math.max(0, maxLength - extension.length - 4))}${ELLIPSIS}.${extension}`;
}

export function lastPathSegment(url: string): string {
  return url.split('/').at(-1) ?? '';
}

export function previewableTypeOfUrl(url: string): string | undefined {
  const [path = ''] = url.split(/[?#]/);
  const extension = path.split('.').at(-1)?.toLowerCase() ?? '';
  return PREVIEWABLE_TYPES_BY_EXTENSION.get(extension);
}
