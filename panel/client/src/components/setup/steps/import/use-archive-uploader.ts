import { useRef, useState } from 'react';
import { IMPORT_PATH, useCompleteImportUpload, useImportUploadProgress, type ImportState } from '@/hooks/data/hosted-import';
import { httpChunkSender } from '@/lib/archive-chunk-sender';
import { uploadArchive } from '@/lib/archive-upload';
import { apiErrorCode } from '@/lib/http-error';
import { errorKey } from './import-labels';

const UPLOAD_PATH = `${IMPORT_PATH}/upload`;

export interface ArchiveUploader {
  file: File | null;
  uploading: boolean;
  failedKey: string | null;
  start: (file: File, state: ImportState) => void;
  cancel: () => void;
}

export function useArchiveUploader(): ArchiveUploader {
  const progress = useImportUploadProgress();
  const complete = useCompleteImportUpload();
  const controllerRef = useRef<AbortController | null>(null);
  const [file, setFile] = useState<File | null>(null);
  const [uploading, setUploading] = useState(false);
  const [failedKey, setFailedKey] = useState<string | null>(null);

  const run = async (archive: File, state: ImportState) => {
    const abort = new AbortController();
    controllerRef.current = abort;
    setUploading(true);
    setFailedKey(null);
    try {
      await uploadArchive(archive, {
        path: UPLOAD_PATH,
        importId: state.importId,
        size: state.totalBytes,
        receivedBytes: state.receivedBytes,
        maxChunkBytes: state.maxChunkBytes,
      }, httpChunkSender({ path: UPLOAD_PATH, importId: state.importId }), progress, abort.signal);
      await complete.mutateAsync(state.importId);
    } catch (error: unknown) {
      if (!abort.signal.aborted) setFailedKey(errorKey(apiErrorCode(error)));
    } finally {
      setUploading(false);
    }
  };

  return {
    file,
    uploading,
    failedKey,
    start: (archive, state) => {
      setFile(archive);
      void run(archive, state);
    },
    cancel: () => controllerRef.current?.abort(),
  };
}
