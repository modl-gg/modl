import { useRef, type ChangeEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { Button } from '@/components/ui/button';

interface FileInputProps {
  id: string;
  accept: string;
  fileName: string | null;
  onChange: (event: ChangeEvent<HTMLInputElement>) => void;
  disabled?: boolean;
}

export function FileInput({ id, accept, fileName, onChange, disabled = false }: FileInputProps) {
  const { t } = useTranslation();
  const inputRef = useRef<HTMLInputElement>(null);

  return (
    <div className="flex min-w-0 items-center gap-3">
      <input ref={inputRef} id={id} type="file" accept={accept} className="sr-only" onChange={onChange} disabled={disabled} />
      <Button type="button" variant="outline" disabled={disabled} onClick={() => inputRef.current?.click()}>
        {t('common.chooseFile')}
      </Button>
      <span className="min-w-0 truncate text-sm text-muted-foreground">{fileName ?? t('common.noFileChosen')}</span>
    </div>
  );
}
