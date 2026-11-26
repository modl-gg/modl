import { createElement, useId, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { EyeOff, Pencil, Plus, Trash2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { GroupRow, GroupRows } from '@/components/ui/group-list';
import { IconAction } from '@/components/ui/icon-action';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Switch } from '@/components/ui/switch';
import { Textarea } from '@/components/ui/textarea';
import type { HomepageCardActionType } from '@/hooks/data/homepage';
import { MAX_HOMEPAGE_CARDS, type HomepageCardDraft } from '@/hooks/data/homepage-settings';
import { useKnowledgebaseCategories } from '@/hooks/data/knowledgebase';
import { useConfirmation } from '@/hooks/use-confirmation';
import { useFlipList } from '@/hooks/use-flip';
import { useSortable } from '@/hooks/use-sortable';
import { homepageIcon, HOMEPAGE_ICON_NAMES } from '@/lib/homepage-icons';
import { movedItem } from '@/lib/move-item';
import { safeExternalHref } from '@/lib/utils';
import { SettingsSection } from './layout/SettingsSections';

export interface EditableHomepageCard extends HomepageCardDraft {
  key: string;
}

const ITEM_TYPE_HOMEPAGE_CARD = 'homepage-card';
const NEW_CARD_PREFIX = 'new-';
const MAX_TITLE_LENGTH = 128;
const MAX_DESCRIPTION_LENGTH = 500;

const EMPTY_CARD: HomepageCardDraft = {
  id: '',
  title: '',
  description: '',
  icon: 'BookOpen',
  actionType: 'url',
  actionUrl: '',
  categoryId: '',
  categoryName: '',
  enabled: true,
};

export function editableHomepageCards(cards: readonly HomepageCardDraft[]): EditableHomepageCard[] {
  return cards.map((card) => ({ key: card.id, ...card }));
}

export function savedHomepageCards(cards: readonly EditableHomepageCard[]): HomepageCardDraft[] {
  return cards.map((card) => ({
    id: card.id,
    title: card.title,
    description: card.description,
    icon: card.icon,
    actionType: card.actionType,
    actionUrl: card.actionUrl,
    categoryId: card.categoryId,
    categoryName: card.categoryName,
    enabled: card.enabled,
  }));
}

export function isHomepageCardValid(card: HomepageCardDraft): boolean {
  const title = card.title.trim();
  if (title === '' || title.length > MAX_TITLE_LENGTH || card.description.length > MAX_DESCRIPTION_LENGTH) return false;
  if (card.actionType === 'url') return safeExternalHref(card.actionUrl) !== undefined;
  return card.categoryId !== '';
}

const isHomepageCardActionType = (value: string): value is HomepageCardActionType =>
  value === 'url' || value === 'category_dropdown';

const IconPreview = ({ iconName }: { iconName: string }) =>
  createElement(homepageIcon(iconName), { className: 'h-5 w-5 text-muted-foreground', 'aria-hidden': true });

interface HomepageCardRowProps {
  card: EditableHomepageCard;
  index: number;
  count: number;
  canModify: boolean;
  isSettling: () => boolean;
  onMove: (dragIndex: number, hoverIndex: number) => void;
  onEdit: (card: EditableHomepageCard) => void;
  onDelete: (card: EditableHomepageCard) => void;
}

function HomepageCardRow({ card, index, count, canModify, isSettling, onMove, onEdit, onDelete }: HomepageCardRowProps) {
  const { t } = useTranslation();
  const sortable = useSortable<HTMLDivElement>({
    type: ITEM_TYPE_HOMEPAGE_CARD,
    id: card.key,
    label: card.title,
    index,
    count,
    disabled: !canModify,
    isSettling,
    onMove,
  });

  return (
    <GroupRow
      ref={sortable.ref}
      handle={sortable.handle}
      dragging={sortable.isDragging}
      leading={<IconPreview iconName={card.icon} />}
      title={card.title}
      meta={!card.enabled && <EyeOff className="h-4 w-4 shrink-0 text-muted-foreground" role="img" aria-label={t('settings.homepage.hidden')} />}
      description={card.description}
      detail={card.actionType === 'url'
        ? t('settings.homepage.cardUrlSummary', { url: card.actionUrl })
        : t('settings.homepage.cardCategorySummary', { name: card.categoryName })}
      actions={canModify && (
        <>
          <IconAction icon={Pencil} label={t('settings.homepage.editCard')} onClick={() => { onEdit(card); }} />
          <IconAction icon={Trash2} label={t('settings.homepage.deleteCardTitle')} destructive onClick={() => { onDelete(card); }} />
        </>
      )}
    />
  );
}

interface HomepageCardDialogProps {
  editing: EditableHomepageCard | null;
  onClose: () => void;
  onSubmit: (card: HomepageCardDraft) => void;
}

function HomepageCardDialog({ editing, onClose, onSubmit }: HomepageCardDialogProps) {
  const { t } = useTranslation();
  const fieldId = useId();
  const { data: categories } = useKnowledgebaseCategories();
  const [form, setForm] = useState<HomepageCardDraft>(editing ?? EMPTY_CARD);
  const valid = isHomepageCardValid(form);

  const submit = () => {
    if (!valid) return;
    const categoryName = categories?.find((category) => category.id === form.categoryId)?.name ?? form.categoryName;
    onSubmit({ ...form, title: form.title.trim(), description: form.description.trim(), actionUrl: form.actionUrl.trim(), categoryName });
  };

  return (
    <Dialog open onOpenChange={(open) => { if (!open) onClose(); }}>
      <DialogContent className="max-w-2xl" aria-describedby={undefined} onInteractOutside={(event) => { event.preventDefault(); }}>
        <DialogHeader>
          <DialogTitle>{editing === null ? t('settings.homepage.addNewCard') : t('settings.homepage.editCard')}</DialogTitle>
        </DialogHeader>
        <form
          id={`${fieldId}-form`}
          className="space-y-4"
          onSubmit={(event) => {
            event.preventDefault();
            submit();
          }}
        >
          <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
            <div className="space-y-2">
              <Label htmlFor={`${fieldId}-title`}>{t('settings.homepage.cardTitle')}</Label>
              <Input
                id={`${fieldId}-title`}
                placeholder={t('settings.homepage.cardTitlePlaceholder')}
                value={form.title}
                maxLength={MAX_TITLE_LENGTH}
                onChange={(event) => { setForm({ ...form, title: event.target.value }); }}
              />
            </div>
            <div className="space-y-2">
              <Label htmlFor={`${fieldId}-icon`}>{t('settings.homepage.icon')}</Label>
              <Select value={form.icon} onValueChange={(icon) => { setForm({ ...form, icon }); }}>
                <SelectTrigger id={`${fieldId}-icon`}>
                  <SelectValue />
                </SelectTrigger>
                <SelectContent className="max-h-48">
                  {HOMEPAGE_ICON_NAMES.map((iconName) => (
                    <SelectItem key={iconName} value={iconName}>
                      <div className="flex items-center gap-2">
                        <IconPreview iconName={iconName} />
                        {iconName}
                      </div>
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
          </div>

          <div className="space-y-2">
            <Label htmlFor={`${fieldId}-description`}>{t('settings.homepage.cardDescription')}</Label>
            <Textarea
              id={`${fieldId}-description`}
              placeholder={t('settings.homepage.cardDescriptionPlaceholder')}
              value={form.description}
              maxLength={MAX_DESCRIPTION_LENGTH}
              onChange={(event) => { setForm({ ...form, description: event.target.value }); }}
            />
          </div>

          <div className="space-y-2">
            <Label htmlFor={`${fieldId}-actionType`}>{t('settings.homepage.actionType')}</Label>
            <Select
              value={form.actionType}
              onValueChange={(value) => {
                if (isHomepageCardActionType(value)) setForm({ ...form, actionType: value });
              }}
            >
              <SelectTrigger id={`${fieldId}-actionType`}>
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="url">{t('settings.homepage.urlAction')}</SelectItem>
                <SelectItem value="category_dropdown">{t('settings.homepage.categoryDropdown')}</SelectItem>
              </SelectContent>
            </Select>
          </div>

          {form.actionType === 'url' ? (
            <div className="space-y-2">
              <Label htmlFor={`${fieldId}-actionUrl`}>{t('settings.homepage.url')}</Label>
              <Input
                id={`${fieldId}-actionUrl`}
                placeholder={t('settings.homepage.urlPlaceholder')}
                value={form.actionUrl}
                onChange={(event) => { setForm({ ...form, actionUrl: event.target.value }); }}
              />
            </div>
          ) : (
            <div className="space-y-2">
              <Label htmlFor={`${fieldId}-categoryId`}>{t('settings.homepage.category')}</Label>
              <Select value={form.categoryId} onValueChange={(categoryId) => { setForm({ ...form, categoryId }); }}>
                <SelectTrigger id={`${fieldId}-categoryId`}>
                  <SelectValue placeholder={t('settings.homepage.selectCategory')} />
                </SelectTrigger>
                <SelectContent>
                  {categories?.map((category) => (
                    <SelectItem key={category.id} value={category.id}>{category.name}</SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
          )}

          <div className="flex items-center space-x-2">
            <Switch
              id={`${fieldId}-enabled`}
              checked={form.enabled}
              onCheckedChange={(enabled) => { setForm({ ...form, enabled }); }}
            />
            <Label htmlFor={`${fieldId}-enabled`}>{t('common.enabled')}</Label>
          </div>
        </form>
        <DialogFooter>
          <Button type="button" variant="outline" onClick={onClose}>{t('common.cancel')}</Button>
          <Button type="submit" form={`${fieldId}-form`} disabled={!valid}>
            {editing === null ? t('common.add') : t('common.update')}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

type EditorState = { mode: 'closed' } | { mode: 'create' } | { mode: 'edit'; card: EditableHomepageCard };

interface HomepageCardListProps {
  cards: EditableHomepageCard[];
  canModify: boolean;
  onChange: (update: (cards: EditableHomepageCard[]) => EditableHomepageCard[]) => void;
}

export function HomepageCardList({ cards, canModify, onChange }: HomepageCardListProps) {
  const { t } = useTranslation();
  const confirmation = useConfirmation();
  const [editor, setEditor] = useState<EditorState>({ mode: 'closed' });
  const nextCardIdRef = useRef(0);
  const cardList = useFlipList<HTMLDivElement>(cards.map((card) => card.key));

  const closeEditor = () => { setEditor({ mode: 'closed' }); };

  const submit = (card: HomepageCardDraft) => {
    if (editor.mode === 'edit') {
      const { key } = editor.card;
      onChange((current) => current.map((candidate) => (candidate.key === key ? { ...card, id: candidate.id, key } : candidate)));
    } else {
      nextCardIdRef.current += 1;
      const key = `${NEW_CARD_PREFIX}${String(nextCardIdRef.current)}`;
      onChange((current) => (current.length >= MAX_HOMEPAGE_CARDS ? current : [...current, { ...card, id: '', key }]));
    }
    closeEditor();
  };

  const remove = async (card: EditableHomepageCard) => {
    const confirmed = await confirmation.confirm({
      title: t('settings.homepage.deleteCardTitle'),
      description: t('settings.homepage.removeCardConfirm', { title: card.title }),
      action: t('common.delete'),
      destructive: true,
    });
    if (confirmed) onChange((current) => current.filter((candidate) => candidate.key !== card.key));
  };

  const move = (dragIndex: number, hoverIndex: number) => {
    onChange((current) => movedItem(current, dragIndex, hoverIndex));
  };

  return (
    <SettingsSection
      title={t('settings.homepage.cards')}
      action={canModify && (
        <IconAction
          icon={Plus}
          label={t('settings.homepage.addNewCard')}
          disabled={cards.length >= MAX_HOMEPAGE_CARDS}
          onClick={() => { setEditor({ mode: 'create' }); }}
        />
      )}
    >
      {cards.length === 0 ? (
        <p className="py-4 text-center text-sm text-muted-foreground">{t('settings.homepage.noCards')}</p>
      ) : (
        <GroupRows ref={cardList.ref}>
          {cards.map((card, index) => (
            <HomepageCardRow
              key={card.key}
              card={card}
              index={index}
              count={cards.length}
              canModify={canModify}
              isSettling={cardList.isSettling}
              onMove={move}
              onEdit={(edited) => { setEditor({ mode: 'edit', card: edited }); }}
              onDelete={(deleted) => { void remove(deleted); }}
            />
          ))}
        </GroupRows>
      )}
      {editor.mode !== 'closed' && (
        <HomepageCardDialog
          editing={editor.mode === 'edit' ? editor.card : null}
          onClose={closeEditor}
          onSubmit={submit}
        />
      )}
      {confirmation.dialog}
    </SettingsSection>
  );
}
