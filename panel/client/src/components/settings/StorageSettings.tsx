import { useTranslation } from 'react-i18next';
import { PageForm } from '@/components/ui/action-form';
import { Button } from '@/components/ui/button';
import { CheckButton } from '@/components/ui/check-button';
import { Field } from '@/components/ui/field';
import { Input } from '@/components/ui/input';
import { Notice } from '@/components/ui/notice';
import { checkPassed, useCheckOutcome, type CheckOutcome } from '@/hooks/use-check-outcome';
import { useConfirmation } from '@/hooks/use-confirmation';
import { useToast } from '@/hooks/use-toast';
import { PERMISSIONS, usePermissions } from '@/hooks/use-permissions';
import { describeError } from '@/lib/http-error';
import { isS3Complete } from '@/lib/s3-connection';
import { formatFileSize } from '@/utils/file-utils';
import { StorageUsageBar } from './StorageUsageBar';
import {
  usePatchOperatorLimits,
  useRemoveOldCopies,
  useRetryStorageTransfer,
  useSaveS3Connection,
  useStorageSettings,
  useSwitchStorageBackend,
  useTestS3Connection,
  type OperatorLimitId,
  type OperatorLimitSetting,
  type S3Connection,
  type S3ConnectionDraft,
  type S3TestResult,
  type StorageBackendId,
  type StorageSettings as StorageSettingsData,
  type StorageTransfer,
} from '@/hooks/data/operations';
import { FIELD_WIDTH, SettingsLoading, SettingsSection, SettingsSections } from './layout/SettingsSections';
import { useSettingsDraft } from './save/use-settings-draft';

const LIMIT_GROUPS: readonly (readonly OperatorLimitId[])[] = [
  ['maxUploadMb', 'maxStorageGb'],
  ['backupKeep', 'backupHour'],
];

const S3_FAILURE_CODES: ReadonlySet<string> = new Set([
  'S3_INCOMPLETE',
  'S3_INVALID_ADDRESS',
  'S3_ACCESS_DENIED',
  'S3_BUCKET_NOT_FOUND',
  'S3_WRONG_REGION',
  'S3_UNREACHABLE',
  'S3_NOT_OWNED',
  'S3_FAILED',
]);

function useCanModifyStorage(): boolean {
  const { isSuperAdmin, hasPermission } = usePermissions();
  return isSuperAdmin || hasPermission(PERMISSIONS.ADMIN_SETTINGS_MODIFY_STORAGE) || hasPermission(PERMISSIONS.ADMIN_SETTINGS_MODIFY);
}

function parseLimit(value: string, limit: OperatorLimitSetting): number | null {
  if (!/^\d+$/.test(value.trim())) return null;
  const parsed = Number(value);
  return parsed >= limit.min && parsed <= limit.max ? parsed : null;
}

type LimitDrafts = Partial<Record<OperatorLimitId, string>>;

function limitDraftsOf(settings: StorageSettingsData): LimitDrafts {
  return Object.fromEntries(settings.limits.filter((limit) => !limit.managed).map((limit) => [limit.id, String(limit.value)]));
}

function changedLimitValues(drafts: LimitDrafts, settings: StorageSettingsData): Partial<Record<OperatorLimitId, number>> | null {
  const values: Partial<Record<OperatorLimitId, number>> = {};
  for (const limit of settings.limits) {
    if (limit.managed) continue;
    const parsed = parseLimit(drafts[limit.id] ?? String(limit.value), limit);
    if (parsed === null) return null;
    if (parsed !== limit.value) values[limit.id] = parsed;
  }
  return values;
}

function LimitsForm({ settings, canModify }: { settings: StorageSettingsData; canModify: boolean }) {
  const patch = usePatchOperatorLimits();
  const limits = new Map(settings.limits.map((limit) => [limit.id, limit]));
  const draft = useSettingsDraft({
    snapshot: canModify ? settings : undefined,
    fallback: limitDraftsOf(settings),
    valueOf: limitDraftsOf,
    isValid: (drafts) => changedLimitValues(drafts, settings) !== null,
    save: async (drafts, base) => {
      const values = changedLimitValues(drafts, base);
      if (values === null) return null;
      return patch.mutateAsync({ expectedVersion: base.limitsVersion, values });
    },
  });

  return (
    <div className="space-y-5">
      {LIMIT_GROUPS.map((group) => (
        <div key={group.join('-')} className="grid gap-x-6 gap-y-5 sm:grid-cols-2">
          {group.flatMap((id) => {
            const limit = limits.get(id);
            return limit === undefined ? [] : [(
              <LimitField
                key={id}
                limit={limit}
                value={limit.managed ? String(limit.value) : draft.value[id] ?? String(limit.value)}
                disabled={!canModify || limit.managed}
                onChange={(value) => { draft.update((current) => ({ ...current, [id]: value })); }}
              />
            )];
          })}
        </div>
      ))}
    </div>
  );
}

interface LimitFieldProps {
  limit: OperatorLimitSetting;
  value: string;
  disabled: boolean;
  onChange: (value: string) => void;
}

function LimitField({ limit, value, disabled, onChange }: LimitFieldProps) {
  const { t } = useTranslation();
  const invalid = !limit.managed && parseLimit(value, limit) === null;
  return (
    <Field
      label={t(`settings.storage.limits.${limit.id}.label`)}
      managed={limit.managed ? t('settings.operatorLimits.managedBy', { variable: limit.environmentVariable }) : null}
      error={invalid ? t('settings.storage.invalidNumber', { min: limit.min, max: limit.max }) : null}
    >
      {(control) => (
        <Input
          {...control}
          type="number"
          inputMode="numeric"
          min={limit.min}
          max={limit.max}
          step={1}
          value={value}
          disabled={disabled}
          className={FIELD_WIDTH.number}
          onChange={(event) => { onChange(event.target.value); }}
        />
      )}
    </Field>
  );
}

const S3_FIELDS = ['endpoint', 'region', 'bucket', 'accessKey', 'secretKey', 'publicUrl'] as const;

function draftFrom(connection: S3Connection): S3ConnectionDraft {
  return {
    endpoint: connection.endpoint,
    region: connection.region,
    bucket: connection.bucket,
    accessKey: connection.accessKey,
    secretKey: '',
    publicUrl: connection.publicUrl,
  };
}

function connectionOf(settings: StorageSettingsData): S3ConnectionDraft {
  return draftFrom(settings.s3);
}

interface S3FormProps {
  settings: StorageSettingsData;
  canModify: boolean;
  canSwitch: boolean;
}

function S3Form({ settings, canModify, canSwitch }: S3FormProps) {
  const { t } = useTranslation();
  const test = useTestS3Connection();
  const save = useSaveS3Connection();
  const editable = canModify && !settings.managed;
  const draft = useSettingsDraft({
    snapshot: editable ? settings : undefined,
    fallback: connectionOf(settings),
    valueOf: connectionOf,
    save: async (saving) => {
      const saved = await save.mutateAsync(saving);
      check.carry(JSON.stringify(saving), JSON.stringify(connectionOf(saved)));
      return saved;
    },
  });
  const connection = draft.value;
  const check = useCheckOutcome<S3TestResult>(JSON.stringify(connection));
  const complete = isS3Complete(connection, settings.s3);

  const failureOf = (outcome: CheckOutcome<S3TestResult> | null): string | null => {
    if (outcome === null) return null;
    if (outcome.kind === 'error') return describeError(outcome.error, t('settings.storage.testFailed'));
    if (outcome.result.ok) return null;
    return S3_FAILURE_CODES.has(outcome.result.code) ? t(`settings.storage.s3Result.${outcome.result.code}`) : outcome.result.message;
  };
  const failure = failureOf(check.outcome);

  return (
    <PageForm className="space-y-5">
      <div className="grid gap-x-6 gap-y-5 sm:grid-cols-2">
        {S3_FIELDS.map((field) => (
          <Field key={field} label={t(`settings.storage.s3.${field}`)}>
            {(control) => (
              <Input
                {...control}
                type={field === 'secretKey' ? 'password' : 'text'}
                autoComplete="off"
                spellCheck={false}
                value={draft.value[field]}
                disabled={!editable}
                placeholder={field === 'secretKey' && settings.s3.hasSecretKey
                  ? t('settings.storage.s3.secretKeySaved')
                  : t(`settings.storage.s3.${field}Placeholder`)}
                onChange={(event) => { draft.update((current) => ({ ...current, [field]: event.target.value })); }}
              />
            )}
          </Field>
        ))}
      </div>
      {failure !== null && <Notice variant="error" announce>{failure}</Notice>}
      <div className="flex flex-wrap items-center gap-3">
        {editable && (
          <CheckButton
            label={t('settings.storage.testS3')}
            pending={test.isPending}
            disabled={!complete}
            passed={checkPassed(check.outcome, (result) => result.ok)}
            onClick={() => { void check.run(async () => test.mutateAsync(connection)); }}
          />
        )}
        {canSwitch && <SwitchBackend settings={settings} s3Unsaved={draft.isDirty} />}
      </div>
    </PageForm>
  );
}

function TransferStatus({ transfer, canModify }: { transfer: StorageTransfer; canModify: boolean }) {
  const { t } = useTranslation();
  const { toast } = useToast();
  const retry = useRetryStorageTransfer();
  const progress = {
    copied: transfer.copiedObjects,
    size: formatFileSize(transfer.copiedBytes),
    skipped: transfer.skippedObjects,
    failed: transfer.failedObjects,
    target: t(`settings.storage.backend.${transfer.target}`),
  };

  switch (transfer.state) {
    case 'running':
      return <Notice variant="info">{t('settings.storage.transferRunning', progress)}</Notice>;
    case 'done':
      return <Notice variant="success">{t('settings.storage.transferDone', progress)}</Notice>;
    case 'failed':
      return (
        <div className="space-y-2">
          <Notice variant="error" announce>
            {t('settings.storage.transferFailed', { ...progress, error: transfer.lastError })}
          </Notice>
          {canModify && (
            <Button
              type="button"
              variant="outline"
              size="sm"
              disabled={retry.isPending}
              onClick={() => {
                retry.mutate(undefined, {
                  onError: (error) => {
                    toast({ title: t('toast.error'), description: describeError(error, t('settings.storage.saveFailed')), variant: 'destructive' });
                  },
                });
              }}
            >
              {t('settings.storage.retryTransfer')}
            </Button>
          )}
        </div>
      );
  }
}

function OldCopies({ settings, canRemove }: { settings: StorageSettingsData; canRemove: boolean }) {
  const { t } = useTranslation();
  const { toast } = useToast();
  const remove = useRemoveOldCopies();
  const confirmation = useConfirmation();
  if (settings.oldCopies.length === 0) return null;
  const places = settings.oldCopies.map((backend) => t(`settings.storage.backend.${backend}`)).join(', ');
  const moving = settings.transfer?.state === 'running' || settings.transfer?.state === 'failed';

  const confirmRemove = async () => {
    const confirmed = await confirmation.confirm({
      title: t('settings.storage.removeOldCopies'),
      description: t('settings.storage.removeOldCopiesConfirm', { places }),
      action: t('settings.storage.removeOldCopies'),
      destructive: true,
    });
    if (!confirmed) return;
    remove.mutate(undefined, {
      onError: (error) => {
        toast({ title: t('toast.error'), description: describeError(error, t('settings.storage.saveFailed')), variant: 'destructive' });
      },
    });
  };

  return (
    <div className="space-y-2">
      <p className="text-sm text-muted-foreground">{t('settings.storage.oldCopies', { places })}</p>
      {settings.removingOldCopies && <Notice variant="info" announce>{t('settings.storage.removingOldCopies')}</Notice>}
      {canRemove && !settings.removingOldCopies && !moving && (
        <Button type="button" variant="outline" disabled={remove.isPending} onClick={() => { void confirmRemove(); }}>
          {t('settings.storage.removeOldCopies')}
        </Button>
      )}
      {confirmation.dialog}
    </div>
  );
}

function SwitchBackend({ settings, s3Unsaved }: { settings: StorageSettingsData; s3Unsaved: boolean }) {
  const { t } = useTranslation();
  const { toast } = useToast();
  const switchBackend = useSwitchStorageBackend();
  const moving = settings.transfer?.state === 'running' || settings.transfer?.state === 'failed';
  const target: StorageBackendId = moving && settings.transfer !== null
    ? settings.transfer.source
    : settings.active === 's3' ? 'local' : 's3';
  const blocked = target === 's3' && (!settings.s3Configured || s3Unsaved);
  const labelKey = moving ? 'settings.storage.moveBack' : `settings.storage.moveTo.${target}`;
  const confirmation = useConfirmation();

  const confirmSwitch = async () => {
    const confirmed = await confirmation.confirm({
      title: t(labelKey),
      description: t('settings.storage.moveConfirm', { target: t(`settings.storage.backend.${target}`) }),
      action: t(labelKey),
    });
    if (!confirmed) return;
    switchBackend.mutate(target, {
      onError: (error) => {
        toast({ title: t('toast.error'), description: describeError(error, t('settings.storage.saveFailed')), variant: 'destructive' });
      },
    });
  };

  return (
    <>
      <Button type="button" variant="outline" disabled={blocked || switchBackend.isPending} onClick={() => { void confirmSwitch(); }}>
        {t(labelKey)}
      </Button>
      {confirmation.dialog}
    </>
  );
}

const StorageSettings = () => {
  const { t } = useTranslation();
  const canModify = useCanModifyStorage();
  const { isSuperAdmin } = usePermissions();
  const { data, isLoading, isError, refetch } = useStorageSettings();

  if (isLoading) return <SettingsLoading />;

  if (isError || !data) {
    return (
      <div className="space-y-3">
        <Notice variant="error" announce>{t('settings.storage.loadFailed')}</Notice>
        <Button type="button" variant="outline" size="sm" onClick={() => { void refetch(); }}>
          {t('common.retry')}
        </Button>
      </div>
    );
  }

  return (
    <SettingsSections>
      <SettingsSection title={t('settings.storage.whereTitle')}>
        <StorageUsageBar backend={data.active} usedBytes={data.usedBytes} capacityBytes={data.capacityBytes} usage={data.usage} />
        {data.managed && <Notice variant="info">{t('settings.storage.backendManaged')}</Notice>}
        {data.transfer !== null && <TransferStatus transfer={data.transfer} canModify={canModify} />}
        <OldCopies settings={data} canRemove={isSuperAdmin && !data.managed} />
      </SettingsSection>
      <SettingsSection title={t('settings.storage.s3Title')}>
        <S3Form settings={data} canModify={isSuperAdmin} canSwitch={canModify && !data.managed} />
      </SettingsSection>
      <SettingsSection title={t('settings.storage.limitsTitle')}>
        <LimitsForm settings={data} canModify={canModify} />
      </SettingsSection>
    </SettingsSections>
  );
};

export default StorageSettings;
