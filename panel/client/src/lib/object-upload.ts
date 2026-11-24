import i18n from '@/lib/i18n';

export interface ObjectUploadTarget {
  url: string;
  method?: string | undefined;
  headers?: Record<string, string> | undefined;
}

export interface UploadProgress {
  loaded: number;
  total: number;
  percentage: number;
}

const BROWSER_CONTROLLED_HEADERS = new Set(['content-length', 'host', 'connection', 'accept-encoding']);

function resolveUploadUrl(url: string): string {
  return new URL(url, window.location.origin).toString();
}

export async function uploadObject(
  target: ObjectUploadTarget,
  file: Blob,
  onProgress?: (progress: UploadProgress) => void,
): Promise<void> {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();

    xhr.upload.addEventListener('progress', (event) => {
      if (event.lengthComputable && onProgress) {
        onProgress({
          loaded: event.loaded,
          total: event.total,
          percentage: Math.round((event.loaded / event.total) * 100),
        });
      }
    });

    xhr.addEventListener('load', () => {
      if (xhr.status >= 200 && xhr.status < 300) {
        resolve();
      } else {
        reject(new Error(i18n.t('upload.transferFailedStatus', { status: xhr.status })));
      }
    });
    xhr.addEventListener('error', () => reject(new Error(i18n.t('upload.networkError'))));
    xhr.addEventListener('abort', () => reject(new Error(i18n.t('upload.aborted'))));

    xhr.open(target.method || 'PUT', resolveUploadUrl(target.url), true);
    Object.entries(target.headers ?? {}).forEach(([name, value]) => {
      if (!BROWSER_CONTROLLED_HEADERS.has(name.toLowerCase())) {
        xhr.setRequestHeader(name, value);
      }
    });
    xhr.send(file);
  });
}
