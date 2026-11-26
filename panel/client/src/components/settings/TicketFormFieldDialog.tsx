import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { ChevronDown, ChevronRight, Plus, X } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Switch } from '@/components/ui/switch';
import { createLocalId } from '@/lib/local-id';
import { FORM_DESCRIPTION_MAX_LENGTH, FORM_FIELD_LABEL_MAX_LENGTH, FORM_OPTION_MAX_LENGTH, type FormSection, type TicketFormField } from '@/types/forms';
import { newSettingsEntryId } from '@/lib/settings-ids';

type TicketFieldType = TicketFormField['type'];

const NO_NAVIGATION = '__none__';
const CHECKED_VALUE = 'true';

export const TICKET_FIELD_TYPE_LABEL_KEYS: Record<TicketFieldType, string> = {
  text: 'settings.tickets.fieldTypeText',
  textarea: 'settings.tickets.fieldTypeTextarea',
  dropdown: 'settings.tickets.fieldTypeDropdown',
  multiple_choice: 'settings.tickets.fieldTypeMultipleChoice',
  checkbox: 'settings.tickets.fieldTypeCheckbox',
  checkboxes: 'settings.tickets.fieldTypeCheckboxes',
  file_upload: 'settings.tickets.fieldTypeFileUpload',
  description: 'settings.tickets.fieldTypeDescription',
};

const TICKET_FIELD_TYPES: readonly TicketFieldType[] = [
  'text', 'textarea', 'dropdown', 'multiple_choice', 'checkbox', 'checkboxes', 'file_upload', 'description',
];

const CHOICE_FIELD_TYPES: readonly TicketFieldType[] = ['dropdown', 'multiple_choice', 'checkboxes'];

function isTicketFieldType(value: string): value is TicketFieldType {
  return TICKET_FIELD_TYPES.some((type) => type === value);
}

function hasChoices(type: TicketFieldType): boolean {
  return CHOICE_FIELD_TYPES.includes(type);
}

interface EditableOption {
  id: string;
  value: string;
}

function toEditableOption(value: string): EditableOption {
  return { id: createLocalId('option'), value };
}

interface NavigationChoice {
  value: string;
  label: string;
}

function navigationTargets(mapping: Record<string, string>, choices: readonly NavigationChoice[]): Record<string, string> | undefined {
  const entries = Object.entries(mapping).filter(([choice, sectionId]) =>
    choices.some((candidate) => candidate.value === choice) && sectionId !== '' && sectionId !== NO_NAVIGATION);
  return entries.length > 0 ? Object.fromEntries(entries) : undefined;
}

interface TicketFormFieldDialogProps {
  field: TicketFormField | null;
  sectionId: string;
  sections: FormSection[];
  onSubmit: (field: TicketFormField) => void;
  onClose: () => void;
}

export default function TicketFormFieldDialog({ field, sectionId, sections, onSubmit, onClose }: TicketFormFieldDialogProps) {
  const { t } = useTranslation();
  const [label, setLabel] = useState(field?.label ?? '');
  const [type, setType] = useState<TicketFieldType>(field?.type ?? 'text');
  const [description, setDescription] = useState(field?.description ?? '');
  const [required, setRequired] = useState(field?.required ?? false);
  const [options, setOptions] = useState<EditableOption[]>(() => (field?.options ?? []).map(toEditableOption));
  const [optionNavigation, setOptionNavigation] = useState<Record<string, string>>(field?.optionSectionMapping ?? {});
  const [newOption, setNewOption] = useState('');
  const [isNavigationExpanded, setIsNavigationExpanded] = useState(Object.keys(field?.optionSectionMapping ?? {}).length > 0);

  const choiceField = hasChoices(type);
  const navigationChoices: NavigationChoice[] = type === 'checkbox'
    ? [{ value: CHECKED_VALUE, label: t('settings.tickets.checkedOption') }]
    : options.map((option) => ({ value: option.value, label: option.value }));
  const supportsNavigation = (type === 'checkbox' || choiceField) && navigationChoices.length > 0;
  const navigableSections = sections.filter((section) => section.id !== sectionId);

  const addOption = () => {
    const option = newOption.trim();
    if (option === '') return;
    setOptions((current) => [...current, toEditableOption(option)]);
    setNewOption('');
  };

  const submit = () => {
    const trimmedLabel = label.trim();
    if (trimmedLabel === '') return;
    onSubmit({
      id: field?.id ?? newSettingsEntryId('field'),
      type,
      label: trimmedLabel,
      description: description.trim() || undefined,
      required,
      options: choiceField ? options.map((option) => option.value) : undefined,
      order: field?.order ?? 0,
      sectionId: sectionId || undefined,
      optionSectionMapping: supportsNavigation ? navigationTargets(optionNavigation, navigationChoices) : undefined,
    });
  };

  return (
    <Dialog open onOpenChange={(open) => {
      if (!open) onClose();
    }}>
      <DialogContent className="max-w-lg max-h-[90vh] overflow-y-auto">
        <DialogHeader>
          <DialogTitle>{field ? t('settings.tickets.editFormField') : t('settings.tickets.addFormField')}</DialogTitle>
          <DialogDescription className="sr-only">{t('settings.tickets.fieldDialogDesc')}</DialogDescription>
        </DialogHeader>

        <div className="space-y-4">
          <div className="space-y-2">
            <Label htmlFor="ticket-field-label">{t('settings.tickets.fieldLabel')}</Label>
            <Input
              id="ticket-field-label"
              maxLength={FORM_FIELD_LABEL_MAX_LENGTH}
              value={label}
              onChange={(event) => {
                setLabel(event.target.value);
              }}
              placeholder={t('settings.tickets.fieldLabelPlaceholder')}
            />
          </div>

          <div className="space-y-2">
            <Label htmlFor="ticket-field-type">{t('settings.tickets.fieldType')}</Label>
            <Select
              value={type}
              onValueChange={(value) => {
                if (isTicketFieldType(value)) setType(value);
              }}
            >
              <SelectTrigger id="ticket-field-type">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {TICKET_FIELD_TYPES.map((fieldType) => (
                  <SelectItem key={fieldType} value={fieldType}>{t(TICKET_FIELD_TYPE_LABEL_KEYS[fieldType])}</SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>

          <div className="space-y-2">
            <Label htmlFor="ticket-field-description">{t('settings.tickets.descriptionOptional')}</Label>
            <Input
              id="ticket-field-description"
              maxLength={FORM_DESCRIPTION_MAX_LENGTH}
              value={description}
              onChange={(event) => {
                setDescription(event.target.value);
              }}
              placeholder={t('settings.tickets.fieldDescriptionPlaceholder')}
            />
          </div>

          <div className="flex items-center gap-2">
            <Switch id="ticket-field-required" checked={required} onCheckedChange={setRequired} />
            <Label htmlFor="ticket-field-required">{t('settings.tickets.required')}</Label>
          </div>

          {choiceField && (
            <div className="space-y-2">
              <Label>{t('settings.tickets.options')}</Label>
              <div className="space-y-2">
                {options.map((option) => (
                  <div key={option.id} className="flex gap-2">
                    <Input
                      value={option.value}
                      maxLength={FORM_OPTION_MAX_LENGTH}
                      onChange={(event) => {
                        const value = event.target.value;
                        setOptions((current) => current.map((existing) => (existing.id === option.id ? { ...existing, value } : existing)));
                      }}
                    />
                    <Button
                      variant="ghost"
                      size="sm"
                      onClick={() => {
                        setOptions((current) => current.filter((existing) => existing.id !== option.id));
                      }}
                    >
                      <X className="h-4 w-4" />
                    </Button>
                  </div>
                ))}
                <div className="flex gap-2">
                  <Input
                    value={newOption}
                    maxLength={FORM_OPTION_MAX_LENGTH}
                    onChange={(event) => {
                      setNewOption(event.target.value);
                    }}
                    placeholder={t('settings.tickets.addOptionPlaceholder')}
                    onKeyDown={(event) => {
                      if (event.key === 'Enter') {
                        event.preventDefault();
                        addOption();
                      }
                    }}
                  />
                  <Button size="sm" onClick={addOption} disabled={newOption.trim() === ''}>
                    <Plus className="h-4 w-4" />
                  </Button>
                </div>
              </div>
            </div>
          )}

          {supportsNavigation && (
            <div className="space-y-3">
              <button
                type="button"
                onClick={() => {
                  setIsNavigationExpanded((expanded) => !expanded);
                }}
                className="flex items-center gap-2 hover:bg-muted/50 p-1 rounded -ml-1 transition-colors"
              >
                {isNavigationExpanded ? <ChevronDown className="h-4 w-4" /> : <ChevronRight className="h-4 w-4" />}
                <Label className="text-sm font-medium cursor-pointer">{t('settings.tickets.optionNavigationOptional')}</Label>
              </button>

              {isNavigationExpanded && (
                <div className="pl-6 space-y-3">
                  <p className="text-xs text-muted-foreground">{t('settings.tickets.optionNavigationDesc')}</p>
                  {navigationChoices.map((choice) => (
                    <div key={choice.value} className="flex items-center gap-3">
                      <div className="flex-1">
                        <Label className="text-sm font-medium">{choice.label}</Label>
                      </div>
                      <div className="flex-1">
                        <Select
                          value={optionNavigation[choice.value] || NO_NAVIGATION}
                          onValueChange={(target) => {
                            setOptionNavigation((current) => ({ ...current, [choice.value]: target }));
                          }}
                        >
                          <SelectTrigger>
                            <SelectValue placeholder={t('settings.tickets.noNavigation')} />
                          </SelectTrigger>
                          <SelectContent>
                            <SelectItem value={NO_NAVIGATION}>{t('settings.tickets.noNavigation')}</SelectItem>
                            {navigableSections.map((section) => (
                              <SelectItem key={section.id} value={section.id}>{section.title}</SelectItem>
                            ))}
                          </SelectContent>
                        </Select>
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </div>
          )}
        </div>

        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{t('common.cancel')}</Button>
          <Button onClick={submit} disabled={label.trim() === ''}>
            {field ? t('common.update') : t('common.add')}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
