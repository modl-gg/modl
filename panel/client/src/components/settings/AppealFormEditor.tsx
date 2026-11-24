import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useConfirmation } from '@/hooks/use-confirmation';
import { Plus } from 'lucide-react';
import { IconAction } from '@/components/ui/icon-action';
import { useFlipList } from '@/hooks/use-flip';
import { cn } from '@/lib/utils';
import type { AppealFormField, AppealFormSection, AppealFormSettings } from '@/types/forms';
import AppealFormFieldDialog, { APPEAL_FIELD_TYPE_LABEL_KEYS } from './AppealFormFieldDialog';
import FormSectionDialog from './FormSectionDialog';
import { useSettingsPageCanModify } from './layout/SettingsPageFrame';
import { SettingsDndProvider } from './save/SettingsDndProvider';
import { FormFieldCard, FormSectionCard, formDeletionRequest, type FieldCardActions, type FormDeletion } from './FormLayoutCards';
import {
  UNSECTIONED,
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

export const EMPTY_APPEAL_FORM: AppealFormSettings = { fields: [], sections: [] };

const APPEAL_DRAG_TYPES = { section: 'appeal-section', field: 'appeal-field' };

interface FieldEditorState {
  field: AppealFormField | null;
  sectionId: string;
}

interface SectionEditorState {
  section: AppealFormSection | null;
}

interface AppealFormEditorProps {
  appealForm: AppealFormSettings | undefined;
  appealable: boolean;
  onChange: (update: (appealForm: AppealFormSettings) => AppealFormSettings) => void;
}

export default function AppealFormEditor({ appealForm = EMPTY_APPEAL_FORM, appealable, onChange }: AppealFormEditorProps) {
  const { t } = useTranslation();
  const canModify = useSettingsPageCanModify();
  const [fieldEditor, setFieldEditor] = useState<FieldEditorState | null>(null);
  const [sectionEditor, setSectionEditor] = useState<SectionEditorState | null>(null);
  const confirmation = useConfirmation();
  const sections = sortedSections(appealForm);
  const unsectionedFields = sectionFields(appealForm, UNSECTIONED);
  const list = useFlipList<HTMLDivElement>([...sections.map((section) => section.id), ...unsectionedFields.map((field) => field.id)]);
  const isEmpty = appealForm.fields.length === 0 && appealForm.sections.length === 0;

  const fieldActions: FieldCardActions<AppealFormField> = {
    dragTypes: APPEAL_DRAG_TYPES,
    fieldTypeLabel: (field) => t(APPEAL_FIELD_TYPE_LABEL_KEYS[field.type]),
    moveField: (dragIndex, hoverIndex, sectionId) => {
      onChange((current) => moveFieldInSection(current, dragIndex, hoverIndex, sectionId));
    },
    moveFieldToSection: (fieldId, toSectionId, targetIndex) => {
      onChange((current) => moveFieldToSection(current, fieldId, toSectionId, targetIndex));
    },
    onEditField: (field) => {
      setFieldEditor({ field, sectionId: field.sectionId ?? UNSECTIONED });
    },
    onDeleteField: (field) => {
      void confirmDeletion({ kind: 'field', id: field.id, name: field.label });
    },
  };

  const confirmDeletion = async (deletion: FormDeletion) => {
    if (!(await confirmation.confirm(formDeletionRequest(t, deletion)))) return;
    onChange((current) => (deletion.kind === 'field' ? removeField(current, deletion.id) : removeSection(current, deletion.id)));
  };

  return (
    <>
      {canModify && (
        <div className="flex justify-end">
          <IconAction
            icon={Plus}
            label={t('settings.page.addSection')}
            variant="outline"
            onClick={() => {
              setSectionEditor({ section: null });
            }}
            disabled={!appealable}
          />
        </div>
      )}

      <div ref={list.ref} inert={!appealable} className={cn('space-y-4', !appealable && 'opacity-50')}>
        <SettingsDndProvider>
          {sections.map((section, index) => (
            <FormSectionCard
              key={section.id}
              section={section}
              index={index}
              count={sections.length}
              headingLevel={3}
              isSettling={list.isSettling}
              fields={sectionFields(appealForm, section.id)}
              moveSection={(dragIndex, hoverIndex) => {
                onChange((current) => moveSection(current, dragIndex, hoverIndex));
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

          {unsectionedFields.map((field, index) => (
            <FormFieldCard key={field.id} field={field} index={index} count={unsectionedFields.length} sectionId={UNSECTIONED} isSettling={list.isSettling} {...fieldActions} />
          ))}

          {isEmpty && (
            <p className="py-8 text-center text-sm text-muted-foreground">
              {appealable ? t('settings.page.defaultAppealFormNote') : t('settings.page.appealsDisabled')}
            </p>
          )}
        </SettingsDndProvider>
      </div>

      {fieldEditor !== null && (
        <AppealFormFieldDialog
          field={fieldEditor.field}
          initialSectionId={fieldEditor.sectionId}
          sections={sections}
          onSubmit={(field) => {
            onChange((current) => upsertField(current, field));
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
            onChange((current) => upsertSection(current, section));
            setSectionEditor(null);
          }}
          onClose={() => {
            setSectionEditor(null);
          }}
        />
      )}

      {confirmation.dialog}
    </>
  );
}
