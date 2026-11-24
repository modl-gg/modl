import { useId, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { ActionForm } from '@/components/ui/action-form';
import { Loader2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { CheckButton } from '@/components/ui/check-button';
import { Checkbox } from '@/components/ui/checkbox';
import { Field } from '@/components/ui/field';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Notice } from '@/components/ui/notice';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { checkPassed, useCheckOutcome, type CheckOutcome } from '@/hooks/use-check-outcome';
import { useConfirmation } from '@/hooks/use-confirmation';
import { useToast } from '@/hooks/use-toast';
import { describeError } from '@/lib/http-error';
import {
  isMoveActive,
  useDatabaseSettings,
  useMoveDatabase,
  useTestDatabaseConnection,
  type DatabaseConnection,
  type DatabaseConnectionDraft,
  type DatabaseKindId,
  type DatabaseMove,
  type DatabaseRestartModeId,
  type DatabaseSettings as DatabaseSettingsData,
  type DatabaseTestResult,
  type DatabaseWarning,
} from '@/hooks/data/database';
import { FIELD_WIDTH, SettingsLoading, SettingsSection, SettingsSections } from './layout/SettingsSections';

const KIND_IDS: readonly DatabaseKindId[] = ['sqlite', 'mysql', 'mariadb', 'postgresql'];

const DEFAULT_PORTS: Record<DatabaseKindId, number> = {
  sqlite: 0,
  mysql: 3306,
  mariadb: 3306,
  postgresql: 5432,
};

const RESULT_CODES: ReadonlySet<string> = new Set([
  'OK',
  'DATABASE_INVALID',
  'DATABASE_SAME',
  'DATABASE_UNREACHABLE',
  'DATABASE_SIGN_IN_FAILED',
  'DATABASE_VERSION_UNSUPPORTED',
  'DATABASE_PRIVILEGES_MISSING',
  'DATABASE_IN_USE',
  'DATABASE_HOLDS_OTHER_DATA',
  'DATABASE_FAILED',
  'DATABASE_WRITES_BUSY',
  'DATABASE_COPY_FAILED',
  'DATABASE_NOT_REACHED_AT_START',
  'DATABASE_COPY_MISSING_AT_START',
  'DATABASE_IMPORT_RUNNING',
  'DATABASE_STORAGE_MOVING',
]);

const TEXT_FIELDS = ['host', 'database', 'username'] as const;

type DraftKind = DatabaseKindId | '';

interface Draft {
  kind: DraftKind;
  host: string;
  port: string;
  database: string;
  username: string;
  password: string;
  tls: boolean;
}

const EMPTY_DRAFT: Draft = { kind: '', host: '', port: '', database: '', username: '', password: '', tls: false };

function isKindId(value: string): value is DatabaseKindId {
  return KIND_IDS.some((kind) => kind === value);
}

function parsePort(value: string): number | null {
  if (!/^\d{1,5}$/.test(value.trim())) return null;
  const port = Number(value);
  return port >= 1 && port <= 65535 ? port : null;
}

function toConnectionDraft(draft: Draft): DatabaseConnectionDraft | null {
  if (draft.kind === '') return null;
  if (draft.kind === 'sqlite') {
    return { kind: 'sqlite', host: '', port: 0, database: '', username: '', password: '', tls: false };
  }
  const port = parsePort(draft.port);
  if (port === null || draft.host.trim() === '' || draft.database.trim() === '' || draft.username.trim() === '') return null;
  return {
    kind: draft.kind,
    host: draft.host,
    port,
    database: draft.database,
    username: draft.username,
    password: draft.password,
    tls: draft.tls,
  };
}

function useResultText() {
  const { t } = useTranslation();
  return (code: string, detail: string, fallback: string) => (RESULT_CODES.has(code)
    ? t(`settings.database.result.${code}`, { detail })
    : fallback);
}

const CONFIRM_KEYS: Record<DatabaseRestartModeId, string> = {
  'in-place': 'settings.database.moveConfirm',
  exit: 'settings.database.moveConfirmExit',
  manual: 'settings.database.moveConfirmManualRestart',
};

const WARNING_KEYS: Record<DatabaseWarning['kind'], string> = {
  setting: 'settings.database.warning.setting',
  'not-strict': 'settings.database.warning.notStrict',
  'journal-mode': 'settings.database.warning.journalMode',
  synchronous: 'settings.database.warning.synchronous',
  'not-encrypted': 'settings.database.warning.notEncrypted',
  'no-nightly-backups': 'settings.database.warning.noNightlyBackups',
};

function warningKey(warning: DatabaseWarning): string {
  return `${warning.kind}:${warning.setting}:${warning.host}`;
}

function connectionLabel(connection: DatabaseConnection, kindName: string): string {
  if (connection.kind === 'sqlite' || connection.host === '') return kindName;
  const address = connection.port === 0 ? connection.host : `${connection.host}:${String(connection.port)}`;
  return `${kindName} ${address}/${connection.database}`;
}

function MoveStatus({ move, kindName }: { move: DatabaseMove; kindName: string }) {
  const { t } = useTranslation();
  const resultText = useResultText();
  const target = connectionLabel(move.target, kindName);

  switch (move.phase) {
    case 'checking':
    case 'pausing-writes':
    case 'copying':
    case 'verifying':
    case 'restarting':
      return (
        <Notice variant="info" announce>
          <span className="inline-flex items-center gap-2">
            <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" />
            {t('settings.database.moving', { target, phase: t(`settings.database.phase.${move.phase}`) })}
          </span>
        </Notice>
      );
    case 'waiting-for-restart':
      return <Notice variant="warning" announce>{t('settings.database.waitingForRestart', { target })}</Notice>;
    case 'failed':
      return RESULT_CODES.has(move.errorCode)
        ? <Notice variant="error" announce>{resultText(move.errorCode, move.errorDetail, move.error)}</Notice>
        : <Notice variant="error" announce title={t('settings.database.moveFailed')}>{move.error}</Notice>;
  }
}

function MoveForm({ settings }: { settings: DatabaseSettingsData }) {
  const { t } = useTranslation();
  const { toast } = useToast();
  const resultText = useResultText();
  const confirmation = useConfirmation();
  const tlsId = useId();
  const [draft, setDraft] = useState<Draft>(EMPTY_DRAFT);
  const test = useTestDatabaseConnection();
  const move = useMoveDatabase();
  const locked = isMoveActive(settings.move) || move.isPending;
  const connection = toConnectionDraft(draft);
  const check = useCheckOutcome<DatabaseTestResult>(JSON.stringify(connection));
  const result = check.outcome?.kind === 'result' ? check.outcome.result : null;
  const external = draft.kind !== '' && draft.kind !== 'sqlite';
  const targetName = connection === null ? '' : connectionLabel(connection, t(`settings.database.kind.${connection.kind}`));

  const failureOf = (outcome: CheckOutcome<DatabaseTestResult> | null): string | null => {
    if (outcome === null) return null;
    if (outcome.kind === 'error') return describeError(outcome.error, t('settings.database.testFailed'));
    return outcome.result.ok ? null : resultText(outcome.result.code, outcome.result.detail, outcome.result.message);
  };
  const failure = failureOf(check.outcome);

  const update = (change: Partial<Draft>) => { setDraft((current) => ({ ...current, ...change })); };

  const changeKind = (value: string) => {
    if (!isKindId(value)) return;
    update({ kind: value, port: value === 'sqlite' ? '' : String(DEFAULT_PORTS[value]) });
  };

  const runTest = (tested: DatabaseConnectionDraft) => {
    void check.run(async () => test.mutateAsync(tested));
  };

  const runMove = async (target: DatabaseConnectionDraft, replacesCopy: boolean) => {
    const description = [
      t(CONFIRM_KEYS[settings.restartMode], { target: targetName }),
      ...(replacesCopy ? [t('settings.database.replacesCopy', { target: targetName })] : []),
    ].join(' ');
    const confirmed = await confirmation.confirm({
      title: t('settings.database.moveTitle', { target: targetName }),
      description,
      action: t('settings.database.move'),
      destructive: replacesCopy,
    });
    if (!confirmed) return;
    move.mutate({ draft: target, manualRestartAcknowledged: settings.restartMode === 'manual' }, {
      onSuccess: () => { setDraft(EMPTY_DRAFT); },
      onError: (error) => {
        toast({ title: t('toast.error'), description: describeError(error, t('settings.database.moveFailed')), variant: 'destructive' });
      },
    });
  };

  return (
    <ActionForm
      className="space-y-5"
      action={{
        submit: () => { if (connection !== null) runTest(connection); },
        canSubmit: !locked && connection !== null && !test.isPending,
      }}
    >
      <Field label={t('settings.database.kindLabel')} className={FIELD_WIDTH.short}>
        {(control) => (
          <Select value={draft.kind} onValueChange={changeKind} disabled={locked}>
            <SelectTrigger {...control}>
              <SelectValue placeholder={t('settings.database.kindPlaceholder')} />
            </SelectTrigger>
            <SelectContent>
              {KIND_IDS.map((kind) => (
                <SelectItem key={kind} value={kind}>{t(`settings.database.kind.${kind}`)}</SelectItem>
              ))}
            </SelectContent>
          </Select>
        )}
      </Field>
      {external && (
        <div className="grid gap-x-6 gap-y-5 sm:grid-cols-2">
          {TEXT_FIELDS.map((field) => (
            <Field key={field} label={t(`settings.database.field.${field}`)}>
              {(control) => (
                <Input
                  {...control}
                  autoComplete="off"
                  spellCheck={false}
                  value={draft[field]}
                  disabled={locked}
                  onChange={(event) => { update({ [field]: event.target.value }); }}
                />
              )}
            </Field>
          ))}
          <Field label={t('settings.database.field.port')}>
            {(control) => (
              <Input
                {...control}
                type="number"
                inputMode="numeric"
                min={1}
                max={65535}
                step={1}
                value={draft.port}
                disabled={locked}
                aria-invalid={parsePort(draft.port) === null}
                className={FIELD_WIDTH.number}
                onChange={(event) => { update({ port: event.target.value }); }}
              />
            )}
          </Field>
          <Field label={t('settings.database.field.password')}>
            {(control) => (
              <Input
                {...control}
                type="password"
                autoComplete="off"
                spellCheck={false}
                value={draft.password}
                disabled={locked}
                onChange={(event) => { update({ password: event.target.value }); }}
              />
            )}
          </Field>
          <div className="flex items-center gap-3 sm:col-span-2">
            <Checkbox
              id={tlsId}
              checked={draft.tls}
              disabled={locked}
              onCheckedChange={(checked) => { update({ tls: checked === true }); }}
            />
            <Label htmlFor={tlsId}>{t('settings.database.field.tls')}</Label>
          </div>
        </div>
      )}
      {failure !== null && <Notice variant="error" announce>{failure}</Notice>}
      {result?.ok === true && result.warnings.length > 0 && (
        <Notice variant="warning" announce>
          <ul className="space-y-1">
            {result.warnings.map((warning) => (
              <li key={warningKey(warning)}>
                {t(WARNING_KEYS[warning.kind], { setting: warning.setting, value: warning.value, expected: warning.expected, host: warning.host })}
              </li>
            ))}
          </ul>
        </Notice>
      )}
      <div className="flex flex-wrap items-center gap-3">
        <CheckButton
          label={t('settings.database.test')}
          pending={test.isPending}
          disabled={locked || connection === null}
          passed={checkPassed(check.outcome, (outcome) => outcome.ok)}
          onClick={() => { if (connection !== null) runTest(connection); }}
        />
        <Button
          type="button"
          disabled={locked || connection === null || result?.ok !== true}
          onClick={() => { if (connection !== null && result !== null) void runMove(connection, result.replacesCopy); }}
        >
          {t('settings.database.move')}
        </Button>
      </div>
      {confirmation.dialog}
    </ActionForm>
  );
}

const DatabaseSettings = () => {
  const { t } = useTranslation();
  const { data, isLoading, refetch } = useDatabaseSettings();

  if (isLoading) return <SettingsLoading />;

  if (!data) {
    return (
      <div className="space-y-3">
        <Notice variant="error" announce>{t('settings.database.loadFailed')}</Notice>
        <Button type="button" variant="outline" size="sm" onClick={() => { void refetch(); }}>
          {t('common.retry')}
        </Button>
      </div>
    );
  }

  return (
    <SettingsSections>
      <SettingsSection title={t('settings.database.currentTitle')}>
        <p className="text-sm text-muted-foreground">{connectionLabel(data.current, t(`settings.database.kind.${data.current.kind}`))}</p>
        {data.managed && <Notice variant="info">{t('settings.database.managed')}</Notice>}
        {data.move !== null && (
          <MoveStatus move={data.move} kindName={t(`settings.database.kind.${data.move.target.kind}`)} />
        )}
      </SettingsSection>
      {!data.managed && (
        <SettingsSection title={t('settings.database.moveSectionTitle')}>
          <MoveForm settings={data} />
        </SettingsSection>
      )}
    </SettingsSections>
  );
};

export default DatabaseSettings;
