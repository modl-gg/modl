import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Controller, useForm, useWatch } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { Notice } from '@/components/ui/notice';
import {
  DefaultLanguageField,
  SERVER_NAME_MAX_LENGTH,
  ServerIconsField,
  ServerNameField,
} from '@/components/settings/ServerIdentityFields';
import {
  useGeneralSettings,
  usePatchGeneralSettings,
  type GeneralSettingsSnapshot,
  type ServerIconKind,
} from '@/hooks/data/settings';
import { useServerIconUpload } from '@/hooks/use-server-icon-upload';
import { isConflictError } from '@/lib/http-error';
import { serverIdentityChanges, serverIdentityOf } from '@/lib/settings-identity';
import { useBeforeContinue, useContinueReady } from '@/hooks/use-before-continue';
import { StepLoadState } from './StepLoadState';

const ICON_FIELDS = {
  homepage: 'homepageIconUrl',
  panel: 'panelIconUrl',
} as const satisfies Record<ServerIconKind, string>;

type SaveFeedback = 'conflict' | 'failed';

function useIdentitySchema(nameManaged: boolean) {
  const { t } = useTranslation();
  return useMemo(() => z.object({
    serverDisplayName: nameManaged
      ? z.string()
      : z.string().trim()
        .min(1, { error: t('settings.general.serverNameRequired') })
        .max(SERVER_NAME_MAX_LENGTH, { error: t('pages.setup.identity.nameTooLong', { max: SERVER_NAME_MAX_LENGTH }) }),
    defaultLanguage: z.string().min(1),
    homepageIconUrl: z.string(),
    panelIconUrl: z.string(),
  }), [t, nameManaged]);
}

type IdentityValues = z.infer<ReturnType<typeof useIdentitySchema>>;

interface IdentityFormProps {
  initial: GeneralSettingsSnapshot;
  loadLatest: () => Promise<GeneralSettingsSnapshot | undefined>;
}

function IdentityForm({ initial, loadLatest }: IdentityFormProps) {
  const { t } = useTranslation();
  const nameManaged = initial.serverNameManaged;
  const schema = useIdentitySchema(nameManaged);
  const patchSettings = usePatchGeneralSettings();
  const [base, setBase] = useState(initial);
  const [feedback, setFeedback] = useState<SaveFeedback | null>(null);
  const form = useForm<IdentityValues>({ resolver: zodResolver(schema), defaultValues: serverIdentityOf(initial) });
  const [homepageIconUrl, panelIconUrl] = useWatch({ control: form.control, name: ['homepageIconUrl', 'panelIconUrl'] });
  const iconUpload = useServerIconUpload((kind, url) => form.setValue(ICON_FIELDS[kind], url, { shouldDirty: true }));

  const save = async (values: IdentityValues): Promise<boolean> => {
    setFeedback(null);
    const changes = serverIdentityChanges(values, base);
    if (Object.keys(changes).length === 0) return true;
    try {
      setBase(await patchSettings.mutateAsync({ expectedVersion: base.version, changes }));
      return true;
    } catch (error) {
      if (!isConflictError(error)) {
        setFeedback('failed');
        return false;
      }
      const latest = await loadLatest();
      if (latest) setBase(latest);
      setFeedback('conflict');
      return false;
    }
  };

  useContinueReady(form.formState.isValid && !iconUpload.uploadingAny);

  useBeforeContinue(async () => new Promise<boolean>((resolve) => {
    void form.handleSubmit(async (values) => resolve(await save(values)), () => resolve(false))();
  }));

  return (
    <div className="space-y-6">
      <Controller
        control={form.control}
        name="serverDisplayName"
        render={({ field, fieldState }) => (
          <ServerNameField value={field.value} onChange={field.onChange} managed={nameManaged} error={fieldState.error?.message} />
        )}
      />
      <Controller
        control={form.control}
        name="defaultLanguage"
        render={({ field }) => <DefaultLanguageField value={field.value} onChange={field.onChange} />}
      />
      <ServerIconsField
        iconUrls={{ homepage: homepageIconUrl, panel: panelIconUrl }}
        isUploading={iconUpload.isUploading}
        onUpload={(kind, file) => { void iconUpload.upload(kind, file); }}
        onRemove={(kind) => form.setValue(ICON_FIELDS[kind], '', { shouldDirty: true })}
      />
      {feedback && (
        <Notice variant="error" announce>
          {feedback === 'conflict' ? t('pages.setup.identity.conflict') : t('pages.setup.identity.saveFailed')}
        </Notice>
      )}
    </div>
  );
}

export function IdentityStep() {
  const { data: saved, isError, isFetchedAfterMount, refetch } = useGeneralSettings();
  const loadLatest = async () => (await refetch()).data;

  if (!saved || !isFetchedAfterMount) {
    return <StepLoadState isError={isError} onRetry={() => { void refetch(); }} />;
  }
  return <IdentityForm initial={saved} loadLatest={loadLatest} />;
}
