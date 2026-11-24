import { useState, type ChangeEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { Button } from '@/components/ui/button';
import { FileInput } from '@/components/ui/file-input';
import { Input } from '@/components/ui/input';
import { Field } from '@/components/ui/field';
import { Notice } from '@/components/ui/notice';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { useBeginImportUpload, useStartImportDownload, type ImportState } from '@/hooks/data/hosted-import';
import { apiErrorCode } from '@/lib/http-error';
import { errorKey } from './import-labels';

const SHA256 = /^[0-9a-fA-F]{64}$/;

type ImportSource = 'file' | 'link';

function isImportSource(value: string): value is ImportSource {
  return value === 'file' || value === 'link';
}

interface ImportSourceChoiceProps {
  onUploadStarted: (file: File, state: ImportState) => void;
}

export function ImportSourceChoice({ onUploadStarted }: ImportSourceChoiceProps) {
  const { t } = useTranslation();
  const beginUpload = useBeginImportUpload();
  const startDownload = useStartImportDownload();
  const [source, setSource] = useState<ImportSource>('file');
  const [file, setFile] = useState<File | null>(null);
  const [link, setLink] = useState('');
  const [checksum, setChecksum] = useState('');
  const checksumValid = checksum.trim() === '' || SHA256.test(checksum.trim());
  const failure = beginUpload.error ?? startDownload.error;
  const pending = beginUpload.isPending || startDownload.isPending;

  const chooseFile = (event: ChangeEvent<HTMLInputElement>) => setFile(event.target.files?.item(0) ?? null);

  const upload = () => {
    if (file === null) return;
    beginUpload.mutate({ file, expectedSha256: checksum.trim() }, { onSuccess: (state) => onUploadStarted(file, state) });
  };

  const download = () => {
    const url = link.trim();
    setLink('');
    startDownload.mutate({ url, expectedSha256: checksum.trim() });
  };

  return (
    <div className="space-y-4">
      <Tabs value={source} onValueChange={(value) => { if (isImportSource(value)) setSource(value); }}>
        <TabsList>
          <TabsTrigger value="file">{t('pages.setup.import.source.uploadTab')}</TabsTrigger>
          <TabsTrigger value="link">{t('pages.setup.import.source.linkTab')}</TabsTrigger>
        </TabsList>
        <TabsContent value="file" className="mt-4">
          <Field label={t('pages.setup.import.source.file')}>
            {(control) => <FileInput id={control.id} accept=".zip,application/zip" fileName={file?.name ?? null} onChange={chooseFile} />}
          </Field>
        </TabsContent>
        <TabsContent value="link" className="mt-4">
          <Field label={t('pages.setup.import.source.link')}>
            {(control) => (
              <Input {...control} type="url" autoComplete="off" className="max-w-md" value={link} onChange={(event) => setLink(event.target.value)} />
            )}
          </Field>
        </TabsContent>
      </Tabs>
      <Field label={t('pages.setup.import.source.checksum')}>
        {(control) => (
          <Input
            {...control}
            autoComplete="off"
            spellCheck={false}
            className="max-w-md font-mono"
            value={checksum}
            aria-invalid={!checksumValid}
            onChange={(event) => setChecksum(event.target.value)}
          />
        )}
      </Field>
      {source === 'file' ? (
        <Button type="button" variant="outline" disabled={file === null || !checksumValid || pending} onClick={upload}>
          {t('pages.setup.import.source.upload')}
        </Button>
      ) : (
        <Button type="button" variant="outline" disabled={link.trim() === '' || !checksumValid || pending} onClick={download}>
          {t('pages.setup.import.source.download')}
        </Button>
      )}
      {failure !== null && <Notice variant="error" announce>{t(errorKey(apiErrorCode(failure)))}</Notice>}
    </div>
  );
}
