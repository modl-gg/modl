import React from 'react';
import { useTranslation } from 'react-i18next';
import { useRoute, Link } from 'wouter';
import PageContainer from '@/components/layout/PageContainer';
import ArticleDisplay from '@/components/knowledgebase/ArticleDisplay';
import { usePublicKnowledgebaseArticle } from '@/hooks/data/knowledgebase';
import { describeError } from '@/lib/http-error';
import { LoadingSpinner } from '@/components/ui/loading-spinner';

const ArticleDetailPage: React.FC = () => {
  const { t } = useTranslation();
  const [, params] = useRoute("/article/:articleSlug");
  const articleQuery = usePublicKnowledgebaseArticle(params?.articleSlug);

  if (articleQuery.isPending) {
    return (
      <PageContainer>
        <div className="flex justify-center py-16" role="status" aria-label={t('article.loading')}>
          <LoadingSpinner className="h-6 w-6 text-primary" />
        </div>
      </PageContainer>
    );
  }

  if (articleQuery.isError) {
    const message = describeError(articleQuery.error, t('article.loadFailed'));
    return <PageContainer><p className="py-16 text-center text-sm text-destructive">{t('article.error', { message })}</p></PageContainer>;
  }

  const article = articleQuery.data;
  if (article === null) {
    return <PageContainer><p className="py-16 text-center text-sm text-muted-foreground">{t('article.notFound')}</p></PageContainer>;
  }

  return (
    <PageContainer>
      <div className="mx-auto max-w-3xl">
        <div className="mb-6">
          <Link data-modl="back-link" href="/" className="text-sm text-muted-foreground hover:text-foreground hover:underline">{t('article.backToHome')}</Link>
        </div>

        <ArticleDisplay title={article.title} content={article.content} updatedAt={article.updatedAt} />
      </div>
    </PageContainer>
  );
};

export default ArticleDetailPage;
