import React, { useState } from 'react';
import { articlePath } from '@/utils/routes';
import { Link } from 'wouter';
import SearchBar from '@/components/knowledgebase/SearchBar';
import CategoryDisplay from '@/components/knowledgebase/CategoryDisplay';
import PageContainer from '@/components/layout/PageContainer';
import { useTranslation } from 'react-i18next';
import {
  MIN_KNOWLEDGEBASE_SEARCH_LENGTH,
  usePublicKnowledgebaseCategories,
  usePublicKnowledgebaseSearch,
} from '@/hooks/data/knowledgebase';
import { useDebouncedValue } from '@/hooks/use-debounced-value';
import { describeError } from '@/lib/http-error';
import { LoadFailure } from '@/components/ui/load-failure';
import { LoadingSpinner } from '@/components/ui/loading-spinner';

const SEARCH_DEBOUNCE_MS = 300;

const KnowledgebasePage: React.FC = () => {
  const { t } = useTranslation();
  const [searchTerm, setSearchTerm] = useState('');
  const categoriesQuery = usePublicKnowledgebaseCategories();
  const isSearchActive = searchTerm.trim().length >= MIN_KNOWLEDGEBASE_SEARCH_LENGTH;
  const debouncedSearchTerm = useDebouncedValue(searchTerm, SEARCH_DEBOUNCE_MS);
  const searchQuery = usePublicKnowledgebaseSearch(isSearchActive ? debouncedSearchTerm : '');
  const isSearching = isSearchActive && (searchTerm !== debouncedSearchTerm || searchQuery.isFetching);
  const searchResults = isSearchActive ? searchQuery.data ?? [] : [];
  const searchFailed = isSearchActive && searchQuery.isError && searchQuery.data === undefined;

  if (categoriesQuery.isPending) {
    return (
      <PageContainer>
        <div className="flex justify-center py-16" role="status" aria-label={t('pages.knowledgebase.loading')}>
          <LoadingSpinner className="h-6 w-6 text-primary" />
        </div>
      </PageContainer>
    );
  }

  if (categoriesQuery.isError) {
    const error = describeError(categoriesQuery.error, t('pages.knowledgebase.loadFailed'));
    return <PageContainer><p className="py-16 text-center text-sm text-destructive-text">{t('pages.knowledgebase.loadError', { error })}</p></PageContainer>;
  }

  const categories = categoriesQuery.data.filter((category) => category.articles.length > 0);

  return (
    <PageContainer>
      <div className="mx-auto max-w-3xl space-y-8">
        <div className="space-y-4">
          <h1 data-modl="page-title" className="text-xl font-semibold">{t('pages.knowledgebase.title')}</h1>
          <SearchBar searchTerm={searchTerm} onSearchTermChange={setSearchTerm} />
        </div>

        {isSearching && (
          <div className="flex justify-center py-8" role="status" aria-label={t('home.searching')}>
            <LoadingSpinner className="h-5 w-5 text-muted-foreground" />
          </div>
        )}

        {isSearchActive && !isSearching && searchResults.length > 0 && (
          <section className="space-y-2">
            <h2 className="text-base font-semibold">{t('pages.knowledgebase.searchResults')}</h2>
            <ul data-modl="article-list" className="space-y-1">
              {searchResults.map(article => (
                <li key={article.id}>
                  <Link data-modl="article-link" href={articlePath(article.slug)} className="text-sm text-primary hover:underline">
                    {article.title}
                  </Link>
                </li>
              ))}
            </ul>
          </section>
        )}

        {searchFailed && !isSearching && (
          <LoadFailure message={t('search.failed')} onRetry={() => { void searchQuery.refetch(); }} />
        )}

        {isSearchActive && !isSearching && !searchFailed && searchResults.length === 0 && (
          <p className="py-8 text-center text-sm text-muted-foreground">{t('pages.knowledgebase.noArticlesFound', { searchTerm })}</p>
        )}

        {!isSearchActive && categories.length === 0 && (
          <p className="py-8 text-center text-sm text-muted-foreground">{t('pages.knowledgebase.noCategories')}</p>
        )}

        {!isSearchActive && categories.map(category => (
          <CategoryDisplay
            key={category.id}
            name={category.name}
            slug={category.slug}
            description={category.description}
            articles={category.articles}
          />
        ))}
      </div>
    </PageContainer>
  );
};

export default KnowledgebasePage;
