import React, { createElement, useState } from 'react';
import { articlePath } from '@/utils/routes';
import { Link } from 'wouter';
import {
  Search, Shield, MessageCircle, UserPlus, FileText, BookOpen, ChevronDown, Sun, Moon, Loader2,
  Mail, Phone, Scale, Users, User, UserCheck, UserX, Crown, Award,
  Book, ScrollText, Newspaper, Library, GraduationCap, HelpCircle, Info, AlertCircle,
  ExternalLink, Link as LinkIcon, ArrowRight, ChevronRight, Home,
  Eye, Download, Upload, Share,
  MessageSquare, Send, Inbox, Bell, Megaphone,
  Radio, Headphones, Mic, Video, Calendar,
  Gamepad2, Zap, Server, Globe, Wifi, Signal,
  Activity, BarChart, TrendingUp, Target, Trophy,
  Settings, Wrench, Cog, Sliders, Filter,
  Lock, Unlock, Key, ShieldCheck, ShieldAlert,
  Plus, Minus, Check, X, AlertTriangle, CheckCircle,
  XCircle, Clock, Timer, Pause, Play,
  CreditCard, DollarSign, Gift, Star, Heart, ThumbsUp,
  Flag, Map as MapIcon, Compass, Navigation, Bookmark, Tag,
} from 'lucide-react';
import { Button } from '@/components/ui/button';
import { LoadFailure } from '@/components/ui/load-failure';
import { Input } from '@/components/ui/input';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Collapsible, CollapsibleTrigger } from '@/components/ui/collapsible';
import { BrandLogo } from '@/components/brand/BrandLogo';
import { useAuth } from '@/hooks/use-auth';
import { usePublicColorScheme, usePublicTheme } from '@/hooks/use-public-theme';
import { usePublicHomepageCards, type PublicHomepageCard, type PublicHomepageCardCategory } from '@/hooks/data/homepage';
import { MIN_KNOWLEDGEBASE_SEARCH_LENGTH, usePublicKnowledgebaseSearch } from '@/hooks/data/knowledgebase';
import { useDebouncedValue } from '@/hooks/use-debounced-value';
import { openExternalUrl, safeExternalHref } from '@/lib/utils';
import { useTranslation } from 'react-i18next';

type HomepageIcon = React.ComponentType<{ className?: string; style?: React.CSSProperties; 'data-modl'?: string }>;

const HOMEPAGE_ICONS = new Map<string, HomepageIcon>(Object.entries({
  Shield, UserPlus, MessageCircle, Mail, Phone, Scale,
  Users, User, UserCheck, UserX, Crown, Award,
  BookOpen, Book, FileText, ScrollText, Newspaper,
  Library, GraduationCap, HelpCircle, Info, AlertCircle,
  ExternalLink, Link: LinkIcon, ArrowRight, ChevronRight, Home,
  Search, Eye, Download, Upload, Share,
  MessageSquare, Send, Inbox, Bell, Megaphone,
  Radio, Headphones, Mic, Video, Calendar,
  Gamepad2, Zap, Server, Globe, Wifi, Signal,
  Activity, BarChart, TrendingUp, Target, Trophy,
  Settings, Wrench, Cog, Sliders, Filter,
  Lock, Unlock, Key, ShieldCheck, ShieldAlert,
  Plus, Minus, Check, X, AlertTriangle, CheckCircle,
  XCircle, Clock, Timer, Pause, Play,
  CreditCard, DollarSign, Gift, Star, Heart, ThumbsUp,
  Flag, Map: MapIcon, Compass, Navigation, Bookmark, Tag,
}));

const CARD_BODY_CLASS = 'p-6 h-full flex flex-col justify-between gap-4';
const CARD_TITLE_CLASS = 'text-base font-semibold';
const CARD_TEXT_CLASS = 'mt-1 text-sm text-muted-foreground';
const LOADING_CARD_IDS = [1, 2, 3, 4, 5, 6] as const;

function useCardIconColor(): string {
  return usePublicTheme().accentColor === '' ? 'text-muted-foreground' : 'text-accent';
}

function HomepageCardIcon({ name, className }: { name: string; className: string }) {
  const color = useCardIconColor();
  return createElement(HOMEPAGE_ICONS.get(name) ?? BookOpen, { className: `${className} ${color}`, 'data-modl': 'card-icon' });
}

interface DefaultHomeCardDefinition {
  icon: React.ComponentType<{ className?: string }>;
  titleKey: string;
  descriptionKey: string;
  actionKey: string;
  href: string;
}

const DEFAULT_HOME_CARDS: readonly DefaultHomeCardDefinition[] = [
  { icon: Shield, titleKey: 'home.defaults.appealTitle', descriptionKey: 'home.defaults.appealDesc', actionKey: 'home.defaults.appealAction', href: '/appeal' },
  { icon: UserPlus, titleKey: 'home.defaults.applyTitle', descriptionKey: 'home.defaults.applyDesc', actionKey: 'home.defaults.applyAction', href: '/submit-ticket/staff' },
  { icon: MessageCircle, titleKey: 'home.defaults.contactTitle', descriptionKey: 'home.defaults.contactDesc', actionKey: 'home.defaults.contactAction', href: '/submit-ticket/support' },
];

function DefaultHomeCard({ card }: { card: DefaultHomeCardDefinition }) {
  const { t } = useTranslation();
  const Icon = card.icon;
  const iconColor = useCardIconColor();
  return (
    <Card data-modl="card" className="h-full shadow-card">
      <CardContent className={CARD_BODY_CLASS}>
        <div className="text-center">
          <Icon data-modl="card-icon" className={`h-6 w-6 mx-auto mb-3 ${iconColor}`} aria-hidden="true" />
          <h3 data-modl="card-title" className={CARD_TITLE_CLASS}>{t(card.titleKey)}</h3>
          <p data-modl="card-description" className={CARD_TEXT_CLASS}>{t(card.descriptionKey)}</p>
        </div>
        <Button data-modl="card-button" asChild variant="outline" size="sm" className="w-full">
          <Link href={card.href}>{t(card.actionKey)}</Link>
        </Button>
      </CardContent>
    </Card>
  );
}

interface HomepageCardViewProps {
  card: PublicHomepageCard;
  isExpanded: boolean;
  onToggleExpanded: (cardId: string) => void;
}

function CategoryDropdownCard({ card, category, isExpanded, onToggleExpanded }: HomepageCardViewProps & { category: PublicHomepageCardCategory }) {
  const { t } = useTranslation();
  return (
    <Card data-modl="card" className="h-full shadow-card">
      <Collapsible open={isExpanded} onOpenChange={() => onToggleExpanded(card.id)} className="h-full">
        <CardContent className={CARD_BODY_CLASS}>
          <div className="text-center">
            <HomepageCardIcon name={card.icon} className="h-6 w-6 mx-auto mb-3" />
            <h3 data-modl="card-title" className={CARD_TITLE_CLASS}>{card.title}</h3>
            <p data-modl="card-description" className={CARD_TEXT_CLASS}>{card.description}</p>
          </div>

          <CollapsibleTrigger asChild>
            <Button data-modl="card-button" variant="outline" size="sm" className="w-full" disabled={category.articles.length === 0}>
              <span>{t('home.articleCount', { count: category.articles.length })}</span>
              <ChevronDown className={`ml-2 h-4 w-4 transition-transform ${isExpanded ? 'rotate-180' : ''}`} aria-hidden="true" />
            </Button>
          </CollapsibleTrigger>
        </CardContent>
      </Collapsible>
    </Card>
  );
}

function LinkCard({ card }: { card: PublicHomepageCard }) {
  const { t } = useTranslation();
  const buttonText = card.actionButtonText || t('home.learnMore');
  const safeUrl = safeExternalHref(card.actionUrl);

  return (
    <Card data-modl="card" className="h-full shadow-card">
      <CardContent className={CARD_BODY_CLASS}>
        <div className="text-center">
          <HomepageCardIcon name={card.icon} className="h-6 w-6 mx-auto mb-3" />
          <h3 className={CARD_TITLE_CLASS}>{card.title}</h3>
          <p className={CARD_TEXT_CLASS}>{card.description}</p>
        </div>
        {safeUrl?.startsWith('/') === true ? (
          <Button data-modl="card-button" asChild variant="outline" size="sm" className="w-full">
            <Link href={safeUrl}>{buttonText}</Link>
          </Button>
        ) : (
          <Button
            data-modl="card-button"
            variant="outline"
            size="sm"
            className="w-full"
            onClick={() => openExternalUrl(card.actionUrl)}
          >
            {buttonText}
          </Button>
        )}
      </CardContent>
    </Card>
  );
}

function HomepageCardView({ card, isExpanded, onToggleExpanded }: HomepageCardViewProps) {
  if (card.actionType === 'category_dropdown' && card.category) {
    return (
      <CategoryDropdownCard
        card={card}
        category={card.category}
        isExpanded={isExpanded}
        onToggleExpanded={onToggleExpanded}
      />
    );
  }
  return <LinkCard card={card} />;
}

function ExpandedCategorySection({ card, category }: { card: PublicHomepageCard; category: PublicHomepageCardCategory }) {
  const { t } = useTranslation();
  return (
    <Card data-modl="category-articles" className="shadow-card text-left">
      <CardHeader>
        <CardTitle>{card.title}</CardTitle>
      </CardHeader>
      <CardContent>
        <ul data-modl="article-list" className="grid grid-cols-1 md:grid-cols-2 gap-1">
          {category.articles.map(article => (
            <li key={article.id}>
              <Link data-modl="article-link" href={articlePath(article.slug)} className="block truncate rounded-md px-2 py-1.5 text-sm hover:bg-muted hover:underline">
                {article.title}
              </Link>
            </li>
          ))}
        </ul>
        {category.articles.length === 0 && (
          <p className="py-8 text-center text-sm text-muted-foreground">{t('home.noArticlesInCategory')}</p>
        )}
      </CardContent>
    </Card>
  );
}

function LoadingCard() {
  return (
    <Card className="animate-pulse h-48">
      <CardContent className="p-6 h-full flex flex-col">
        <div className="w-6 h-6 bg-muted rounded-md mx-auto mb-3"></div>
        <div className="h-6 bg-muted rounded w-3/4 mx-auto mb-3"></div>
        <div className="h-4 bg-muted rounded w-full mb-2"></div>
        <div className="h-4 bg-muted rounded w-2/3 mx-auto mb-4"></div>
        <div className="h-8 bg-muted rounded w-full mt-auto"></div>
      </CardContent>
    </Card>
  );
}

const SEARCH_DEBOUNCE_MS = 300;

const HomePage: React.FC = () => {
  const { t } = useTranslation();
  const [searchTerm, setSearchTerm] = useState('');
  const [expandedCards, setExpandedCards] = useState<Set<string>>(() => new Set());
  const { scheme, toggle: toggleScheme } = usePublicColorScheme();
  const { user } = useAuth();
  const { data: homepageCardsData, isPending: isLoading, isError: cardsError, refetch: refetchCards } = usePublicHomepageCards();
  const homepageCards = homepageCardsData ?? [];
  const cardsFailed = cardsError && homepageCardsData === undefined;

  const isSearchActive = searchTerm.trim().length >= MIN_KNOWLEDGEBASE_SEARCH_LENGTH;
  const debouncedSearchTerm = useDebouncedValue(searchTerm, SEARCH_DEBOUNCE_MS);
  const searchQuery = usePublicKnowledgebaseSearch(isSearchActive ? debouncedSearchTerm : '');
  const isSearching = isSearchActive && (searchTerm !== debouncedSearchTerm || searchQuery.isFetching);
  const searchResults = searchQuery.data ?? [];
  const searchFailed = searchQuery.isError && searchQuery.data === undefined;

  const toggleExpanded = (cardId: string) => {
    setExpandedCards(prev => {
      const newSet = new Set(prev);
      if (newSet.has(cardId)) {
        newSet.delete(cardId);
      } else {
        newSet.add(cardId);
      }
      return newSet;
    });
  };

  const expandedCategories = homepageCards.flatMap((card) =>
    card.actionType === 'category_dropdown' && card.category && expandedCards.has(card.id)
      ? [{ card, category: card.category }]
      : [],
  );

  return (
    <div className="min-h-screen">
      <div data-modl="home-actions" className="absolute top-4 right-4 z-10 flex items-center gap-1 sm:gap-2">
        <Button
          data-modl="theme-toggle"
          variant="secondary"
          size="sm"
          className="bg-card/80 hover:bg-card/90 text-foreground border-muted p-2 sm:px-3"
          onClick={toggleScheme}
          aria-label={t('home.toggleTheme')}
          title={t('home.toggleTheme')}
        >
          {scheme === 'dark' ? <Sun className="h-4 w-4" aria-hidden="true" /> : <Moon className="h-4 w-4" aria-hidden="true" />}
        </Button>

        <Button data-modl="staff-link" asChild variant="secondary" size="sm" className="bg-card/80 hover:bg-card/90 text-foreground border-muted text-xs sm:text-sm">
          <Link href={user ? "/panel" : "/panel/auth"}>
            <span className="hidden sm:inline">{user ? t('home.goToPanel') : t('home.staffLogin')}</span>
            <span className="sm:hidden">{user ? t('home.panelShort') : t('home.loginShort')}</span>
          </Link>
        </Button>
      </div>

      <section data-modl="hero" className="py-8 sm:py-12 px-4">
        <div className="max-w-6xl mx-auto text-center">
          <div data-modl="logo" className="w-24 h-24 sm:w-32 sm:h-32 mx-auto mb-6">
            <BrandLogo icon="homepage" wordmarkClassName="flex h-full w-full items-center justify-center text-3xl sm:text-4xl" imageClassName="w-full h-full object-contain" />
          </div>
          
          <div className="max-w-xl mx-auto mb-4 px-2">
            <div className="relative">
              <Search className="absolute left-3 top-1/2 transform -translate-y-1/2 text-muted-foreground h-4 w-4" aria-hidden="true" />
              <Input
                data-modl="search"
                type="search"
                aria-label={t('home.searchPlaceholder')}
                placeholder={t('home.searchPlaceholder')}
                value={searchTerm}
                onChange={(e) => setSearchTerm(e.target.value)}
                className="pl-9"
              />
            </div>

            {isSearchActive && (
              <Card data-modl="search-results" className="mt-3 text-left max-h-48 overflow-y-auto">
                <CardContent className="p-3">
                  {isSearching ? (
                    <div className="flex justify-center py-1" role="status">
                      <Loader2 className="h-4 w-4 animate-spin text-muted-foreground" aria-label={t('home.searching')} />
                    </div>
                  ) : searchFailed ? (
                    <LoadFailure className="py-1" message={t('search.failed')} onRetry={() => { void searchQuery.refetch(); }} />
                  ) : searchResults.length > 0 ? (
                    <div className="space-y-1">
                      {searchResults.map(article => (
                        <Link data-modl="article-link" key={article.id} href={articlePath(article.slug)}>
                          <div className="p-2 rounded hover:bg-muted/50 transition-colors cursor-pointer">
                            <p className="text-sm font-medium text-foreground hover:underline">{article.title}</p>
                          </div>
                        </Link>
                      ))}
                    </div>
                  ) : (
                    <p className="text-center text-muted-foreground text-sm" role="status">
                      {t('home.noArticlesFound', { query: searchTerm })}
                    </p>
                  )}
                </CardContent>
              </Card>
            )}
          </div>

          <div className="max-w-6xl mx-auto mt-8 px-2">
            <div data-modl="card-grid" className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4 sm:gap-6">
              {isLoading ? (
                LOADING_CARD_IDS.map((cardId) => <LoadingCard key={cardId} />)
              ) : cardsFailed ? (
                <LoadFailure className="col-span-full" message={t('common.unknownError')} onRetry={() => { void refetchCards(); }} />
              ) : homepageCards.length > 0 ? (
                homepageCards.map((card) => (
                  <HomepageCardView
                    key={card.id}
                    card={card}
                    isExpanded={expandedCards.has(card.id)}
                    onToggleExpanded={toggleExpanded}
                  />
                ))
              ) : (
                DEFAULT_HOME_CARDS.map((card) => <DefaultHomeCard key={card.titleKey} card={card} />)
              )}
            </div>

            <div className="mt-6 space-y-4">
              {expandedCategories.map(({ card, category }) => (
                <ExpandedCategorySection key={`expanded-${card.id}`} card={card} category={category} />
              ))}
            </div>
          </div>
        </div>
      </section>
    </div>
  );
};

export default HomePage;
