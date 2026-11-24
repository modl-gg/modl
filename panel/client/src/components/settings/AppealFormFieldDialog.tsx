import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { ChevronDown, ChevronRight, Plus, X } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Switch } from '@/components/ui/switch';
import { APPEAL_FORM_FIELD_TYPES } from '@/hooks/data/punishment-types';
import type { AppealFormField, AppealFormSection } from '@/types/forms';
import { newSettingsEntryId } from '@/lib/settings-ids';

type AppealFieldType = AppealFormField['type'];

const NO_NAVIGATION = '__none__';

export const APPEAL_FIELD_TYPE_LABEL_KEYS: Record<AppealFieldType, string> = {
  text: 'settings.tickets.fieldTypeText',
  textarea: 'settings.tickets.fieldTypeTextarea',
  dropdown: 'settings.tickets.fieldTypeDropdown',
  multiple_choice: 'settings.tickets.fieldTypeMultipleChoice',
  checkbox: 'settings.tickets.fieldTypeCheckbox',
  checkboxes: 'settings.tickets.fieldTypeCheckboxes',
  file_upload: 'settings.tickets.fieldTypeFileUpload',
};

function isAppealFieldType(value: string): value is AppealFieldType {
  return APPEAL_FORM_FIELD_TYPES.some((type) => type === value);
}

function hasChoices(type: AppealFieldType): boolean {
  return type === 'dropdown' || type === 'multiple_choice';
}

function navigationTargets(mapping: Record<string, string>): Record<string, string> | undefined {
  const entries = Object.entries(mapping).filter(([, sectionId]) => sectionId !== '' && sectionId !== NO_NAVIGATION);
  return entries.length > 0 ? Object.fromEntries(entries) : undefined;
}

interface AppealFormFieldDialogProps {
  field: AppealFormField | null;
  initialSectionId: string;
  sections: AppealFormSection[];
  onSubmit: (field: AppealFormField) => void;
  onClose: () => void;
}

export default function AppealFormFieldDialog({ field, initialSectionId, sections, onSubmit, onClose }: AppealFormFieldDialogProps) {
  const { t } = useTranslation();
  const [label, setLabel] = useState(field?.label ?? '');
  const [type, setType] = useState<AppealFieldType>(field?.type ?? 'text');
  const [description, setDescription] = useState(field?.description ?? '');
  const [required, setRequired] = useState(field?.required ?? false);
  const [options, setOptions] = useState<string[]>(field?.options ?? []);
  const [sectionId, setSectionId] = useState(field?.sectionId ?? initialSectionId);
  const [optionNavigation, setOptionNavigation] = useState<Record<string, string>>(field?.optionSectionMapping ?? {});
  const [newOption, setNewOption] = useState('');
  const [isNavigationExpanded, setIsNavigationExpanded] = useState(false);

  const choiceField = hasChoices(type);
  const navigableSections = sections.filter((section) => sectionId === '' || section.id !== sectionId);

  const addOption = () => {
    const option = newOption.trim();
    if (option === '') return;
    setOptions((current) => (current.includes(option) ? current : [...current, option]));
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
      options: choiceField ? options : undefined,
      order: field?.order ?? 0,
      sectionId: sectionId || undefined,
      optionSectionMapping: navigationTargets(optionNavigation),
    });
  };

  return (
    <Dialog open onOpenChange={(open) => {
      if (!open) onClose();
    }}>
      <DialogContent className="max-w-lg max-h-[90vh] overflow-y-auto">
        <DialogHeader>
          <DialogTitle>{field ? t('settings.appealFormEditor.editFieldTitle') : t('settings.appealFormEditor.addFieldTitle')}</DialogTitle>
          <DialogDescription className="sr-only">{t('settings.appealFormEditor.fieldDialogDesc')}</DialogDescription>
        </DialogHeader>

        <div className="space-y-4">
          <div className="space-y-2">
            <Label htmlFor="appeal-field-label">{t('settings.tickets.fieldLabel')}</Label>
            <Input
              id="appeal-field-label"
              placeholder={t('settings.appealFormEditor.fieldLabelPlaceholder')}
              value={label}
              onChange={(event) => {
                setLabel(event.target.value);
              }}
            />
          </div>

          <div className="space-y-2">
            <Label htmlFor="appeal-field-type">{t('settings.tickets.fieldType')}</Label>
            <Select
              value={type}
              onValueChange={(value) => {
                if (isAppealFieldType(value)) setType(value);
              }}
            >
              <SelectTrigger id="appeal-field-type">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {APPEAL_FORM_FIELD_TYPES.map((fieldType) => (
                  <SelectItem key={fieldType} value={fieldType}>{t(APPEAL_FIELD_TYPE_LABEL_KEYS[fieldType])}</SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>

          <div className="space-y-2">
            <Label htmlFor="appeal-field-description">{t('settings.tickets.descriptionOptional')}</Label>
            <Input
              id="appeal-field-description"
              placeholder={t('settings.appealFormEditor.fieldDescriptionPlaceholder')}
              value={description}
              onChange={(event) => {
                setDescription(event.target.value);
              }}
            />
          </div>

          <div className="flex items-center space-x-2">
            <Switch id="appeal-field-required" checked={required} onCheckedChange={setRequired} />
            <Label htmlFor="appeal-field-required">{t('settings.tickets.required')}</Label>
          </div>

          <div className="space-y-2">
            <Label htmlFor="appeal-field-section">{t('settings.appealFormEditor.section')}</Label>
            <Select value={sectionId} onValueChange={setSectionId}>
              <SelectTrigger id="appeal-field-section">
                <SelectValue placeholder={t('settings.appealFormEditor.selectSection')} />
              </SelectTrigger>
              <SelectContent>
                {sections.map((section) => (
                  <SelectItem key={section.id} value={section.id}>{section.title}</SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>

          {choiceField && (
            <div className="space-y-2">
              <Label>{type === 'dropdown' ? t('settings.appealFormEditor.dropdownOptions') : t('settings.appealFormEditor.multipleChoiceOptions')}</Label>
              <div className="space-y-2">
                {options.map((option, index) => (
                  <div key={option} className="flex items-center gap-2">
                    <Input value={option} readOnly className="flex-1" />
                    <Button
                      variant="ghost"
                      size="icon"
                      onClick={() => {
                        setOptions((current) => current.filter((_, optionIndex) => optionIndex !== index));
                      }}
                      className="h-9 w-9 text-muted-foreground hover:text-destructive-text"
                    >
                      <X className="h-4 w-4" />
                    </Button>
                  </div>
                ))}
                <div className="flex gap-2">
                  <Input
                    placeholder={t('settings.tickets.addOptionPlaceholder')}
                    value={newOption}
                    onChange={(event) => {
                      setNewOption(event.target.value);
                    }}
                    onKeyDown={(event) => {
                      if (event.key === 'Enter') {
                        event.preventDefault();
                        addOption();
                      }
                    }}
                  />
                  <Button type="button" onClick={addOption} disabled={newOption.trim() === ''}>
                    <Plus className="h-4 w-4" />
                  </Button>
                </div>
              </div>
            </div>
          )}

          {choiceField && options.length > 0 && (
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
                  <p className="text-xs text-muted-foreground">{t('settings.appealFormEditor.optionNavigationDesc')}</p>
                  {options.map((option) => (
                    <div key={option} className="flex items-center gap-3">
                      <div className="flex-1">
                        <Label className="text-sm font-medium">{option}</Label>
                      </div>
                      <div className="flex-1">
                        <Select
                          value={optionNavigation[option] ?? ''}
                          onValueChange={(target) => {
                            setOptionNavigation((current) => ({ ...current, [option]: target }));
                          }}
                        >
                          <SelectTrigger>
                            <SelectValue placeholder={t('settings.appealFormEditor.noNavigation')} />
                          </SelectTrigger>
                          <SelectContent>
                            <SelectItem value={NO_NAVIGATION}>{t('settings.appealFormEditor.noNavigation')}</SelectItem>
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
            {field ? t('settings.appealFormEditor.updateField') : t('settings.tickets.addField')}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
