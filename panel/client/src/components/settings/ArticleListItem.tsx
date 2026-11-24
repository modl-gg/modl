import { useTranslation } from 'react-i18next';
import { EyeOff, Pencil, Trash2 } from 'lucide-react';
import { GroupRow } from '@/components/ui/group-list';
import { IconAction } from '@/components/ui/icon-action';
import { useSortable } from '@/hooks/use-sortable';
import { useSettingsPageCanModify } from './layout/SettingsPageFrame';
import type { KnowledgebaseArticleStub } from '@/types/knowledgebase';

const ARTICLE_DRAG_TYPE = 'article';

interface ArticleListItemProps {
  article: KnowledgebaseArticleStub;
  index: number;
  count: number;
  editPending: boolean;
  categoryId: string;
  categoryName: string;
  isSettling: () => boolean;
  onMove: (dragIndex: number, hoverIndex: number) => void;
  onEdit: (article: KnowledgebaseArticleStub) => void;
  onDelete: (article: KnowledgebaseArticleStub) => void;
}

export default function ArticleListItem({ article, index, count, editPending, categoryId, categoryName, isSettling, onMove, onEdit, onDelete }: ArticleListItemProps) {
  const { t } = useTranslation();
  const canModify = useSettingsPageCanModify();
  const sortable = useSortable<HTMLDivElement>({
    type: ARTICLE_DRAG_TYPE,
    id: article.id,
    label: article.title,
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
      title={article.title}
      meta={!article.isVisible && (
        <EyeOff className="h-4 w-4 shrink-0 text-muted-foreground" role="img" aria-label={t('settings.knowledgebase.hiddenArticle')} />
      )}
      actions={canModify && (
        <>
          <IconAction icon={Pencil} label={t('settings.knowledgebase.editArticle')} disabled={editPending} onClick={() => { onEdit(article); }} />
          <IconAction icon={Trash2} label={t('settings.knowledgebase.deleteArticleTitle')} destructive onClick={() => { onDelete(article); }} />
        </>
      )}
    />
  );
}
