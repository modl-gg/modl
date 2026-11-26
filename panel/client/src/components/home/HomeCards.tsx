import { useId, useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'wouter';
import { ChevronDown, ChevronRight, MessageCircle, Shield, UserPlus } from 'lucide-react';
import { HOME_ROW_ACTION_CLASS, HOME_ROW_CHEVRON_CLASS, HOME_ROW_CLASS, HomeRowBody } from '@/components/home/HomeRow';
import { LoadFailure } from '@/components/ui/load-failure';
import { usePublicHomepageCards, type PublicHomepageCard, type PublicHomepageCardCategory } from '@/hooks/data/homepage';
import { usePublicTheme } from '@/hooks/use-public-theme';
import { homepageIcon, type HomepageIcon } from '@/lib/homepage-icons';
import { cn, safeExternalHref } from '@/lib/utils';
import { articlePath } from '@/utils/routes';

const LOADING_ROW_IDS = [1, 2, 3, 4] as const;

interface DefaultCard {
  icon: HomepageIcon;
  titleKey: string;
  descriptionKey: string;
  href: string;
}

const DEFAULT_CARDS: readonly DefaultCard[] = [
  { icon: Shield, titleKey: 'home.defaults.appealTitle', descriptionKey: 'home.defaults.appealDesc', href: '/appeal' },
  { icon: UserPlus, titleKey: 'home.defaults.applyTitle', descriptionKey: 'home.defaults.applyDesc', href: '/submit-ticket/staff' },
  { icon: MessageCircle, titleKey: 'home.defaults.contactTitle', descriptionKey: 'home.defaults.contactDesc', href: '/submit-ticket/support' },
];

function useAccentIcons(): boolean {
  return usePublicTheme().accentColor !== '';
}

function RowLink({ href, children }: { href: string; children: ReactNode }) {
  if (href.startsWith('/')) {
    return <Link data-modl="card-button" href={href} className={HOME_ROW_ACTION_CLASS}>{children}</Link>;
  }
  return <a data-modl="card-button" href={href} target="_blank" rel="noopener noreferrer" className={HOME_ROW_ACTION_CLASS}>{children}</a>;
}

function LinkRow({ icon, title, description, href }: { icon: HomepageIcon; title: string; description: string; href: string }) {
  const accent = useAccentIcons();
  return (
    <div data-modl="card" className={HOME_ROW_CLASS}>
      <RowLink href={href}>
        <HomeRowBody
          icon={icon}
          title={title}
          description={description}
          accent={accent}
          trailing={<ChevronRight className={cn(HOME_ROW_CHEVRON_CLASS, 'group-hover:translate-x-0.5')} aria-hidden="true" />}
        />
      </RowLink>
    </div>
  );
}

function CategoryArticles({ id, category }: { id: string; category: PublicHomepageCardCategory }) {
  const { t } = useTranslation();
  return (
    <div id={id} data-modl="category-articles" className="border-t border-border/60 py-2 pl-16 pr-4">
      {category.articles.length === 0 ? (
        <p className="px-2 py-2 text-sm text-muted-foreground">{t('home.noArticlesInCategory')}</p>
      ) : (
        <ul data-modl="article-list" className="py-1">
          {category.articles.map((article) => (
            <li key={article.id}>
              <Link
                data-modl="article-link"
                href={articlePath(article.slug)}
                className="block truncate rounded-md px-2 py-1.5 text-sm hover:bg-muted hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
              >
                {article.title}
              </Link>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

function CategoryRow({ card, category }: { card: PublicHomepageCard; category: PublicHomepageCardCategory }) {
  const [expanded, setExpanded] = useState(false);
  const articlesId = useId();
  const accent = useAccentIcons();
  return (
    <div data-modl="card" className={HOME_ROW_CLASS}>
      <button
        type="button"
        data-modl="card-button"
        aria-expanded={expanded}
        aria-controls={articlesId}
        onClick={() => { setExpanded((open) => !open); }}
        className={HOME_ROW_ACTION_CLASS}
      >
        <HomeRowBody
          icon={homepageIcon(card.icon)}
          title={card.title}
          description={card.description}
          accent={accent}
          trailing={<ChevronDown className={cn(HOME_ROW_CHEVRON_CLASS, expanded && 'rotate-180')} aria-hidden="true" />}
        />
      </button>
      {expanded && <CategoryArticles id={articlesId} category={category} />}
    </div>
  );
}

function ConfiguredRow({ card }: { card: PublicHomepageCard }) {
  if (card.actionType === 'category_dropdown') {
    return card.category === undefined ? null : <CategoryRow card={card} category={card.category} />;
  }
  const href = safeExternalHref(card.actionUrl);
  if (href === undefined) return null;
  return <LinkRow icon={homepageIcon(card.icon)} title={card.title} description={card.description} href={href} />;
}

function DefaultRows() {
  const { t } = useTranslation();
  return DEFAULT_CARDS.map((card) => (
    <LinkRow key={card.href} icon={card.icon} title={t(card.titleKey)} description={t(card.descriptionKey)} href={card.href} />
  ));
}

function LoadingRows() {
  return LOADING_ROW_IDS.map((id) => (
    <div key={id} className="flex animate-pulse items-center gap-4 rounded-lg border border-border/60 px-4 py-3.5">
      <div className="h-10 w-10 shrink-0 rounded-md bg-muted" />
      <div className="flex-1 space-y-2">
        <div className="h-4 w-1/3 rounded bg-muted" />
        <div className="h-3 w-2/3 rounded bg-muted" />
      </div>
    </div>
  ));
}

function Rows() {
  const { t } = useTranslation();
  const { data: cards, isError, refetch } = usePublicHomepageCards();
  if (cards === undefined) {
    if (!isError) return <LoadingRows />;
    return <LoadFailure className="md:col-span-2" message={t('common.unknownError')} onRetry={() => { void refetch(); }} />;
  }
  if (cards.length === 0) return <DefaultRows />;
  return cards.map((card) => <ConfiguredRow key={card.id} card={card} />);
}

export function HomeCards() {
  return (
    <div data-modl="card-grid" className="grid grid-cols-1 items-start gap-3 md:grid-cols-2">
      <Rows />
    </div>
  );
}
