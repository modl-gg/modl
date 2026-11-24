import { useCallback, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useToast } from '@/hooks/use-toast';
import { useUploadServerIcon, type ServerIconKind } from '@/hooks/data/settings';
import { describeError } from '@/lib/http-error';

export interface ServerIconUpload {
  upload: (kind: ServerIconKind, file: File) => Promise<void>;
  isUploading: (kind: ServerIconKind) => boolean;
  uploadingAny: boolean;
}

export function useServerIconUpload(onUploaded: (kind: ServerIconKind, url: string) => void): ServerIconUpload {
  const { t } = useTranslation();
  const { toast } = useToast();
  const uploadIcon = useUploadServerIcon();
  const [uploading, setUploading] = useState<ReadonlySet<ServerIconKind>>(() => new Set());

  const markUploading = (kind: ServerIconKind, active: boolean) => {
    setUploading((current) => {
      const next = new Set(current);
      if (active) {
        next.add(kind);
      } else {
        next.delete(kind);
      }
      return next;
    });
  };

  const upload = async (kind: ServerIconKind, file: File) => {
    markUploading(kind, true);
    try {
      onUploaded(kind, await uploadIcon.mutateAsync({ kind, file }));
    } catch (error) {
      toast({
        title: t('settings.page.uploadFailed'),
        description: describeError(error, t('settings.page.uploadFailedDesc')),
        variant: 'destructive',
      });
    } finally {
      markUploading(kind, false);
    }
  };

  const isUploading = useCallback((kind: ServerIconKind) => uploading.has(kind), [uploading]);

  return { upload, isUploading, uploadingAny: uploading.size > 0 };
}
