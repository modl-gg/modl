import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Pencil, Plus, Trash2 } from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import { GroupCard, GroupRow, GroupRows } from '@/components/ui/group-list';
import { IconAction } from '@/components/ui/icon-action';
import { useConfirmation } from '@/hooks/use-confirmation';
import { useFlipList } from '@/hooks/use-flip';
import { useSortable } from '@/hooks/use-sortable';
import { movedItem, renumbered } from '@/lib/move-item';
import type { QuickResponseAction, QuickResponseCategory, QuickResponsesConfiguration } from '@/types/quickResponses';
import { SettingsPageAction, SettingsPageActionButton, useSettingsPageCanModify } from './layout/SettingsPageFrame';
import { SettingsDndProvider } from './save/SettingsDndProvider';
import { QuickResponseActionDialog, QuickResponseCategoryDialog, ticketTypeLabel } from './QuickResponseForms';

const ACTION_DRAG_TYPE = 'quick-response-action';
const CATEGORY_DRAG_TYPE = 'quick-response-category';

type QuickResponsesUpdate = (update: (config: QuickResponsesConfiguration) => QuickResponsesConfiguration) => void;

function withCategory(
  config: QuickResponsesConfiguration,
  categoryId: string,
  update: (category: QuickResponseCategory) => QuickResponseCategory,
): QuickResponsesConfiguration {
  return { ...config, categories: config.categories.map((category) => (category.id === categoryId ? update(category) : category)) };
}

function movedAction(category: QuickResponseCategory, dragIndex: number, hoverIndex: number): QuickResponseCategory {
  return { ...category, actions: renumbered(movedItem(category.actions, dragIndex, hoverIndex)) };
}

function movedCategory(config: QuickResponsesConfiguration, dragIndex: number, hoverIndex: number): QuickResponsesConfiguration {
  return { ...config, categories: renumbered(movedItem(config.categories, dragIndex, hoverIndex)) };
}

function upsertedAction(category: QuickResponseCategory, action: QuickResponseAction): QuickResponseCategory {
  const exists = category.actions.some((candidate) => candidate.id === action.id);
  return {
    ...category,
    actions: exists
      ? category.actions.map((candidate) => (candidate.id === action.id ? action : candidate))
      : [...category.actions, action],
  };
}

function upsertedCategory(config: QuickResponsesConfiguration, category: QuickResponseCategory): QuickResponsesConfiguration {
  const exists = config.categories.some((candidate) => candidate.id === category.id);
  return {
    ...config,
    categories: exists
      ? config.categories.map((candidate) => (candidate.id === category.id ? category : candidate))
      : [...config.categories, category],
  };
}

interface QuickResponseActionRowProps {
  action: QuickResponseAction;
  index: number;
  count: number;
  categoryId: string;
  categoryName: string;
  isSettling: () => boolean;
  onMove: (dragIndex: number, hoverIndex: number) => void;
  onEdit: () => void;
  onDelete: () => void;
}

function QuickResponseActionRow({ action, index, count, categoryId, categoryName, isSettling, onMove, onEdit, onDelete }: QuickResponseActionRowProps) {
  const { t } = useTranslation();
  const canModify = useSettingsPageCanModify();
  const sortable = useSortable<HTMLDivElement>({
    type: ACTION_DRAG_TYPE,
    id: action.id,
    label: action.name,
    index,
    count,
    group: categoryId,
    groupLabel: categoryName,
    disabled: !canModify,
    isSettling,
    onMove,
  });

  return (
    <GroupRow
      ref={sortable.ref}
      handle={sortable.handle}
      dragging={sortable.isDragging}
      title={action.name}
      meta={(
        <>
          {action.closeTicket === true && <Badge variant="outline">{t('settings.tickets.badgeClose')}</Badge>}
          {action.showPunishment === true && <Badge variant="outline">{t('settings.tickets.badgePunish')}</Badge>}
          {action.appealAction === 'pardon' && <Badge variant="outline">{t('settings.tickets.badgePardon')}</Badge>}
          {action.appealAction === 'reduce' && <Badge variant="outline">{t('settings.tickets.badgeReduce')}</Badge>}
        </>
      )}
      description={action.message}
      actions={canModify && (
        <>
          <IconAction icon={Pencil} label={t('settings.tickets.editQuickResponse')} onClick={onEdit} />
          <IconAction icon={Trash2} label={t('settings.tickets.deleteQuickResponseTitle')} destructive onClick={onDelete} />
        </>
      )}
    />
  );
}

interface QuickResponseCategoryCardProps {
  category: QuickResponseCategory;
  index: number;
  count: number;
  isSettling: () => boolean;
  onMove: (dragIndex: number, hoverIndex: number) => void;
  onMoveAction: (dragIndex: number, hoverIndex: number) => void;
  onEditCategory: () => void;
  onDeleteCategory: () => void;
  onAddAction: () => void;
  onEditAction: (action: QuickResponseAction) => void;
  onDeleteAction: (action: QuickResponseAction) => void;
}

function QuickResponseCategoryCard({
  category,
  index,
  count,
  isSettling,
  onMove,
  onMoveAction,
  onEditCategory,
  onDeleteCategory,
  onAddAction,
  onEditAction,
  onDeleteAction,
}: QuickResponseCategoryCardProps) {
  const { t } = useTranslation();
  const canModify = useSettingsPageCanModify();
  const actionList = useFlipList<HTMLDivElement>(category.actions.map((action) => action.id));
  const sortable = useSortable<HTMLDivElement>({
    type: CATEGORY_DRAG_TYPE,
    id: category.id,
    label: category.name,
    index,
    count,
    disabled: !canModify,
    isSettling,
    onMove,
  });

  return (
    <GroupCard
      ref={sortable.ref}
      headingLevel={3}
      handle={sortable.handle}
      dragging={sortable.isDragging}
      title={category.name}
      description={category.ticketTypes.map((type) => ticketTypeLabel(type, t)).join(', ')}
      actions={canModify && (
        <>
          <IconAction icon={Plus} label={t('settings.tickets.addQuickResponse')} onClick={onAddAction} />
          <IconAction icon={Pencil} label={t('settings.tickets.editCategory')} onClick={onEditCategory} />
          <IconAction icon={Trash2} label={t('settings.tickets.deleteCategoryTitle')} destructive onClick={onDeleteCategory} />
        </>
      )}
    >
      {category.actions.length > 0 && (
        <GroupRows ref={actionList.ref}>
          {category.actions.map((action, actionIndex) => (
            <QuickResponseActionRow
              key={action.id}
              action={action}
              index={actionIndex}
              count={category.actions.length}
              categoryId={category.id}
              categoryName={category.name}
              isSettling={actionList.isSettling}
              onMove={onMoveAction}
              onEdit={() => { onEditAction(action); }}
              onDelete={() => { onDeleteAction(action); }}
            />
          ))}
        </GroupRows>
      )}
    </GroupCard>
  );
}

interface ActionEditorState {
  categoryId: string;
  action: QuickResponseAction | null;
}

interface CategoryEditorState {
  category: QuickResponseCategory | null;
}

type QuickResponseDeletion =
  | { kind: 'action'; categoryId: string; action: QuickResponseAction }
  | { kind: 'category'; category: QuickResponseCategory };

interface QuickResponseSettingsProps {
  quickResponses: QuickResponsesConfiguration;
  onQuickResponsesChange: QuickResponsesUpdate;
}

function withoutDeleted(config: QuickResponsesConfiguration, deletion: QuickResponseDeletion): QuickResponsesConfiguration {
  switch (deletion.kind) {
    case 'action':
      return withCategory(config, deletion.categoryId, (category) => ({
        ...category,
        actions: category.actions.filter((action) => action.id !== deletion.action.id),
      }));
    case 'category':
      return { ...config, categories: config.categories.filter((category) => category.id !== deletion.category.id) };
  }
}

export default function QuickResponseSettings({ quickResponses, onQuickResponsesChange }: QuickResponseSettingsProps) {
  const { t } = useTranslation();
  const [actionEditor, setActionEditor] = useState<ActionEditorState | null>(null);
  const [categoryEditor, setCategoryEditor] = useState<CategoryEditorState | null>(null);
  const confirmation = useConfirmation();
  const categoryList = useFlipList<HTMLDivElement>(quickResponses.categories.map((category) => category.id));
  const editedCategory = actionEditor === null
    ? undefined
    : quickResponses.categories.find((category) => category.id === actionEditor.categoryId);

  const confirmDeletion = async (deletion: QuickResponseDeletion) => {
    const confirmed = await confirmation.confirm({
      title: deletion.kind === 'action' ? t('settings.tickets.deleteQuickResponseTitle') : t('settings.tickets.deleteCategoryTitle'),
      description: deletion.kind === 'action'
        ? t('settings.tickets.deleteQuickResponseConfirm', { name: deletion.action.name })
        : t('settings.tickets.deleteCategoryConfirm', { name: deletion.category.name }),
      action: t('common.delete'),
      destructive: true,
    });
    if (confirmed) onQuickResponsesChange((config) => withoutDeleted(config, deletion));
  };

  return (
    <SettingsDndProvider>
      <SettingsPageAction>
        <SettingsPageActionButton icon={Plus} label={t('settings.tickets.addCategory')} onClick={() => { setCategoryEditor({ category: null }); }} />
      </SettingsPageAction>

      {quickResponses.categories.length === 0 ? (
        <p className="py-8 text-center text-sm text-muted-foreground">{t('settings.tickets.noCategoriesYet')}</p>
      ) : (
        <div ref={categoryList.ref} className="space-y-3">
          {quickResponses.categories.map((category, index) => (
            <QuickResponseCategoryCard
              key={category.id}
              category={category}
              index={index}
              count={quickResponses.categories.length}
              isSettling={categoryList.isSettling}
              onMove={(dragIndex, hoverIndex) => {
                onQuickResponsesChange((config) => movedCategory(config, dragIndex, hoverIndex));
              }}
              onMoveAction={(dragIndex, hoverIndex) => {
                onQuickResponsesChange((config) => withCategory(config, category.id, (current) => movedAction(current, dragIndex, hoverIndex)));
              }}
              onEditCategory={() => { setCategoryEditor({ category }); }}
              onDeleteCategory={() => { void confirmDeletion({ kind: 'category', category }); }}
              onAddAction={() => { setActionEditor({ categoryId: category.id, action: null }); }}
              onEditAction={(action) => { setActionEditor({ categoryId: category.id, action }); }}
              onDeleteAction={(action) => { void confirmDeletion({ kind: 'action', categoryId: category.id, action }); }}
            />
          ))}
        </div>
      )}

      {actionEditor !== null && editedCategory !== undefined && (
        <QuickResponseActionDialog
          action={actionEditor.action}
          category={editedCategory}
          onSubmit={(action) => {
            onQuickResponsesChange((config) => withCategory(config, actionEditor.categoryId, (category) => upsertedAction(category, action)));
            setActionEditor(null);
          }}
          onClose={() => {
            setActionEditor(null);
          }}
        />
      )}

      {categoryEditor !== null && (
        <QuickResponseCategoryDialog
          category={categoryEditor.category}
          order={quickResponses.categories.length}
          onSubmit={(category) => {
            onQuickResponsesChange((config) => upsertedCategory(config, category));
            setCategoryEditor(null);
          }}
          onClose={() => {
            setCategoryEditor(null);
          }}
        />
      )}

      {confirmation.dialog}
    </SettingsDndProvider>
  );
}
