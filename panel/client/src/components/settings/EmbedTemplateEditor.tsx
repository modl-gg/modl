import React, { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Trash2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import { Switch } from '@/components/ui/switch';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import type { WebhookEmbedFieldConfig, WebhookEmbedTemplateConfig } from '@/hooks/data/settings';
import { createLocalId } from '@/lib/local-id';

export type EmbedTemplateType = 'newTickets' | 'newPunishments' | 'auditLogs';

export interface EditableEmbedField extends WebhookEmbedFieldConfig {
  key: string;
}

export interface EditableEmbedTemplate extends Omit<WebhookEmbedTemplateConfig, 'fields'> {
  fields: EditableEmbedField[];
}

export const toEditableEmbedTemplate = (template: WebhookEmbedTemplateConfig): EditableEmbedTemplate => ({
  ...template,
  fields: template.fields.map((field) => ({ ...field, key: createLocalId('embed-field') })),
});

export const toEmbedTemplateConfig = ({ fields, ...template }: EditableEmbedTemplate): WebhookEmbedTemplateConfig => ({
  ...template,
  fields: fields.map(({ name, value, inline }) => ({ name, value, inline })),
});

const TEMPLATE_VARIABLES: Record<EmbedTemplateType, readonly string[]> = {
  newTickets: ['id', 'type', 'title', 'priority', 'category', 'submittedBy', 'ticketUrl', 'panelUrl'],
  newPunishments: ['id', 'playerName', 'playerUuid', 'type', 'severity', 'reason', 'duration', 'issuer', 'ticketId', 'playerUrl', 'panelUrl'],
  auditLogs: ['user', 'action', 'target', 'details', 'auditUrl', 'panelUrl'],
};

const TEMPLATE_EXAMPLES: Record<EmbedTemplateType, Readonly<Record<string, string>>> = {
  newTickets: {
    id: 'SUPPORT-123456',
    type: 'support',
    title: 'Login Issues',
    priority: 'High',
    category: 'Technical',
    submittedBy: 'PlayerName',
    ticketUrl: 'https://panel.example.com/panel/tickets/SUPPORT-123456',
    panelUrl: 'https://panel.example.com'
  },
  newPunishments: {
    id: 'PUN-789012',
    playerName: 'Griefer123',
    type: 'Temporary Ban',
    severity: 'High',
    reason: 'Griefing other players',
    duration: '7 days',
    issuer: 'ModeratorName',
    ticketId: 'REPORT-456789',
    playerUuid: '069a79f4-44e9-4726-a5be-fca90e38aaf5',
    playerUrl: 'https://panel.example.com/panel/player/069a79f4-44e9-4726-a5be-fca90e38aaf5',
    panelUrl: 'https://panel.example.com'
  },
  auditLogs: {
    user: 'AdminName',
    action: 'Player Ban',
    target: 'PlayerName',
    details: 'Banned for 7 days',
    auditUrl: 'https://panel.example.com/panel/audit',
    panelUrl: 'https://panel.example.com'
  },
};

const PRESET_COLORS = [
  '#3498db',
  '#e74c3c',
  '#f39c12',
  '#2ecc71',
  '#9b59b6',
  '#1abc9c',
  '#34495e',
  '#e67e22',
  '#e91e63',
  '#607d8b',
];

const DEFAULT_EMBED_COLOR = '#3498db';

const DESCRIPTION_PLACEHOLDER_VARIABLE = '{{type}}';

const FIELD_VALUE_PLACEHOLDER = '#{{id}}';

const HEX_COLOR_PATTERN = /^#?([a-f\d]{2})([a-f\d]{2})([a-f\d]{2})$/i;

const TEMPLATE_VARIABLE_PATTERN = /\{\{(\w+)\}\}/g;

function embedBorderColor(hex: string): string {
  const result = HEX_COLOR_PATTERN.exec(hex);
  if (!result) {
    return DEFAULT_EMBED_COLOR;
  }
  const [, red, green, blue] = result;
  if (red === undefined || green === undefined || blue === undefined) {
    return DEFAULT_EMBED_COLOR;
  }
  return `rgb(${String(parseInt(red, 16))}, ${String(parseInt(green, 16))}, ${String(parseInt(blue, 16))})`;
}

function replaceVariablesForPreview(text: string, examples: Readonly<Record<string, string>>): string {
  return text.replace(TEMPLATE_VARIABLE_PATTERN, (match: string, key: string) => examples[key] ?? match);
}

function groupPreviewRows(fields: readonly EditableEmbedField[]): EditableEmbedField[][] {
  const rows: EditableEmbedField[][] = [];
  let index = 0;
  while (index < fields.length) {
    const inlineRun: EditableEmbedField[] = [];
    while (inlineRun.length < 3) {
      const field = fields[index + inlineRun.length];
      if (!field?.inline) break;
      inlineRun.push(field);
    }
    if (inlineRun.length >= 2) {
      rows.push(inlineRun);
      index += inlineRun.length;
    } else {
      const field = fields[index];
      if (field) rows.push([field]);
      index += 1;
    }
  }
  return rows;
}

interface VariableHelperProps {
  templateType: EmbedTemplateType;
  disabled: boolean;
  onInsert: (variable: string) => void;
}

const VariableHelper = ({ templateType, disabled, onInsert }: VariableHelperProps) => {
  const { t } = useTranslation();
  const examples = TEMPLATE_EXAMPLES[templateType];

  return (
    <Popover>
      <PopoverTrigger asChild>
        <Button variant="ghost" size="sm" className="h-7 px-2 text-xs" disabled={disabled}>
          {t('settings.webhook.variables')}
        </Button>
      </PopoverTrigger>
      <PopoverContent className="w-80">
        <div className="space-y-2">
          <div className="grid gap-1">
            {TEMPLATE_VARIABLES[templateType].map(variable => (
              <Button
                key={variable}
                variant="ghost"
                size="sm"
                className="justify-start h-auto p-2"
                onClick={() => onInsert(`{{${variable}}}`)}
                disabled={disabled}
              >
                <div className="flex flex-col items-start gap-0.5">
                  <code className="text-xs font-mono">{`{{${variable}}}`}</code>
                  <span className="text-xs text-muted-foreground">
                    {t('settings.webhook.example', { value: examples[variable] })}
                  </span>
                </div>
              </Button>
            ))}
          </div>
        </div>
      </PopoverContent>
    </Popover>
  );
};

interface ColorPickerProps {
  value: string;
  disabled: boolean;
  onChange: (color: string) => void;
  colorLabel: (color: string) => string;
}

const ColorPicker = ({ value, disabled, onChange, colorLabel }: ColorPickerProps) => (
  <div className="flex items-center gap-2">
    <input
      type="color"
      value={value}
      onChange={(e) => onChange(e.target.value)}
      disabled={disabled}
      className="w-8 h-8 rounded border border-border cursor-pointer disabled:cursor-not-allowed"
      aria-label={colorLabel(value)}
    />
    <Input
      value={value}
      onChange={(e) => onChange(e.target.value)}
      placeholder={DEFAULT_EMBED_COLOR}
      disabled={disabled}
      className="w-24"
    />
    <div className="flex gap-1">
      {PRESET_COLORS.map(color => (
        <button
          key={color}
          type="button"
          className="w-6 h-6 rounded border border-border cursor-pointer hover:scale-110 transition-transform disabled:cursor-not-allowed"
          style={{ backgroundColor: color }}
          onClick={() => onChange(color)}
          disabled={disabled}
          aria-label={colorLabel(color)}
        />
      ))}
    </div>
  </div>
);

interface PreviewFieldProps {
  field: EditableEmbedField;
  examples: Readonly<Record<string, string>>;
  className: string;
}

const PreviewField = ({ field, examples, className }: PreviewFieldProps) => (
  <div className={className}>
    <div className="text-white font-medium text-xs mb-1">
      {replaceVariablesForPreview(field.name, examples)}
    </div>
    <div className="text-[#dcddde] text-xs">
      {replaceVariablesForPreview(field.value, examples)}
    </div>
  </div>
);

interface EmbedPreviewProps {
  template: EditableEmbedTemplate;
  templateType: EmbedTemplateType;
}

const EmbedPreview = ({ template, templateType }: EmbedPreviewProps) => {
  const examples = TEMPLATE_EXAMPLES[templateType];
  const rows = groupPreviewRows(template.fields);

  return (
    <div className="bg-[#2f3136] p-4 rounded-lg w-full max-w-md mx-auto">
      <div className="bg-[#36393f] rounded-lg p-4 border-l-4 w-full max-w-[432px]" style={{ borderLeftColor: embedBorderColor(template.color) }}>
        {template.title && (
          <div className="text-white font-semibold text-sm mb-1">
            {replaceVariablesForPreview(template.title, examples)}
          </div>
        )}
        {template.description && (
          <div className="text-[#dcddde] text-sm mb-3">
            {replaceVariablesForPreview(template.description, examples)}
          </div>
        )}
        {rows.length > 0 && (
          <div className="space-y-2">
            {rows.map((row) => {
              const [first] = row;
              if (!first) return null;
              if (row.length === 1) {
                return (
                  <PreviewField key={first.key} field={first} examples={examples} className={first.inline ? "flex-1" : "block"} />
                );
              }
              return (
                <div key={first.key} className={row.length === 3 ? "flex gap-2" : "flex gap-4"}>
                  {row.map((field) => (
                    <PreviewField key={field.key} field={field} examples={examples} className="flex-1" />
                  ))}
                </div>
              );
            })}
          </div>
        )}
      </div>
    </div>
  );
};

interface EmbedTemplateEditorProps {
  template: EditableEmbedTemplate;
  templateType: EmbedTemplateType;
  onChange: (template: EditableEmbedTemplate) => void;
  disabled?: boolean;
}

const EmbedTemplateEditor: React.FC<EmbedTemplateEditorProps> = ({
  template,
  templateType,
  onChange,
  disabled = false
}) => {
  const { t } = useTranslation();
  const [activeTab, setActiveTab] = useState('basic');

  const updateTemplate = (updates: Partial<EditableEmbedTemplate>) => {
    onChange({ ...template, ...updates });
  };

  const addField = () => {
    const newField: EditableEmbedField = { key: createLocalId('embed-field'), name: '', value: '', inline: true };
    updateTemplate({ fields: [...template.fields, newField] });
  };

  const updateField = (fieldKey: string, updates: Partial<WebhookEmbedFieldConfig>) => {
    updateTemplate({ fields: template.fields.map((field) => (field.key === fieldKey ? { ...field, ...updates } : field)) });
  };

  const removeField = (fieldKey: string) => {
    updateTemplate({ fields: template.fields.filter((field) => field.key !== fieldKey) });
  };

  return (
    <Tabs value={activeTab} onValueChange={setActiveTab}>
      <TabsList>
        <TabsTrigger value="basic">{t('settings.webhook.basicSettings')}</TabsTrigger>
        <TabsTrigger value="fields">{t('settings.webhook.fields')}</TabsTrigger>
        <TabsTrigger value="preview">{t('settings.webhook.preview')}</TabsTrigger>
      </TabsList>

      <TabsContent value="basic" className="space-y-4 pt-2">
        <div className="space-y-2">
          <div className="flex items-center justify-between gap-2">
            <Label htmlFor={`title-${templateType}`}>{t('settings.webhook.embedTitle')}</Label>
            <VariableHelper templateType={templateType} disabled={disabled} onInsert={(variable) => {
              updateTemplate({ title: template.title + variable });
            }} />
          </div>
          <Input
            id={`title-${templateType}`}
            value={template.title}
            onChange={(e) => updateTemplate({ title: e.target.value })}
            placeholder={t('settings.webhook.embedTitlePlaceholder')}
            disabled={disabled}
          />
        </div>

        <div className="space-y-2">
          <div className="flex items-center justify-between gap-2">
            <Label htmlFor={`description-${templateType}`}>{t('settings.webhook.embedDescription')}</Label>
            <VariableHelper templateType={templateType} disabled={disabled} onInsert={(variable) => {
              updateTemplate({ description: template.description + variable });
            }} />
          </div>
          <Textarea
            id={`description-${templateType}`}
            value={template.description}
            onChange={(e) => updateTemplate({ description: e.target.value })}
            placeholder={t('settings.webhook.embedDescriptionPlaceholder', { variable: DESCRIPTION_PLACEHOLDER_VARIABLE })}
            rows={3}
            disabled={disabled}
          />
        </div>

        <div className="space-y-2">
          <Label>{t('settings.webhook.embedColor')}</Label>
          <ColorPicker
            value={template.color}
            disabled={disabled}
            onChange={(color) => updateTemplate({ color })}
            colorLabel={(color) => t('settings.webhook.useColor', { color })}
          />
        </div>
      </TabsContent>

      <TabsContent value="fields" className="space-y-3 pt-2">
        {template.fields.length === 0 && (
          <p className="py-8 text-center text-sm text-muted-foreground">{t('settings.webhook.noFieldsConfigured')}</p>
        )}
        {template.fields.map((field, index) => (
          <div key={field.key} className="space-y-3 rounded-md border p-3">
            <div className="flex items-center justify-between gap-2">
              <span className="text-sm font-medium">{t('settings.webhook.fieldN', { n: index + 1 })}</span>
              <div className="flex items-center gap-3">
                <div className="flex items-center gap-2">
                  <Switch
                    id={`inline-${field.key}`}
                    checked={field.inline}
                    onCheckedChange={(checked) => updateField(field.key, { inline: checked })}
                    disabled={disabled}
                  />
                  <Label htmlFor={`inline-${field.key}`}>{t('settings.webhook.inline')}</Label>
                </div>
                <Button
                  variant="ghost"
                  size="icon"
                  onClick={() => removeField(field.key)}
                  disabled={disabled}
                  className="h-8 w-8 text-muted-foreground hover:text-destructive-text"
                  aria-label={t('settings.webhook.removeField')}
                  title={t('settings.webhook.removeField')}
                >
                  <Trash2 className="h-4 w-4" />
                </Button>
              </div>
            </div>
            <div className="grid gap-3 sm:grid-cols-2">
              <div className="space-y-2">
                <div className="flex items-center justify-between gap-2">
                  <Label htmlFor={`name-${field.key}`}>{t('settings.webhook.fieldName')}</Label>
                  <VariableHelper templateType={templateType} disabled={disabled} onInsert={(variable) => {
                    updateField(field.key, { name: field.name + variable });
                  }} />
                </div>
                <Input
                  id={`name-${field.key}`}
                  value={field.name}
                  onChange={(e) => updateField(field.key, { name: e.target.value })}
                  placeholder={t('settings.webhook.fieldNamePlaceholder')}
                  disabled={disabled}
                />
              </div>
              <div className="space-y-2">
                <div className="flex items-center justify-between gap-2">
                  <Label htmlFor={`value-${field.key}`}>{t('settings.webhook.fieldValue')}</Label>
                  <VariableHelper templateType={templateType} disabled={disabled} onInsert={(variable) => {
                    updateField(field.key, { value: field.value + variable });
                  }} />
                </div>
                <Input
                  id={`value-${field.key}`}
                  value={field.value}
                  onChange={(e) => updateField(field.key, { value: e.target.value })}
                  placeholder={FIELD_VALUE_PLACEHOLDER}
                  disabled={disabled}
                />
              </div>
            </div>
          </div>
        ))}
        <Button variant="outline" size="sm" onClick={addField} disabled={disabled}>
          {t('settings.webhook.addField')}
        </Button>
      </TabsContent>

      <TabsContent value="preview" className="pt-2">
        <EmbedPreview template={template} templateType={templateType} />
      </TabsContent>
    </Tabs>
  );
};

export default EmbedTemplateEditor;
