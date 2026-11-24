import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { PageForm } from '@/components/ui/action-form';
import type { TFunction } from 'i18next';
import { ChevronDown, Eye, EyeOff } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { CheckButton } from '@/components/ui/check-button';
import { Field } from '@/components/ui/field';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Switch } from '@/components/ui/switch';
import { Notice } from '@/components/ui/notice';
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/ui/collapsible';
import { checkPassed, useCheckOutcome } from '@/hooks/use-check-outcome';
import { useConfirmation } from '@/hooks/use-confirmation';
import { PERMISSIONS, usePermissions } from '@/hooks/use-permissions';
import {
  EMPTY_WEBHOOK_EMBED_TEMPLATES,
  useGeneralSettings,
  useSaveWebhookSettings,
  useSettings,
  useTestWebhook,
  webhookSettingsOf,
  type VersionedSettings,
  type WebhookConfiguration,
  type WebhookEmbedTemplatesConfig,
} from '@/hooks/data/settings';
import { formatDate } from '@/utils/date-utils';
import { FIELD_WIDTH, SettingsLoading, SettingsSection, SettingsSections } from './layout/SettingsSections';
import { useSettingsDraft } from './save/use-settings-draft';
import EmbedTemplateEditor, {
  toEditableEmbedTemplate,
  toEmbedTemplateConfig,
  type EditableEmbedTemplate,
  type EmbedTemplateType,
} from './EmbedTemplateEditor';

interface WebhookDraft extends Pick<WebhookConfiguration, 'replacementWebhookUrl' | 'discordAdminRoleId' | 'botName' | 'avatarUrl' | 'enabled' | 'notifications'> {
  embedTemplates: Record<EmbedTemplateType, EditableEmbedTemplate>;
}

const DEFAULT_BOT_NAME = 'modl Panel';

const WEBHOOK_URL_PLACEHOLDER = 'https://discord.com/api/webhooks/...';

const ADMIN_ROLE_ID_PLACEHOLDER = '123456789012345678';

const AVATAR_URL_PLACEHOLDER = 'https://cdn.discordapp.com/avatars/123456789/avatar.png';

const toEditableTemplates = (templates: WebhookEmbedTemplatesConfig): WebhookDraft['embedTemplates'] => ({
  newTickets: toEditableEmbedTemplate(templates.newTickets),
  newPunishments: toEditableEmbedTemplate(templates.newPunishments),
  auditLogs: toEditableEmbedTemplate(templates.auditLogs),
});

const isCustomAvatarUrl = (avatarUrl: string, panelIconUrl: string | undefined): boolean =>
  avatarUrl !== '' && avatarUrl !== panelIconUrl && !avatarUrl.includes('/panel-icon-');

function webhookDraftOf(webhookSettings: WebhookConfiguration | null, panelIconUrl: string | undefined): WebhookDraft {
  if (webhookSettings === null) {
    return {
      replacementWebhookUrl: null,
      discordAdminRoleId: '',
      botName: DEFAULT_BOT_NAME,
      avatarUrl: panelIconUrl ?? '',
      enabled: false,
      notifications: {
        newTickets: true,
        newPunishments: true,
        auditLogs: false,
      },
      embedTemplates: toEditableTemplates(EMPTY_WEBHOOK_EMBED_TEMPLATES),
    };
  }
  return {
    replacementWebhookUrl: webhookSettings.replacementWebhookUrl,
    discordAdminRoleId: webhookSettings.discordAdminRoleId,
    botName: webhookSettings.botName,
    avatarUrl: isCustomAvatarUrl(webhookSettings.avatarUrl, panelIconUrl) ? webhookSettings.avatarUrl : '',
    enabled: webhookSettings.enabled,
    notifications: webhookSettings.notifications,
    embedTemplates: toEditableTemplates(webhookSettings.embedTemplates),
  };
}

function webhookConfigurationOfDraft(draft: WebhookDraft, stored: WebhookConfiguration | null, panelIconUrl: string | undefined): WebhookConfiguration {
  return {
    discordWebhookConfigured: stored?.discordWebhookConfigured ?? false,
    discordWebhookUrlSuffix: stored?.discordWebhookUrlSuffix ?? '',
    ...draft,
    avatarUrl: draft.avatarUrl || panelIconUrl || '',
    embedTemplates: {
      newTickets: toEmbedTemplateConfig(draft.embedTemplates.newTickets),
      newPunishments: toEmbedTemplateConfig(draft.embedTemplates.newPunishments),
      auditLogs: toEmbedTemplateConfig(draft.embedTemplates.auditLogs),
    },
  };
}

function webhookUrlStatus(t: TFunction, replacement: string | null, stored: WebhookConfiguration | null | undefined): string | null {
  if (replacement === '') {
    return t('settings.webhook.webhookUrlRemoved');
  }
  if (replacement !== null) {
    return t('settings.webhook.webhookUrlReplaced');
  }
  return stored?.discordWebhookConfigured === true
    ? t('settings.webhook.webhookUrlConfigured', { suffix: stored.discordWebhookUrlSuffix })
    : null;
}

interface WebhookTest {
  passed: boolean;
  failureBefore: number | null;
}

interface WebhookEvent {
  type: EmbedTemplateType;
  labelKey: string;
}

const WEBHOOK_EVENTS: readonly WebhookEvent[] = [
  { type: 'newTickets', labelKey: 'settings.webhook.notifyNewTickets' },
  { type: 'newPunishments', labelKey: 'settings.webhook.notifyNewPunishments' },
  { type: 'auditLogs', labelKey: 'settings.webhook.notifyAuditLogs' },
];

interface WebhookEventRowProps {
  event: WebhookEvent;
  enabled: boolean;
  template: EditableEmbedTemplate;
  disabled: boolean;
  onEnabledChange: (enabled: boolean) => void;
  onTemplateChange: (template: EditableEmbedTemplate) => void;
}

function WebhookEventRow({ event, enabled, template, disabled, onEnabledChange, onTemplateChange }: WebhookEventRowProps) {
  const { t } = useTranslation();
  const [editing, setEditing] = useState(false);
  const switchId = `webhook-event-${event.type}`;

  return (
    <Collapsible open={editing} onOpenChange={setEditing}>
      <div className="flex items-center justify-between gap-3 px-3 py-2">
        <div className="flex items-center gap-3">
          <Switch id={switchId} checked={enabled} onCheckedChange={onEnabledChange} disabled={disabled} />
          <Label htmlFor={switchId}>{t(event.labelKey)}</Label>
        </div>
        <CollapsibleTrigger asChild>
          <Button type="button" variant="ghost" size="sm" aria-expanded={editing}>
            {t('settings.webhook.editMessage')}
            <ChevronDown className={editing ? 'h-4 w-4 rotate-180 transition-transform' : 'h-4 w-4 transition-transform'} aria-hidden="true" />
          </Button>
        </CollapsibleTrigger>
      </div>
      <CollapsibleContent className="border-t px-3 py-4">
        <EmbedTemplateEditor template={template} templateType={event.type} onChange={onTemplateChange} disabled={disabled} />
      </CollapsibleContent>
    </Collapsible>
  );
}

export default function WebhookSettings() {
  const { hasPermission, canLoadSettingsData } = usePermissions();
  const { t } = useTranslation();
  const testWebhook = useTestWebhook();
  const saveWebhook = useSaveWebhookSettings();
  const { data: snapshot } = useSettings({ enabled: canLoadSettingsData('general') });
  const { data: general } = useGeneralSettings({ enabled: hasPermission(PERMISSIONS.ADMIN_SETTINGS_VIEW) });
  const panelIconUrl = general?.panelIconUrl;
  const webhookSettings = snapshot === undefined ? undefined : webhookSettingsOf(snapshot) ?? null;
  const canModify = hasPermission(PERMISSIONS.ADMIN_SETTINGS_MODIFY);

  const draft = useSettingsDraft({
    snapshot: canModify && general !== undefined ? webhookSettings ?? undefined : undefined,
    fallback: webhookDraftOf(webhookSettings?.value ?? null, panelIconUrl),
    valueOf: (saved: VersionedSettings<WebhookConfiguration>) => webhookDraftOf(saved.value, panelIconUrl),
    save: async (value, base) => {
      const { saved } = await saveWebhook.mutateAsync({ expectedVersion: base.version, value: webhookConfigurationOfDraft(value, base.value, panelIconUrl) });
      return saved;
    },
  });
  const settings = draft.value;
  const stored = webhookSettings?.value;
  const storedUrlConfigured = stored?.discordWebhookConfigured === true;
  const hasWebhookUrl = settings.replacementWebhookUrl === null
    ? storedUrlConfigured
    : settings.replacementWebhookUrl !== '';

  const [showWebhookUrl, setShowWebhookUrl] = useState(false);
  const check = useCheckOutcome<WebhookTest>(String(webhookSettings?.version));
  const confirmation = useConfirmation();

  const handleInputChange = <K extends keyof WebhookDraft>(field: K, value: WebhookDraft[K]) => {
    draft.update((current) => ({ ...current, [field]: value }));
  };

  const handleNotificationChange = (key: keyof WebhookDraft['notifications'], value: boolean) => {
    draft.update((current) => ({ ...current, notifications: { ...current.notifications, [key]: value } }));
  };

  const handleEmbedTemplateChange = (templateType: EmbedTemplateType, template: EditableEmbedTemplate) => {
    draft.update((current) => ({ ...current, embedTemplates: { ...current.embedTemplates, [templateType]: template } }));
  };

  const lastFailure = stored?.lastDeliveryFailure;
  const testPassed = checkPassed(check.outcome, (result) => result.passed);
  const testFailureReported = check.outcome?.kind === 'result'
    && lastFailure?.event === 'test'
    && lastFailure.failedAt !== check.outcome.result.failureBefore;

  const handleTestWebhook = () => {
    const failureBefore = lastFailure?.event === 'test' ? lastFailure.failedAt : null;
    void check.run(async () => {
      try {
        await testWebhook.mutateAsync();
        return { passed: true, failureBefore };
      } catch {
        return { passed: false, failureBefore };
      }
    });
  };

  const confirmRemoveWebhookUrl = async () => {
    const confirmed = await confirmation.confirm({
      title: t('settings.webhook.removeWebhookUrlTitle'),
      description: t('settings.webhook.removeWebhookUrlDesc'),
      action: t('settings.webhook.removeWebhookUrl'),
      destructive: true,
    });
    if (confirmed) handleInputChange('replacementWebhookUrl', '');
  };

  if (snapshot === undefined) return <SettingsLoading />;

  return (
    <SettingsSections>
      <PageForm>
        <SettingsSection>
          <div className="flex flex-wrap items-center justify-between gap-3">
            <Label htmlFor="webhook-enabled" className="text-sm font-semibold">
              {t('settings.webhook.enableWebhook')}
            </Label>
            <div className="flex items-center gap-3">
              {canModify && (
                <CheckButton
                  label={t('settings.webhook.test')}
                  size="sm"
                  pending={testWebhook.isPending}
                  disabled={draft.isDirty || !settings.enabled || !hasWebhookUrl}
                  passed={testPassed}
                  onClick={handleTestWebhook}
                />
              )}
              <Switch
                id="webhook-enabled"
                checked={settings.enabled}
                onCheckedChange={(checked) => handleInputChange('enabled', checked)}
                disabled={!canModify}
              />
            </div>
          </div>

          {testPassed === false && !testFailureReported && <Notice variant="error" announce>{t('settings.webhook.testFailedDesc')}</Notice>}

          {lastFailure !== undefined && (
            <Notice variant="warning">
              {t('settings.webhook.lastFailure', {
                time: formatDate(new Date(lastFailure.failedAt).toISOString()),
                event: t(`settings.webhook.failureEvents.${lastFailure.event}`),
                reason: t(`settings.webhook.failureReasons.${lastFailure.reason}`, { status: lastFailure.httpStatus }),
              })}
            </Notice>
          )}

          <Field label={t('settings.webhook.webhookUrl')} hint={webhookUrlStatus(t, settings.replacementWebhookUrl, stored)}>
            {(control) => (
              <div className={`flex gap-2 ${FIELD_WIDTH.wide}`}>
                <div className="relative flex-1">
                  <Input
                    {...control}
                    type={showWebhookUrl ? 'text' : 'password'}
                    placeholder={WEBHOOK_URL_PLACEHOLDER}
                    value={settings.replacementWebhookUrl ?? ''}
                    onChange={(e) => handleInputChange('replacementWebhookUrl', e.target.value === '' ? null : e.target.value)}
                    disabled={!canModify}
                    className="pr-10"
                  />
                  <Button
                    type="button"
                    variant="ghost"
                    size="sm"
                    className="absolute right-0 top-0 h-full px-3"
                    onClick={() => setShowWebhookUrl(!showWebhookUrl)}
                    disabled={!canModify}
                    aria-label={t(showWebhookUrl ? 'settings.webhook.hideUrl' : 'settings.webhook.showUrl')}
                  >
                    {showWebhookUrl ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
                  </Button>
                </div>
                {canModify && storedUrlConfigured && settings.replacementWebhookUrl === null && (
                  <Button type="button" variant="outline" onClick={() => { void confirmRemoveWebhookUrl(); }}>
                    {t('settings.webhook.removeWebhookUrl')}
                  </Button>
                )}
              </div>
            )}
          </Field>

          <Field label={t('settings.webhook.adminRoleId')}>
            {(control) => (
              <Input
                {...control}
                placeholder={ADMIN_ROLE_ID_PLACEHOLDER}
                value={settings.discordAdminRoleId}
                onChange={(e) => handleInputChange('discordAdminRoleId', e.target.value)}
                disabled={!canModify}
                className={FIELD_WIDTH.short}
              />
            )}
          </Field>

          <Field label={t('settings.webhook.botName')}>
            {(control) => (
              <Input
                {...control}
                placeholder={DEFAULT_BOT_NAME}
                value={settings.botName}
                onChange={(e) => handleInputChange('botName', e.target.value)}
                disabled={!canModify}
                className={FIELD_WIDTH.text}
              />
            )}
          </Field>

          <Field label={t('settings.webhook.avatarUrl')}>
            {(control) => (
              <Input
                {...control}
                placeholder={AVATAR_URL_PLACEHOLDER}
                value={settings.avatarUrl}
                onChange={(e) => handleInputChange('avatarUrl', e.target.value)}
                disabled={!canModify}
                className={FIELD_WIDTH.wide}
              />
            )}
          </Field>
          {confirmation.dialog}
        </SettingsSection>
      </PageForm>

      <SettingsSection title={t('settings.webhook.notificationTypes')}>
        <div className="divide-y rounded-md border">
          {WEBHOOK_EVENTS.map((event) => (
            <WebhookEventRow
              key={event.type}
              event={event}
              enabled={settings.notifications[event.type]}
              template={settings.embedTemplates[event.type]}
              disabled={!canModify}
              onEnabledChange={(checked) => handleNotificationChange(event.type, checked)}
              onTemplateChange={(template) => handleEmbedTemplateChange(event.type, template)}
            />
          ))}
        </div>
      </SettingsSection>
    </SettingsSections>
  );
}