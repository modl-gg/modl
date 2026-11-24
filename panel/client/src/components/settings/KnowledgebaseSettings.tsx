import { useCallback, useId, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Pencil, Plus, Trash2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { GroupCard, GroupRows } from '@/components/ui/group-list';
import { IconAction } from '@/components/ui/icon-action';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import MarkdownEditor from '@/components/ui/MarkdownEditor';
import { Switch } from '@/components/ui/switch';
import {
  fetchPanelKnowledgebaseArticle,
  useCreateKnowledgebaseArticle,
  useCreateKnowledgebaseCategory,
  useDeleteKnowledgebaseArticle,
  useDeleteKnowledgebaseCategory,
  useKnowledgebaseCategories,
  useReorderKnowledgebaseArticles,
  useReorderKnowledgebaseCategories,
  useUpdateKnowledgebaseArticle,
  useUpdateKnowledgebaseCategory,
} from '@/hooks/data/knowledgebase';
import { useConfirmation } from '@/hooks/use-confirmation';
import { useFlipList } from '@/hooks/use-flip';
import { useSortable } from '@/hooks/use-sortable';
import { useToast } from '@/hooks/use-toast';
import { optionalField } from '@/lib/defined-fields';
import { describeError } from '@/lib/http-error';
import type { KnowledgebaseArticleStub, KnowledgebaseCategory } from '@/types/knowledgebase';
import ArticleListItem from './ArticleListItem';
import { SettingsPageAction, SettingsPageActionButton, useSettingsPageCanModify } from './layout/SettingsPageFrame';
import { SettingsLoading } from './layout/SettingsSections';
import { SettingsDndProvider } from './save/SettingsDndProvider';
import { useSavedOrder } from './save/use-saved-order';

const CATEGORY_DRAG_TYPE = 'category';

const NO_CATEGORIES: readonly KnowledgebaseCategory[] = [];

interface ArticleDraft {
  categoryId: string;
  articleId: string | null;
  title: string;
  content: string;
  isVisible: boolean;
}

interface CategoryDraft {
  id: string | null;
  name: string;
  description: string;
}

function useKnowledgebaseFailureToast() {
  const { toast } = useToast();
  const { t } = useTranslation();

  return useCallback((error: unknown, fallbackKey: string) => {
    toast({ title: t('toast.error'), description: describeError(error, t(fallbackKey)), variant: 'destructive' });
  }, [t, toast]);
}

interface CategoryItemProps {
  category: KnowledgebaseCategory;
  index: number;
  count: number;
  loadingArticleId: string | null;
  isSettling: () => boolean;
  onMove: (dragIndex: number, hoverIndex: number) => void;
  onEdit: (category: KnowledgebaseCategory) => void;
  onDelete: (category: KnowledgebaseCategory) => void;
  onAddArticle: (categoryId: string) => void;
  onEditArticle: (article: KnowledgebaseArticleStub) => void;
  onDeleteArticle: (article: KnowledgebaseArticleStub) => void;
}

function CategoryItem({
  category,
  index,
  count,
  loadingArticleId,
  isSettling,
  onMove,
  onEdit,
  onDelete,
  onAddArticle,
  onEditArticle,
  onDeleteArticle,
}: CategoryItemProps) {
  const { t } = useTranslation();
  const { mutateAsync: reorderArticles } = useReorderKnowledgebaseArticles();
  const canModify = useSettingsPageCanModify();
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
  const articleOrder = useSavedOrder(category.articles, async (ordered) =>
    reorderArticles({ categoryId: category.id, orderedArticleIds: ordered.map((article) => article.id) }));
  const articleList = useFlipList<HTMLDivElement>(articleOrder.items.map((article) => article.id));

  return (
    <GroupCard
      ref={sortable.ref}
      headingLevel={3}
      handle={sortable.handle}
      dragging={sortable.isDragging}
      title={category.name}
      description={category.description}
      actions={canModify && (
        <>
          <IconAction icon={Plus} label={t('settings.knowledgebase.addArticle')} onClick={() => { onAddArticle(category.id); }} />
          <IconAction icon={Pencil} label={t('settings.knowledgebase.editCategory')} onClick={() => { onEdit(category); }} />
          <IconAction icon={Trash2} label={t('settings.knowledgebase.deleteCategoryTitle')} destructive onClick={() => { onDelete(category); }} />
        </>
      )}
    >
      {articleOrder.items.length > 0 && (
        <GroupRows ref={articleList.ref}>
          {articleOrder.items.map((article, articleIndex) => (
            <ArticleListItem
              key={article.id}
              article={article}
              index={articleIndex}
              count={articleOrder.items.length}
              editPending={loadingArticleId === article.id}
              categoryId={category.id}
              categoryName={category.name}
              isSettling={articleList.isSettling}
              onMove={articleOrder.move}
              onEdit={(edited) => { onEditArticle({ ...edited, categoryId: category.id }); }}
              onDelete={(deleted) => { onDeleteArticle({ ...deleted, categoryId: category.id }); }}
            />
          ))}
        </GroupRows>
      )}
    </GroupCard>
  );
}

interface CategoryDialogProps {
  draft: CategoryDraft;
  isSaving: boolean;
  onChange: (draft: CategoryDraft) => void;
  onSubmit: () => void;
  onClose: () => void;
}

function CategoryDialog({ draft, isSaving, onChange, onSubmit, onClose }: CategoryDialogProps) {
  const { t } = useTranslation();
  const formId = useId();

  return (
    <Dialog open onOpenChange={(open) => {
      if (!open) onClose();
    }}>
      <DialogContent className="max-w-md" aria-describedby={undefined}>
        <DialogHeader>
          <DialogTitle>{draft.id === null ? t('settings.knowledgebase.addCategory') : t('settings.knowledgebase.editCategory')}</DialogTitle>
        </DialogHeader>
        <form
          id={formId}
          className="space-y-4"
          onSubmit={(event) => {
            event.preventDefault();
            onSubmit();
          }}
        >
          <div className="space-y-2">
            <Label htmlFor={`${formId}-name`}>{t('settings.knowledgebase.categoryName')}</Label>
            <Input id={`${formId}-name`} value={draft.name} onChange={(event) => onChange({ ...draft, name: event.target.value })} />
          </div>
          <div className="space-y-2">
            <Label htmlFor={`${formId}-description`}>{t('settings.knowledgebase.categoryDescriptionOptional')}</Label>
            <Input id={`${formId}-description`} value={draft.description} onChange={(event) => onChange({ ...draft, description: event.target.value })} />
          </div>
        </form>
        <DialogFooter>
          <Button type="button" variant="outline" onClick={onClose}>{t('common.cancel')}</Button>
          <Button type="submit" form={formId} disabled={isSaving || draft.name.trim() === ''}>{t('common.save')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

interface ArticleDialogProps {
  draft: ArticleDraft;
  isSaving: boolean;
  onChange: (draft: ArticleDraft) => void;
  onSubmit: () => void;
  onClose: () => void;
}

function ArticleDialog({ draft, isSaving, onChange, onSubmit, onClose }: ArticleDialogProps) {
  const { t } = useTranslation();
  const fieldId = useId();
  const valid = draft.title.trim() !== '' && draft.content.trim() !== '';

  return (
    <Dialog open onOpenChange={(open) => {
      if (!open) onClose();
    }}>
      <DialogContent className="max-h-[90dvh] max-w-3xl overflow-y-auto" aria-describedby={undefined} onInteractOutside={(event) => { event.preventDefault(); }}>
        <DialogHeader>
          <DialogTitle>{draft.articleId === null ? t('settings.knowledgebase.addArticle') : t('settings.knowledgebase.editArticle')}</DialogTitle>
        </DialogHeader>
        <div className="space-y-4">
          <div className="space-y-2">
            <Label htmlFor={`${fieldId}-title`}>{t('settings.knowledgebase.articleTitle')}</Label>
            <Input id={`${fieldId}-title`} value={draft.title} onChange={(event) => onChange({ ...draft, title: event.target.value })} />
          </div>
          <div className="space-y-2">
            <span className="text-sm font-medium">{t('settings.knowledgebase.articleContent')}</span>
            <MarkdownEditor value={draft.content} onChange={(content) => onChange({ ...draft, content })} />
          </div>
          <div className="flex items-center gap-3">
            <Switch id={`${fieldId}-visible`} checked={draft.isVisible} onCheckedChange={(isVisible) => onChange({ ...draft, isVisible })} />
            <Label htmlFor={`${fieldId}-visible`}>{t('settings.knowledgebase.visibleToUsers')}</Label>
          </div>
        </div>
        <DialogFooter>
          <Button type="button" variant="outline" onClick={onClose}>{t('common.cancel')}</Button>
          <Button type="button" onClick={onSubmit} disabled={isSaving || !valid}>{t('common.save')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

export default function KnowledgebaseSettings() {
  const { t } = useTranslation();
  const [categoryDraft, setCategoryDraft] = useState<CategoryDraft | null>(null);
  const [articleDraft, setArticleDraft] = useState<ArticleDraft | null>(null);
  const [loadingArticleId, setLoadingArticleId] = useState<string | null>(null);
  const confirmation = useConfirmation();

  const { data: categories, isLoading: isLoadingCategories } = useKnowledgebaseCategories();

  const createCategoryMutation = useCreateKnowledgebaseCategory();
  const updateCategoryMutation = useUpdateKnowledgebaseCategory();
  const deleteCategoryMutation = useDeleteKnowledgebaseCategory();
  const { mutateAsync: reorderCategories } = useReorderKnowledgebaseCategories();
  const categoryOrder = useSavedOrder(categories ?? NO_CATEGORIES, async (ordered) => reorderCategories(ordered.map((category) => category.id)));
  const categoryList = useFlipList<HTMLDivElement>(categoryOrder.items.map((category) => category.id));
  const createArticleMutation = useCreateKnowledgebaseArticle();
  const updateArticleMutation = useUpdateKnowledgebaseArticle();
  const deleteArticleMutation = useDeleteKnowledgebaseArticle();
  const toastFailure = useKnowledgebaseFailureToast();

  const saveCategory = (draft: CategoryDraft) => {
    const name = draft.name.trim();
    if (name === '') return;
    const fields = { name, ...optionalField('description', draft.description.trim() || undefined) };
    const callbacks = (failureKey: string) => ({
      onSuccess: () => { setCategoryDraft(null); },
      onError: (error: unknown) => { toastFailure(error, failureKey); },
    });
    if (draft.id === null) {
      createCategoryMutation.mutate(fields, callbacks('settings.knowledgebase.categoryCreateFailed'));
    } else {
      updateCategoryMutation.mutate({ id: draft.id, ...fields }, callbacks('settings.knowledgebase.categoryUpdateFailed'));
    }
  };

  const deleteCategory = async (category: KnowledgebaseCategory) => {
    const confirmed = await confirmation.confirm({
      title: t('settings.knowledgebase.deleteCategoryTitle'),
      description: t('settings.knowledgebase.deleteCategoryConfirm', { name: category.name, count: category.articles.length }),
      action: t('common.delete'),
      destructive: true,
    });
    if (!confirmed) return;
    deleteCategoryMutation.mutate(category.id, {
      onError: (error) => { toastFailure(error, 'settings.knowledgebase.categoryDeleteFailed'); },
    });
  };

  const openCreateArticle = (categoryId: string) => {
    setArticleDraft({ categoryId, articleId: null, title: '', content: '', isVisible: true });
  };

  const editArticle = (stub: KnowledgebaseArticleStub) => {
    if (loadingArticleId !== null) return;
    setLoadingArticleId(stub.id);
    fetchPanelKnowledgebaseArticle(stub.categoryId, stub.id)
      .then((article) => {
        setArticleDraft({ categoryId: article.categoryId, articleId: article.id, title: article.title, content: article.content, isVisible: article.isVisible });
      })
      .catch((error: unknown) => {
        toastFailure(error, 'settings.knowledgebase.articleLoadFailed');
      })
      .finally(() => {
        setLoadingArticleId(null);
      });
  };

  const saveArticle = (draft: ArticleDraft) => {
    if (draft.title.trim() === '' || draft.content.trim() === '') return;
    const fields = { categoryId: draft.categoryId, title: draft.title, content: draft.content, isVisible: draft.isVisible };
    const callbacks = (failureKey: string) => ({
      onSuccess: () => { setArticleDraft(null); },
      onError: (error: unknown) => { toastFailure(error, failureKey); },
    });
    if (draft.articleId === null) {
      createArticleMutation.mutate(fields, callbacks('settings.knowledgebase.articleCreateFailed'));
    } else {
      updateArticleMutation.mutate({ ...fields, articleId: draft.articleId }, callbacks('settings.knowledgebase.articleUpdateFailed'));
    }
  };

  const deleteArticle = async (article: KnowledgebaseArticleStub) => {
    const confirmed = await confirmation.confirm({
      title: t('settings.knowledgebase.deleteArticleTitle'),
      description: t('settings.knowledgebase.deleteArticleConfirm', { title: article.title }),
      action: t('common.delete'),
      destructive: true,
    });
    if (!confirmed) return;
    deleteArticleMutation.mutate({ categoryId: article.categoryId, articleId: article.id }, {
      onError: (error) => { toastFailure(error, 'settings.knowledgebase.articleDeleteFailed'); },
    });
  };

  if (isLoadingCategories) return <SettingsLoading />;

  return (
    <SettingsDndProvider>
      <SettingsPageAction>
        <SettingsPageActionButton
          icon={Plus}
          label={t('settings.knowledgebase.addCategory')}
          onClick={() => { setCategoryDraft({ id: null, name: '', description: '' }); }}
        />
      </SettingsPageAction>

      {categoryOrder.items.length === 0 ? (
        <p className="py-8 text-center text-sm text-muted-foreground">{t('settings.knowledgebase.noCategories')}</p>
      ) : (
        <div ref={categoryList.ref} className="space-y-3">
          {categoryOrder.items.map((category, index) => (
            <CategoryItem
              key={category.id}
              index={index}
              count={categoryOrder.items.length}
              loadingArticleId={loadingArticleId}
              category={category}
              isSettling={categoryList.isSettling}
              onMove={categoryOrder.move}
              onEdit={(edited) => { setCategoryDraft({ id: edited.id, name: edited.name, description: edited.description ?? '' }); }}
              onDelete={(deleted) => { void deleteCategory(deleted); }}
              onAddArticle={openCreateArticle}
              onEditArticle={editArticle}
              onDeleteArticle={(article) => { void deleteArticle(article); }}
            />
          ))}
        </div>
      )}

      {categoryDraft !== null && (
        <CategoryDialog
          draft={categoryDraft}
          isSaving={createCategoryMutation.isPending || updateCategoryMutation.isPending}
          onChange={setCategoryDraft}
          onSubmit={() => { saveCategory(categoryDraft); }}
          onClose={() => { setCategoryDraft(null); }}
        />
      )}

      {articleDraft !== null && (
        <ArticleDialog
          draft={articleDraft}
          isSaving={createArticleMutation.isPending || updateArticleMutation.isPending}
          onChange={setArticleDraft}
          onSubmit={() => { saveArticle(articleDraft); }}
          onClose={() => { setArticleDraft(null); }}
        />
      )}

      {confirmation.dialog}
    </SettingsDndProvider>
  );
}
