import { useId, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import { Loader2, Trash2, Upload } from 'lucide-react';
import { Field } from '@/components/ui/field';
import { IconAction } from '@/components/ui/icon-action';
import { Input } from '@/components/ui/input';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import type { ServerIconKind } from '@/hooks/data/settings';
import { SUPPORTED_LANGUAGES } from '@/lib/languages';
import { useConfirmation } from '@/hooks/use-confirmation';

export const SERVER_NAME_MAX_LENGTH = 80;

export type ServerIconUrls = Record<ServerIconKind, string>;

interface ServerNameFieldProps {
  value: string;
  onChange: (value: string) => void;
  managed: boolean;
  error?: string | undefined;
}

export function ServerNameField({ value, onChange, managed, error }: ServerNameFieldProps) {
  const { t } = useTranslation();

  return (
    <Field
      label={t('settings.general.serverDisplayName')}
      error={error}
      managed={managed ? t('settings.general.serverNameManagedDesc') : null}
    >
      {(control) => (
        <Input
          {...control}
          placeholder={t('settings.general.serverDisplayNamePlaceholder')}
          value={value}
          maxLength={SERVER_NAME_MAX_LENGTH}
          onChange={(event) => { onChange(event.target.value); }}
          readOnly={managed}
          className="max-w-md"
        />
      )}
    </Field>
  );
}

interface DefaultLanguageFieldProps {
  value: string;
  onChange: (value: string) => void;
}

export function DefaultLanguageField({ value, onChange }: DefaultLanguageFieldProps) {
  const { t } = useTranslation();

  return (
    <Field label={t('settings.general.defaultLanguage')}>
      {(control) => (
        <Select value={value} onValueChange={onChange}>
          <SelectTrigger {...control} className="max-w-xs">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {SUPPORTED_LANGUAGES.map((language) => (
              <SelectItem key={language.code} value={language.code}>{language.nativeName}</SelectItem>
            ))}
          </SelectContent>
        </Select>
      )}
    </Field>
  );
}

const SERVER_ICON_LABEL_KEYS: Record<ServerIconKind, string> = {
  homepage: 'settings.general.homepageIcon',
  panel: 'settings.general.panelIcon',
};

const SERVER_ICON_KINDS: readonly ServerIconKind[] = ['homepage', 'panel'];

interface ServerIconsFieldProps {
  iconUrls: ServerIconUrls;
  isUploading: (kind: ServerIconKind) => boolean;
  onUpload: (kind: ServerIconKind, file: File) => void;
  onRemove: (kind: ServerIconKind) => void;
}

interface ServerIconFieldProps extends Omit<ServerIconsFieldProps, 'iconUrls' | 'isUploading'> {
  kind: ServerIconKind;
  url: string;
  uploading: boolean;
}

function ServerIconField({ kind, url, uploading, onUpload, onRemove }: ServerIconFieldProps) {
  const { t } = useTranslation();
  const fileInputRef = useRef<HTMLInputElement>(null);
  const confirmation = useConfirmation();
  const labelId = useId();
  const label = t(SERVER_ICON_LABEL_KEYS[kind]);

  const confirmRemove = async () => {
    const confirmed = await confirmation.confirm({
      title: t('settings.general.removeIconTitle', { icon: label }),
      description: t('settings.general.removeIconDescription'),
      action: t('common.remove'),
      destructive: true,
    });
    if (confirmed) onRemove(kind);
  };

  return (
    <div role="group" aria-labelledby={labelId} className="space-y-2">
      <p id={labelId} className="text-sm font-medium leading-none">{label}</p>
      <div className="flex items-center gap-3">
        <input
          ref={fileInputRef}
          type="file"
          accept="image/*"
          className="hidden"
          aria-label={label}
          onChange={(event) => {
            const file = event.target.files?.[0];
            event.target.value = '';
            if (file) onUpload(kind, file);
          }}
        />
        <div className="relative h-20 w-20 shrink-0 overflow-hidden rounded-lg border bg-surface-2">
          {url && <img src={url} alt={label} className="h-full w-full object-cover" />}
          {uploading && (
            <span className="absolute inset-0 flex items-center justify-center bg-black/55">
              <Loader2 className="h-5 w-5 animate-spin text-white" aria-hidden="true" />
            </span>
          )}
        </div>
        <div className="flex flex-col gap-2">
          <IconAction
            icon={Upload}
            variant="outline"
            label={t('settings.general.uploadIcon', { icon: label })}
            disabled={uploading}
            onClick={() => fileInputRef.current?.click()}
          />
          <IconAction
            icon={Trash2}
            variant="outline"
            destructive
            label={t('settings.general.removeIcon', { icon: label })}
            disabled={!url || uploading}
            onClick={() => { void confirmRemove(); }}
          />
        </div>
      </div>
      {confirmation.dialog}
    </div>
  );
}

export function ServerIconsField({ iconUrls, isUploading, onUpload, onRemove }: ServerIconsFieldProps) {
  return (
    <div className="flex flex-wrap gap-10">
      {SERVER_ICON_KINDS.map((kind) => (
        <ServerIconField
          key={kind}
          kind={kind}
          url={iconUrls[kind]}
          uploading={isUploading(kind)}
          onUpload={onUpload}
          onRemove={onRemove}
        />
      ))}
    </div>
  );
}
