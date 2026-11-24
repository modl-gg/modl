import { useCallback } from 'react';
import { useTranslation } from 'react-i18next';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { TicketFileIcon } from './TicketFileIcon';

interface ObjectUrlImageProps {
  file: File;
  alt: string;
  className: string;
}

function ObjectUrlImage({ file, alt, className }: ObjectUrlImageProps) {
  const showFile = useCallback((image: HTMLImageElement | null) => {
    if (!image) return undefined;
    const objectUrl = URL.createObjectURL(file);
    image.src = objectUrl;
    return () => {
      URL.revokeObjectURL(objectUrl);
    };
  }, [file]);

  return <img ref={showFile} alt={alt} className={className} />;
}

export function PendingFilePreview({ file }: { file: File }) {
  const { t } = useTranslation();

  if (!file.type.startsWith('image/')) {
    return <TicketFileIcon fileType={file.type} />;
  }

  return (
    <Popover>
      <PopoverTrigger asChild>
        <button type="button" className="cursor-zoom-in" aria-label={t('ticket.filePreviewOpen', { name: file.name })}>
          <ObjectUrlImage
            file={file}
            alt={t('ticket.filePreview')}
            className="h-4 w-4 object-cover rounded-sm"
          />
        </button>
      </PopoverTrigger>
      <PopoverContent className="p-0 w-auto border-none bg-transparent">
        <ObjectUrlImage
          file={file}
          alt={t('ticket.filePreviewFull')}
          className="max-w-[300px] max-h-[300px] rounded-md shadow-lg border bg-background"
        />
      </PopoverContent>
    </Popover>
  );
}
