import type { ReactNode } from 'react';
import type { TFunction } from 'i18next';
import { useTranslation } from 'react-i18next';
import { useDrop } from 'react-dnd';
import { Pencil, Plus, Trash2 } from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import { GroupCard, GroupRow, GroupRows } from '@/components/ui/group-list';
import { IconAction } from '@/components/ui/icon-action';
import type { ConfirmationRequest } from '@/hooks/use-confirmation';
import { useDndConnectorRef } from '@/hooks/use-dnd-connector-ref';
import { useFlipList } from '@/hooks/use-flip';
import { useSortable, type SortableItem } from '@/hooks/use-sortable';
import { cn } from '@/lib/utils';
import type { LayoutField } from './form-layout';
import { useSettingsPageCanModify } from './layout/SettingsPageFrame';

export interface CardField extends LayoutField {
  label: string;
  required: boolean;
}

export interface CardSection {
  id: string;
  title: string;
  description?: string | undefined;
  hideByDefault?: boolean | undefined;
}

export interface FormDragTypes {
  section: string;
  field: string;
}

export interface FieldCardActions<Field extends CardField> {
  dragTypes: FormDragTypes;
  fieldTypeLabel: (field: Field) => ReactNode;
  moveField: (dragIndex: number, hoverIndex: number, sectionId: string) => void;
  moveFieldToSection: (fieldId: string, toSectionId: string, targetIndex?: number) => void;
  onEditField: (field: Field) => void;
  onDeleteField: (field: Field) => void;
}

interface FormFieldCardProps<Field extends CardField> extends FieldCardActions<Field> {
  field: Field;
  index: number;
  count: number;
  sectionId: string;
  sectionTitle?: string | undefined;
  isSettling: () => boolean;
}

export function FormFieldCard<Field extends CardField>({
  field,
  index,
  count,
  sectionId,
  sectionTitle,
  isSettling,
  dragTypes,
  fieldTypeLabel,
  moveField,
  moveFieldToSection,
  onEditField,
  onDeleteField,
}: FormFieldCardProps<Field>) {
  const { t } = useTranslation();
  const canModify = useSettingsPageCanModify();
  const sortable = useSortable<HTMLDivElement>({
    type: dragTypes.field,
    id: field.id,
    label: field.label,
    index,
    count,
    group: sectionId,
    groupLabel: sectionTitle,
    disabled: !canModify,
    isSettling,
    onMove: (dragIndex, hoverIndex) => {
      moveField(dragIndex, hoverIndex, sectionId);
    },
    onDropFromOtherGroup: (item) => {
      moveFieldToSection(item.id, sectionId, index);
    },
  });

  return (
    <GroupRow
      ref={sortable.ref}
      handle={sortable.handle}
      dragging={sortable.isDragging}
      title={(
        <>
          {field.label}
          {field.required && (
            <>
              <span className="ml-0.5 text-destructive-text" aria-hidden="true">*</span>
              <span className="sr-only">{t('settings.tickets.required')}</span>
            </>
          )}
        </>
      )}
      detail={fieldTypeLabel(field)}
      actions={canModify && (
        <>
          <IconAction icon={Pencil} label={t('settings.tickets.editFormField')} onClick={() => { onEditField(field); }} />
          <IconAction icon={Trash2} label={t('settings.tickets.deleteFormFieldTitle')} destructive onClick={() => { onDeleteField(field); }} />
        </>
      )}
    />
  );
}

interface FormFieldDropZoneProps {
  sectionId: string;
  dragType: string;
  moveFieldToSection: (fieldId: string, toSectionId: string) => void;
}

function FormFieldDropZone({ sectionId, dragType, moveFieldToSection }: FormFieldDropZoneProps) {
  const { t } = useTranslation();
  const [{ isOver, canDrop }, drop] = useDrop<SortableItem, unknown, { isOver: boolean; canDrop: boolean }>({
    accept: dragType,
    drop: (item) => {
      moveFieldToSection(item.id, sectionId);
    },
    canDrop: (item) => item.group !== sectionId,
    collect: (monitor) => ({ isOver: monitor.isOver(), canDrop: monitor.canDrop() }),
  });
  const dropRef = useDndConnectorRef<HTMLDivElement>(drop);
  const highlighted = isOver && canDrop;

  if (!canDrop) return null;

  return (
    <div
      ref={dropRef}
      className={cn(
        'flex min-h-10 items-center justify-center rounded-lg border-2 border-dashed p-2 text-sm transition-colors',
        highlighted ? 'border-primary bg-primary/10 text-primary' : 'border-muted-foreground/50',
      )}
    >
      {highlighted && t('settings.tickets.dropFieldHere')}
    </div>
  );
}

interface FormSectionCardProps<Field extends CardField, Section extends CardSection> extends FieldCardActions<Field> {
  section: Section;
  index: number;
  count: number;
  headingLevel: 3 | 4;
  fields: Field[];
  isSettling: () => boolean;
  moveSection: (dragIndex: number, hoverIndex: number) => void;
  onEditSection: (section: Section) => void;
  onDeleteSection: (section: Section) => void;
  onAddField: (sectionId: string) => void;
}

export function FormSectionCard<Field extends CardField, Section extends CardSection>({
  section,
  index,
  count,
  headingLevel,
  fields,
  isSettling,
  moveSection,
  onEditSection,
  onDeleteSection,
  onAddField,
  ...fieldActions
}: FormSectionCardProps<Field, Section>) {
  const { t } = useTranslation();
  const canModify = useSettingsPageCanModify();
  const fieldList = useFlipList<HTMLDivElement>(fields.map((field) => field.id));
  const sortable = useSortable<HTMLDivElement>({
    type: fieldActions.dragTypes.section,
    id: section.id,
    label: section.title,
    index,
    count,
    disabled: !canModify,
    isSettling,
    onMove: moveSection,
  });

  return (
    <GroupCard
      ref={sortable.ref}
      headingLevel={headingLevel}
      handle={sortable.handle}
      dragging={sortable.isDragging}
      title={section.title}
      meta={section.hideByDefault === true && <Badge variant="outline">{t('settings.appealFormEditor.hiddenByDefault')}</Badge>}
      description={section.description}
      actions={canModify && (
        <>
          <IconAction icon={Plus} label={t('settings.tickets.addField')} onClick={() => { onAddField(section.id); }} />
          <IconAction icon={Pencil} label={t('settings.tickets.editSection')} onClick={() => { onEditSection(section); }} />
          <IconAction icon={Trash2} label={t('settings.tickets.deleteSectionTitle')} destructive onClick={() => { onDeleteSection(section); }} />
        </>
      )}
    >
      <GroupRows ref={fieldList.ref}>
        {fields.map((field, fieldIndex) => (
          <FormFieldCard key={field.id} field={field} index={fieldIndex} count={fields.length} sectionId={section.id} sectionTitle={section.title} isSettling={fieldList.isSettling} {...fieldActions} />
        ))}
        <FormFieldDropZone sectionId={section.id} dragType={fieldActions.dragTypes.field} moveFieldToSection={fieldActions.moveFieldToSection} />
      </GroupRows>
    </GroupCard>
  );
}

export interface FormDeletion {
  kind: 'field' | 'section';
  id: string;
  name: string;
}

export function formDeletionRequest(t: TFunction, deletion: FormDeletion): ConfirmationRequest {
  switch (deletion.kind) {
    case 'field':
      return {
        title: t('settings.tickets.deleteFormFieldTitle'),
        description: t('settings.tickets.deleteFormFieldConfirm', { label: deletion.name }),
        action: t('common.delete'),
        destructive: true,
      };
    case 'section':
      return {
        title: t('settings.tickets.deleteSectionTitle'),
        description: t('settings.tickets.deleteSectionConfirm', { title: deletion.name }),
        action: t('common.delete'),
        destructive: true,
      };
  }
}
