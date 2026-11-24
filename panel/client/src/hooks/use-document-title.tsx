import { useEffect } from 'react';
import { useLocation } from 'wouter';
import { useTranslation } from 'react-i18next';
import defaultIcon from '@/assets/modl-favicon.svg';
import { usePublicSettings } from '@/hooks/use-public-settings';

const FAVICON_RELS = ['icon', 'apple-touch-icon'] as const;

const EXACT_PAGE_TITLES: Readonly<Record<string, string>> = {
  '/panel': 'pageTitles.dashboard',
  '/panel/lookup': 'pageTitles.playerLookup',
  '/panel/tickets': 'pageTitles.tickets',
  '/panel/audit': 'pageTitles.audit',
  '/panel/settings': 'pageTitles.settings',
  '/panel/auth': 'pageTitles.staffLogin',
  '/': 'pageTitles.support',
  '/knowledgebase': 'pageTitles.knowledgebase',
  '/appeal': 'pageTitles.appeals',
  '/auth': 'pageTitles.login',
  '/setup': 'pageTitles.setup',
  '/auth/link': 'pageTitles.signIn',
};

const PREFIX_PAGE_TITLES: readonly (readonly [string, string])[] = [
  ['/panel/tickets/', 'pageTitles.ticketDetails'],
  ['/panel/player/', 'pageTitles.playerDetails'],
  ['/panel/settings/', 'pageTitles.settings'],
  ['/ticket/', 'pageTitles.ticket'],
  ['/accept-invitation', 'pageTitles.acceptInvitation'],
  ['/setup/', 'pageTitles.setup'],
  ['/article/', 'pageTitles.knowledgebase'],
  ['/submit-ticket', 'pageTitles.submitTicket'],
  ['/upload-evidence/', 'pageTitles.uploadEvidence'],
  ['/replay', 'pageTitles.replay'],
  ['/reset-password', 'pageTitles.resetPassword'],
];

function pageTitleKey(location: string): string | undefined {
  return EXACT_PAGE_TITLES[location] ?? PREFIX_PAGE_TITLES.find(([prefix]) => location.startsWith(prefix))?.[1];
}

function usesPanelIcon(location: string): boolean {
  return location.startsWith('/panel') || location.startsWith('/setup');
}

function showFavicon(href: string): void {
  for (const rel of FAVICON_RELS) {
    const existing = document.head.querySelector<HTMLLinkElement>(`link[rel="${rel}"]`);
    const link = existing ?? document.head.appendChild(document.createElement('link'));
    link.rel = rel;
    link.removeAttribute('type');
    if (link.getAttribute('href') !== href) link.setAttribute('href', href);
  }
}

export function useDocumentTitle() {
  const { t } = useTranslation();
  const { data: publicSettings, isLoading } = usePublicSettings();
  const [location] = useLocation();
  const panelIconUrl = publicSettings?.panelIconUrl || '';
  const homepageIconUrl = publicSettings?.homepageIconUrl || '';
  const faviconUrl = (usesPanelIcon(location) ? panelIconUrl : homepageIconUrl) || defaultIcon;

  useEffect(() => {
    const serverDisplayName = publicSettings?.serverDisplayName || '';
    const titleKey = pageTitleKey(location);
    const pageName = titleKey ? t(titleKey) : '';

    if (isLoading) {
      document.title = t('common.loading');
    } else if (serverDisplayName) {
      document.title = pageName ? `${pageName} - ${serverDisplayName}` : serverDisplayName;
    } else {
      document.title = pageName || t('pageTitles.panel');
    }
  }, [publicSettings, location, isLoading, t]);

  useEffect(() => {
    showFavicon(faviconUrl);
  }, [faviconUrl]);

  return {
    serverDisplayName: publicSettings?.serverDisplayName || '',
    panelIconUrl,
    homepageIconUrl,
  };
}
