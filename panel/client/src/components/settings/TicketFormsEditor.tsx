import { useState } from 'react';
import { Plus } from 'lucide-react';
import { IconAction } from '@/components/ui/icon-action';
import { useFlipList } from '@/hooks/use-flip';
import { useTranslation } from 'react-i18next';
import { useConfirmation } from '@/hooks/use-confirmation';
import { Label } from '@/components/ui/label';
import { Switch } from '@/components/ui/switch';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { TICKET_FORM_TYPES, type FormSection, type TicketFormField, type TicketFormSettings, type TicketFormsConfiguration, type TicketFormType } from '@/types/forms';
import FormSectionDialog from './FormSectionDialog';
import { SettingsDndProvider } from './save/SettingsDndProvider';
import { useSettingsPageCanModify } from './layout/SettingsPageFrame';
import { FormSectionCard, formDeletionRequest, type FieldCardActions, type FormDeletion } from './FormLayoutCards';
import TicketFormFieldDialog, { TICKET_FIELD_TYPE_LABEL_KEYS } from './TicketFormFieldDialog';
import {
  moveFieldInSection,
  moveFieldToSection,
  moveSection,
  removeField,
  removeSection,
  sectionFields,
  sortedSections,
  upsertField,
  upsertSection,
} from './form-layout';

const TICKET_DRAG_TYPES = { section: 'ticket-section', field: 'ticket-field' };

const TICKET_FORM_LABEL_KEYS: Record<TicketFormType, string> = {
  bug: 'settings.tickets.bugReport',
  support: 'settings.tickets.supportRequest',
  application: 'settings.tickets.staffApplication',
};

interface SwitchRowProps {
  id: string;
  label: string;
  checked: boolean;
  disabled?: boolean;
  onChange: (checked: boolean) => void;
}

function SwitchRow({ id, label, checked, disabled = false, onChange }: SwitchRowProps) {
  return (
    <div className="flex items-center justify-between gap-4">
      <Label htmlFor={id} className={disabled ? 'text-muted-foreground' : undefined}>{label}</Label>
      <Switch id={id} checked={checked} disabled={disabled} onCheckedChange={onChange} />
    </div>
  );
}

function isTicketFormType(value: string): value is TicketFormType {
  return TICKET_FORM_TYPES.some((type) => type === value);
}

interface FieldEditorState {
  field: TicketFormField | null;
  sectionId: string;
}

interface SectionEditorState {
  section: FormSection | null;
}

interface TicketFormsEditorProps {
  ticketForms: TicketFormsConfiguration;
  onTicketFormsChange: (update: (forms: TicketFormsConfiguration) => TicketFormsConfiguration) => void;
}

export default function TicketFormsEditor({ ticketForms, onTicketFormsChange }: TicketFormsEditorProps) {
  const { t } = useTranslation();
  const canModify = useSettingsPageCanModify();
  const [formType, setFormType] = useState<TicketFormType>('bug');
  const [fieldEditor, setFieldEditor] = useState<FieldEditorState | null>(null);
  const [sectionEditor, setSectionEditor] = useState<SectionEditorState | null>(null);
  const confirmation = useConfirmation();
  const form = ticketForms[formType];
  const sections = sortedSections(form);
  const sectionList = useFlipList<HTMLDivElement>(sections.map((section) => section.id));

  const updateForm = (update: (current: TicketFormSettings) => TicketFormSettings) => {
    onTicketFormsChange((forms) => ({ ...forms, [formType]: update(forms[formType]) }));
  };

  const fieldActions: FieldCardActions<TicketFormField> = {
    dragTypes: TICKET_DRAG_TYPES,
    fieldTypeLabel: (field) => t(TICKET_FIELD_TYPE_LABEL_KEYS[field.type]),
    moveField: (dragIndex, hoverIndex, sectionId) => {
      updateForm((current) => moveFieldInSection(current, dragIndex, hoverIndex, sectionId));
    },
    moveFieldToSection: (fieldId, toSectionId, targetIndex) => {
      updateForm((current) => moveFieldToSection(current, fieldId, toSectionId, targetIndex));
    },
    onEditField: (field) => {
      setFieldEditor({ field, sectionId: field.sectionId ?? '' });
    },
    onDeleteField: (field) => {
      void confirmDeletion({ kind: 'field', id: field.id, name: field.label });
    },
  };

  const confirmDeletion = async (deletion: FormDeletion) => {
    if (!(await confirmation.confirm(formDeletionRequest(t, deletion)))) return;
    updateForm((current) => (deletion.kind === 'field' ? removeField(current, deletion.id) : removeSection(current, deletion.id)));
  };

  const createSection = () => {
    setSectionEditor({ section: null });
  };

  return (
    <SettingsDndProvider>
      <Tabs
        value={formType}
        onValueChange={(value) => {
          if (isTicketFormType(value)) setFormType(value);
        }}
        className="w-full"
      >
        <TabsList className="grid w-full grid-cols-3">
          {TICKET_FORM_TYPES.map((type) => (
            <TabsTrigger key={type} value={type} className="min-w-0 truncate">{t(TICKET_FORM_LABEL_KEYS[type])}</TabsTrigger>
          ))}
        </TabsList>

        <TabsContent value={formType} className="mt-6 space-y-8">
          <div className="space-y-4">
            <SwitchRow
              id="ticket-form-require-email"
              label={t('settings.tickets.requireEmailForCreation')}
              checked={form.requireEmail ?? false}
              disabled={!canModify}
              onChange={(checked) => {
                updateForm((current) => ({
                  ...current,
                  requireEmail: checked,
                  requireEmailAuth: checked && (current.requireEmailAuth ?? false),
                }));
              }}
            />
            <SwitchRow
              id="ticket-form-require-email-auth"
              label={t('settings.tickets.requireEmailAuthToAccess')}
              checked={form.requireEmailAuth ?? false}
              disabled={!canModify || form.requireEmail !== true}
              onChange={(checked) => {
                updateForm((current) => ({ ...current, requireEmailAuth: checked }));
              }}
            />
            <SwitchRow
              id="ticket-form-email-notifications"
              label={t('settings.tickets.allowEmailNotifications')}
              checked={form.allowEmailNotifications !== false}
              disabled={!canModify}
              onChange={(checked) => {
                updateForm((current) => ({ ...current, allowEmailNotifications: checked }));
              }}
            />
          </div>

          <section className="space-y-3">
            <div className="flex min-h-9 items-center justify-between gap-2">
              <h3 className="text-sm font-semibold">{t('settings.tickets.formSections')}</h3>
              {canModify && <IconAction icon={Plus} label={t('settings.tickets.addSection')} variant="outline" onClick={createSection} />}
            </div>

            {sections.length === 0 ? (
              <p className="py-8 text-center text-sm text-muted-foreground">{t('settings.tickets.noSectionsYet')}</p>
            ) : (
              <div ref={sectionList.ref} className="space-y-3">
                {sections.map((section, index) => (
                  <FormSectionCard
                    key={section.id}
                    section={section}
                    index={index}
                    count={sections.length}
                    headingLevel={4}
                    isSettling={sectionList.isSettling}
                    fields={sectionFields(form, section.id)}
                    moveSection={(dragIndex, hoverIndex) => {
                      updateForm((current) => moveSection(current, dragIndex, hoverIndex));
                    }}
                    onEditSection={(edited) => {
                      setSectionEditor({ section: edited });
                    }}
                    onDeleteSection={(deleted) => {
                      void confirmDeletion({ kind: 'section', id: deleted.id, name: deleted.title });
                    }}
                    onAddField={(sectionId) => {
                      setFieldEditor({ field: null, sectionId });
                    }}
                    {...fieldActions}
                  />
                ))}
              </div>
            )}
          </section>
        </TabsContent>
      </Tabs>

      {fieldEditor !== null && (
        <TicketFormFieldDialog
          field={fieldEditor.field}
          sectionId={fieldEditor.sectionId}
          sections={sections}
          onSubmit={(field) => {
            updateForm((current) => upsertField(current, field));
            setFieldEditor(null);
          }}
          onClose={() => {
            setFieldEditor(null);
          }}
        />
      )}

      {sectionEditor !== null && (
        <FormSectionDialog
          section={sectionEditor.section}
          onSubmit={(section) => {
            updateForm((current) => upsertSection(current, section));
            setSectionEditor(null);
          }}
          onClose={() => {
            setSectionEditor(null);
          }}
        />
      )}

      {confirmation.dialog}
    </SettingsDndProvider>
  );
}
