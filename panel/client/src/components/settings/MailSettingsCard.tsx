import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { PageForm } from '@/components/ui/action-form';
import { useForm, useWatch } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { Button } from '@/components/ui/button';
import { CheckButton } from '@/components/ui/check-button';
import { Input } from '@/components/ui/input';
import { Switch } from '@/components/ui/switch';
import { Notice } from '@/components/ui/notice';
import { LoadingSpinner } from '@/components/ui/loading-spinner';
import { Form, FormControl, FormField, FormItem, FormLabel, FormMessage } from '@/components/ui/form';
import { checkPassed, useCheckOutcome, type CheckOutcome } from '@/hooks/use-check-outcome';
import { useConfirmation } from '@/hooks/use-confirmation';
import { usePermissions } from '@/hooks/use-permissions';
import {
  classifyMailSaveFailure,
  useMailSettings,
  useRemoveMailSettings,
  useSaveMailSettings,
  useSendTestMail,
  type MailCredentials,
  type MailFailureReason,
  type MailSettingsState,
  type MailTestResult,
} from '@/hooks/data/settings';
import { trimmedEmail } from '@/lib/form-schemas';
import { optionalField } from '@/lib/defined-fields';
import { useBeforeContinue, useContinueReady } from '@/hooks/use-before-continue';
import { useSettingsSaveSource } from './save/settings-save-scope';

const DEFAULT_SMTP_PORT = '587';
const MIN_PORT = 1;
const MAX_PORT = 65535;
const HOST_MAX_LENGTH = 253;
const USERNAME_MAX_LENGTH = 254;
const PASSWORD_MAX_LENGTH = 1024;
const PORT_DIGITS = /^\d{1,5}$/;

const FAILURE_MESSAGES: Record<MailFailureReason, string> = {
  'connect-failed': 'settings.mail.failureConnect',
  'tls-failed': 'settings.mail.failureTls',
  'auth-failed': 'settings.mail.failureAuth',
  rejected: 'settings.mail.failureRejected',
  'timed-out': 'settings.mail.failureTimeout',
};

type MailFeedback =
  | { kind: 'failure'; titleKey: string; reason: MailFailureReason; detail: string }
  | { kind: 'error'; messageKey: string };

function testFeedback(outcome: CheckOutcome<MailTestResult> | null): MailFeedback | null {
  if (outcome === null) return null;
  if (outcome.kind === 'error') return { kind: 'error', messageKey: 'settings.mail.testError' };
  const { result } = outcome;
  if (result.outcome === 'delivered') return null;
  return { kind: 'failure', titleKey: 'settings.mail.testFailed', reason: result.outcome, detail: result.detail };
}

function isValidPort(value: string): boolean {
  if (!PORT_DIGITS.test(value)) return false;
  const port = Number(value);
  return port >= MIN_PORT && port <= MAX_PORT;
}

function storedPasswordStillApplies(values: { host: string; port: string; username: string }, stored: MailSettingsState): boolean {
  const settings = stored.settings;
  return settings !== null
    && values.host.trim() === settings.host
    && Number(values.port) === settings.port
    && values.username.trim() === settings.username;
}

function useMailSchema(stored: MailSettingsState) {
  const { t } = useTranslation();
  return useMemo(() => z.object({
    host: z.string().trim().min(1, { error: t('settings.mail.hostRequired') }).max(HOST_MAX_LENGTH, { error: t('settings.mail.hostTooLong') }),
    port: z.string().trim().refine(isValidPort, { error: t('settings.mail.portInvalid', { min: MIN_PORT, max: MAX_PORT }) }),
    username: z.string().trim().max(USERNAME_MAX_LENGTH, { error: t('settings.mail.usernameTooLong') }),
    password: z.string().max(PASSWORD_MAX_LENGTH, { error: t('settings.mail.passwordTooLong') }),
    from: trimmedEmail(t('settings.mail.fromInvalid')),
    starttls: z.boolean(),
  }).superRefine((values, context) => {
    if (stored.passwordSet && values.password.length === 0 && !storedPasswordStillApplies(values, stored)) {
      context.addIssue({ code: 'custom', path: ['password'], message: t('settings.mail.passwordRequired') });
    }
  }), [t, stored]);
}

type MailFormValues = z.infer<ReturnType<typeof useMailSchema>>;

function formValuesOf(state: MailSettingsState): MailFormValues {
  const settings = state.settings;
  return {
    host: settings?.host ?? '',
    port: settings && settings.port > 0 ? String(settings.port) : DEFAULT_SMTP_PORT,
    username: settings?.username ?? '',
    password: '',
    from: settings?.from ?? '',
    starttls: settings?.starttls ?? true,
  };
}

const MAIL_FIELDS = ['host', 'port', 'username', 'password', 'from', 'starttls'] as const satisfies readonly (keyof MailFormValues)[];

function credentialsOf(values: MailFormValues): MailCredentials {
  return {
    settings: {
      host: values.host,
      port: Number(values.port),
      username: values.username,
      from: values.from,
      starttls: values.starttls,
    },
    ...optionalField('password', values.password.length > 0 ? values.password : undefined),
  };
}

function MailFeedbackNotice({ feedback }: { feedback: MailFeedback }) {
  const { t } = useTranslation();

  switch (feedback.kind) {
    case 'failure':
      return (
        <Notice variant="error" title={t(feedback.titleKey)} announce>
          <p>{t(FAILURE_MESSAGES[feedback.reason])}</p>
          {feedback.detail && <p className="mt-1 font-mono text-xs break-all">{feedback.detail}</p>}
        </Notice>
      );
    case 'error':
      return <Notice variant="error" announce>{t(feedback.messageKey)}</Notice>;
  }
}

function MailSettingsForm({ state }: { state: MailSettingsState }) {
  const { t } = useTranslation();
  const schema = useMailSchema(state);
  const saveMail = useSaveMailSettings();
  const removeMail = useRemoveMailSettings();
  const sendTest = useSendTestMail();
  const confirmation = useConfirmation();
  const [feedback, setFeedback] = useState<MailFeedback | null>(null);
  const managed = state.source === 'config';
  const busy = saveMail.isPending || removeMail.isPending || sendTest.isPending;

  const savedValues = useMemo(() => formValuesOf(state), [state]);
  const form = useForm<MailFormValues>({
    resolver: zodResolver(schema),
    values: savedValues,
    resetOptions: { keepDirtyValues: true },
  });
  const { isValid } = form.formState;
  const watched = useWatch({ control: form.control });
  const isDirty = MAIL_FIELDS.some((field) => watched[field] !== savedValues[field]);
  const check = useCheckOutcome<MailTestResult>(JSON.stringify(watched));
  const testFailure = testFeedback(check.outcome);

  const recordSaveFailure = (error: unknown) => {
    const failure = classifyMailSaveFailure(error);
    if (failure.kind === 'refused') {
      setFeedback({ kind: 'failure', titleKey: 'settings.mail.saveRefused', reason: failure.reason, detail: failure.detail });
      return;
    }
    setFeedback({ kind: 'error', messageKey: failure.kind === 'managed-by-config' ? 'settings.mail.managedNow' : 'settings.mail.saveFailed' });
  };

  const save = async (submitted: MailFormValues): Promise<boolean> => {
    setFeedback(null);
    try {
      const tested = JSON.stringify(form.getValues());
      await saveMail.mutateAsync(credentialsOf(submitted));
      form.resetField('password');
      check.carry(tested, JSON.stringify(form.getValues()));
      return true;
    } catch (error) {
      recordSaveFailure(error);
      return false;
    }
  };

  useContinueReady(isDirty ? isValid : state.source !== 'none');

  const submit = async () => new Promise<boolean>((resolve) => {
    void form.handleSubmit(async (submitted) => { resolve(await save(submitted)); }, () => { resolve(false); })();
  });

  useBeforeContinue(async () => {
    if (managed || !isDirty) return Promise.resolve(true);
    return submit();
  });

  useSettingsSaveSource({
    isDirty: !managed && isDirty,
    isValid,
    save: submit,
    reset: () => {
      setFeedback(null);
      form.reset(savedValues, { keepDirtyValues: false });
    },
  });

  const runTest = async (credentials: MailCredentials | null) => {
    setFeedback(null);
    await check.run(async () => sendTest.mutateAsync(credentials));
  };

  const testMail = () => {
    if (managed) {
      void runTest(null);
      return;
    }
    void form.handleSubmit(async (submitted) => runTest(credentialsOf(submitted)))();
  };

  const remove = async () => {
    const confirmed = await confirmation.confirm({
      title: t('settings.mail.removeTitle'),
      description: t('settings.mail.removeDesc'),
      action: t('settings.mail.remove'),
      destructive: true,
    });
    if (!confirmed) return;
    setFeedback(null);
    try {
      await removeMail.mutateAsync();
    } catch (error) {
      recordSaveFailure(error);
    }
  };

  return (
    <Form {...form}>
      <PageForm className="space-y-5">
        {managed && <Notice variant="info">{t('settings.mail.statusConfig')}</Notice>}
        <fieldset disabled={managed || busy} className="grid grid-cols-1 gap-4 md:grid-cols-2">
          <FormField
            control={form.control}
            name="host"
            render={({ field }) => (
              <FormItem>
                <FormLabel>{t('settings.mail.host')}</FormLabel>
                <FormControl>
                  <Input {...field} autoComplete="off" spellCheck={false} placeholder={t('settings.mail.hostPlaceholder')} />
                </FormControl>
                <FormMessage />
              </FormItem>
            )}
          />
          <FormField
            control={form.control}
            name="port"
            render={({ field }) => (
              <FormItem>
                <FormLabel>{t('settings.mail.port')}</FormLabel>
                <FormControl>
                  <Input {...field} inputMode="numeric" autoComplete="off" maxLength={5} />
                </FormControl>
                <FormMessage />
              </FormItem>
            )}
          />
          <FormField
            control={form.control}
            name="username"
            render={({ field }) => (
              <FormItem>
                <FormLabel>{t('settings.mail.username')}</FormLabel>
                <FormControl>
                  <Input {...field} autoComplete="off" spellCheck={false} />
                </FormControl>
                <FormMessage />
              </FormItem>
            )}
          />
          <FormField
            control={form.control}
            name="password"
            render={({ field }) => (
              <FormItem>
                <FormLabel>{t('settings.mail.password')}</FormLabel>
                <FormControl>
                  <Input
                    {...field}
                    type="password"
                    autoComplete="new-password"
                    placeholder={state.passwordSet ? t('settings.mail.passwordKeepPlaceholder') : undefined}
                  />
                </FormControl>
                <FormMessage />
              </FormItem>
            )}
          />
          <FormField
            control={form.control}
            name="from"
            render={({ field }) => (
              <FormItem>
                <FormLabel>{t('settings.mail.from')}</FormLabel>
                <FormControl>
                  <Input {...field} type="email" autoComplete="off" placeholder={t('settings.mail.fromPlaceholder')} />
                </FormControl>
                <FormMessage />
              </FormItem>
            )}
          />
          <FormField
            control={form.control}
            name="starttls"
            render={({ field }) => (
              <FormItem>
                <div className="flex items-center gap-3 pt-6">
                  <FormControl>
                    <Switch checked={field.value} onCheckedChange={field.onChange} onBlur={field.onBlur} ref={field.ref} />
                  </FormControl>
                  <FormLabel>{t('settings.mail.starttls')}</FormLabel>
                </div>
              </FormItem>
            )}
          />
        </fieldset>

        {feedback && <MailFeedbackNotice feedback={feedback} />}
        {testFailure && <MailFeedbackNotice feedback={testFailure} />}

        <div className="flex flex-wrap items-center gap-2">
          <CheckButton
            label={t('settings.mail.sendTest')}
            pending={sendTest.isPending}
            disabled={busy}
            passed={checkPassed(check.outcome, (result) => result.outcome === 'delivered')}
            onClick={testMail}
          />
          {state.source === 'panel' && (
            <Button type="button" variant="ghost" disabled={busy} onClick={() => { void remove(); }}>
              {t('settings.mail.remove')}
            </Button>
          )}
        </div>
        {confirmation.dialog}
      </PageForm>
    </Form>
  );
}

const MailSettingsCard = () => {
  const { t } = useTranslation();
  const { isSuperAdmin } = usePermissions();
  const { data: state, isLoading, isError, refetch } = useMailSettings({ enabled: isSuperAdmin });

  if (!isSuperAdmin) {
    return null;
  }

  const renderBody = () => {
    if (isLoading) {
      return (
        <div className="flex justify-center py-6">
          <LoadingSpinner className="h-6 w-6 text-muted-foreground" />
        </div>
      );
    }
    if (isError || !state) {
      return (
        <div className="space-y-3">
          <Notice variant="error" announce>{t('settings.mail.loadFailed')}</Notice>
          <Button type="button" variant="outline" size="sm" onClick={() => { void refetch(); }}>
            {t('common.retry')}
          </Button>
        </div>
      );
    }
    return <MailSettingsForm state={state} />;
  };

  return (
    <div className="max-w-3xl">{renderBody()}</div>
  );
};

export default MailSettingsCard;
