import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'wouter';
import { Loader2, Moon, Search, Sun } from 'lucide-react';
import { HomeCards } from '@/components/home/HomeCards';
import { HomeFooter } from '@/components/home/HomeFooter';
import { HomeHero } from '@/components/home/HomeHero';
import { PublicFooterContent } from '@/components/layout/PublicFooterSlot';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { LoadFailure } from '@/components/ui/load-failure';
import { usePublicHomepage } from '@/hooks/data/homepage-settings';
import { MIN_KNOWLEDGEBASE_SEARCH_LENGTH, usePublicKnowledgebaseSearch } from '@/hooks/data/knowledgebase';
import { useAuth } from '@/hooks/use-auth';
import { useDebouncedValue } from '@/hooks/use-debounced-value';
import { usePublicColorScheme } from '@/hooks/use-public-theme';
import { articlePath } from '@/utils/routes';

const SEARCH_DEBOUNCE_MS = 300;
const CORNER_BUTTON_CLASS = 'bg-card/80 hover:bg-card/90 text-foreground border-muted';

function HomeActions() {
  const { t } = useTranslation();
  const { scheme, toggle } = usePublicColorScheme();
  const { user } = useAuth();
  return (
    <div data-modl="home-actions" className="absolute right-4 top-4 z-10 flex items-center gap-1 sm:gap-2">
      <Button
        data-modl="theme-toggle"
        variant="secondary"
        size="sm"
        className={`${CORNER_BUTTON_CLASS} p-2 sm:px-3`}
        onClick={toggle}
        aria-label={t('home.toggleTheme')}
        title={t('home.toggleTheme')}
      >
        {scheme === 'dark' ? <Sun className="h-4 w-4" aria-hidden="true" /> : <Moon className="h-4 w-4" aria-hidden="true" />}
      </Button>
      <Button data-modl="staff-link" asChild variant="secondary" size="sm" className={`${CORNER_BUTTON_CLASS} text-xs sm:text-sm`}>
        <Link href={user ? '/panel' : '/panel/auth'}>
          <span className="hidden sm:inline">{user ? t('home.goToPanel') : t('home.staffLogin')}</span>
          <span className="sm:hidden">{user ? t('home.panelShort') : t('home.loginShort')}</span>
        </Link>
      </Button>
    </div>
  );
}

function SearchResults({ term }: { term: string }) {
  const { t } = useTranslation();
  const debouncedTerm = useDebouncedValue(term, SEARCH_DEBOUNCE_MS);
  const query = usePublicKnowledgebaseSearch(debouncedTerm);
  const results = query.data ?? [];
  const searching = term !== debouncedTerm || query.isFetching;

  const body = () => {
    if (searching) {
      return (
        <div className="flex justify-center py-2" role="status">
          <Loader2 className="h-4 w-4 animate-spin text-muted-foreground" aria-label={t('home.searching')} />
        </div>
      );
    }
    if (query.isError && query.data === undefined) {
      return <LoadFailure className="py-1" message={t('search.failed')} onRetry={() => { void query.refetch(); }} />;
    }
    if (results.length === 0) {
      return <p className="py-2 text-center text-sm text-muted-foreground" role="status">{t('home.noArticlesFound', { query: term })}</p>;
    }
    return (
      <ul className="space-y-0.5">
        {results.map((article) => (
          <li key={article.id}>
            <Link
              data-modl="article-link"
              href={articlePath(article.slug)}
              className="block truncate rounded-md px-3 py-2 text-sm font-medium hover:bg-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
            >
              {article.title}
            </Link>
          </li>
        ))}
      </ul>
    );
  };

  return (
    <div data-modl="search-results" className="mt-2 max-h-60 overflow-y-auto rounded-lg border bg-card p-1.5 text-left shadow-md">
      {body()}
    </div>
  );
}

function HomeSearch() {
  const { t } = useTranslation();
  const [term, setTerm] = useState('');
  const active = term.trim().length >= MIN_KNOWLEDGEBASE_SEARCH_LENGTH;
  return (
    <div className="mx-auto w-full max-w-xl">
      <div className="relative">
        <Search className="pointer-events-none absolute left-3.5 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" aria-hidden="true" />
        <Input
          data-modl="search"
          type="search"
          aria-label={t('home.searchPlaceholder')}
          placeholder={t('home.searchPlaceholder')}
          value={term}
          onChange={(event) => { setTerm(event.target.value); }}
          className="h-11 bg-card/80 pl-10 shadow-sm"
        />
      </div>
      {active && <SearchResults term={term} />}
    </div>
  );
}

export default function HomePage() {
  const { data: homepage } = usePublicHomepage();
  return (
    <div className="relative isolate min-h-screen">
      <HomeActions />
      <div className="mx-auto w-full max-w-[960px] px-4">
        <section data-modl="hero" className="pb-10 pt-24 sm:pb-12 sm:pt-32">
          <HomeHero homepage={homepage} />
          <div className="mt-10">
            <HomeSearch />
          </div>
        </section>
        <section className="pb-8">
          <HomeCards />
        </section>
      </div>
      <PublicFooterContent>
        <HomeFooter />
      </PublicFooterContent>
    </div>
  );
}
