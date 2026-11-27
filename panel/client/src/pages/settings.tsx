import { Suspense } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, Redirect, useParams, useSearch } from 'wouter';
import { ChevronLeft } from 'lucide-react';
import PageContainer from '@/components/layout/PageContainer';
import { SETTINGS_PAGE_VIEWS } from '@/components/settings/settings-pages';
import { SettingsLoading } from '@/components/settings/layout/SettingsSections';
import { LoadFailure } from '@/components/ui/load-failure';
import { SettingsPageFrame } from '@/components/settings/layout/SettingsPageFrame';
import { SettingsSaveScope } from '@/components/settings/save/settings-save-scope';
import { useIsMobile } from '@/hooks/use-mobile';
import { usePermissions } from '@/hooks/use-permissions';
import {
  SETTINGS_ROOT_PATH,
  accessibleSettingsGroups,
  canModifySettingsPage,
  legacySettingsPage,
  resolveSettingsPage,
  settingsPagePath,
  type AccessibleSettingsGroup,
  type SettingsGroupId,
  type SettingsPageId,
} from '@/lib/settings-access';
import { cn } from '@/lib/utils';
import { SETTINGS_PAGE_HELP } from '@/lib/wiki-help';

const GROUP_TITLE_KEYS: Record<SettingsGroupId, string> = {
  account: 'settings.groups.account',
  general: 'settings.groups.general',
  staff: 'settings.groups.staff',
  moderation: 'settings.groups.moderation',
  tickets: 'settings.groups.tickets',
  'public-site': 'settings.groups.publicSite',
  system: 'settings.groups.system',
};

const PAGE_TITLE_KEYS: Record<SettingsPageId, string> = {
  profile: 'settings.pages.profile',
  security: 'settings.pages.security',
  panel: 'settings.pages.panel',
  'minecraft-servers': 'settings.pages.minecraftServers',
  email: 'settings.pages.email',
  webhooks: 'settings.pages.webhooks',
  members: 'settings.pages.members',
  roles: 'settings.pages.roles',
  'sign-in': 'settings.pages.signIn',
  'punishment-types': 'settings.pages.punishmentTypes',
  escalation: 'settings.pages.escalation',
  'player-addresses': 'settings.pages.playerAddresses',
  replays: 'settings.pages.replays',
  forms: 'settings.pages.forms',
  labels: 'settings.pages.labels',
  'quick-replies': 'settings.pages.quickReplies',
  'ai-moderation': 'settings.pages.aiModeration',
  homepage: 'settings.pages.homepage',
  knowledgebase: 'settings.pages.knowledgebase',
  theme: 'settings.pages.theme',
  'custom-css': 'settings.pages.customCss',
  storage: 'settings.pages.storage',
  database: 'settings.pages.database',
  'data-retention': 'settings.pages.dataRetention',
  updates: 'settings.pages.updates',
  status: 'settings.pages.status',
};

interface SettingsNavProps {
  groups: AccessibleSettingsGroup[];
  activePage: SettingsPageId | null;
  touch: boolean;
}

function SettingsNav({ groups, activePage, touch }: SettingsNavProps) {
  const { t } = useTranslation();

  return (
    <nav aria-label={t('settings.page.title')} className={touch ? 'space-y-5' : 'space-y-2.5'}>
      {groups.map((group) => (
        <div key={group.id} className="space-y-0.5">
          <h3 className="px-3 text-sm font-semibold text-foreground">{t(GROUP_TITLE_KEYS[group.id])}</h3>
          <ul>
            {group.pages.map((pageId) => {
              const active = pageId === activePage;
              return (
                <li key={pageId}>
                  <Link
                    href={settingsPagePath(pageId)}
                    aria-current={active ? 'page' : undefined}
                    className={cn(
                      'flex items-center rounded-md pl-6 pr-3 text-sm transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring',
                      touch ? 'h-11' : 'h-6.5',
                      active ? 'bg-muted font-medium text-foreground' : 'text-muted-foreground hover:bg-muted/50 hover:text-foreground',
                    )}
                  >
                    {t(PAGE_TITLE_KEYS[pageId])}
                  </Link>
                </li>
              );
            })}
          </ul>
        </div>
      ))}
    </nav>
  );
}

function SettingsPageView({ pageId, canModify }: { pageId: SettingsPageId; canModify: boolean }) {
  const { t } = useTranslation();
  const PageContent = SETTINGS_PAGE_VIEWS[pageId];

  return (
    <div className="flex min-h-[calc(100dvh-8rem)] min-w-0 flex-1 flex-col">
      <SettingsPageFrame key={pageId} title={t(PAGE_TITLE_KEYS[pageId])} help={SETTINGS_PAGE_HELP[pageId]} canModify={canModify}>
        <SettingsSaveScope>
          <Suspense fallback={<SettingsLoading />}>
            <PageContent />
          </Suspense>
        </SettingsSaveScope>
      </SettingsPageFrame>
    </div>
  );
}

function BackToSettings() {
  const { t } = useTranslation();
  return (
    <Link href={SETTINGS_ROOT_PATH} className="mb-4 inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground">
      <ChevronLeft className="h-4 w-4" aria-hidden="true" />
      {t('settings.page.title')}
    </Link>
  );
}

const Settings = () => {
  const { t } = useTranslation();
  const { page } = useParams<{ page?: string }>();
  const search = useSearch();
  const isMobile = useIsMobile();
  const { settingsAccess, isPermissionsLoading, isPermissionsError, retryPermissions } = usePermissions();

  const legacyPage = page === undefined ? legacySettingsPage(search) : null;
  if (legacyPage !== null) return <Redirect to={settingsPagePath(legacyPage)} replace />;
  if (isPermissionsError) return <PageContainer><LoadFailure message={t('common.unknownError')} onRetry={retryPermissions} /></PageContainer>;
  if (isPermissionsLoading) return <SettingsLoading />;

  const resolution = page === undefined && isMobile ? null : resolveSettingsPage(page, settingsAccess);
  if (resolution?.kind === 'redirect') return <Redirect to={settingsPagePath(resolution.pageId)} replace />;

  const groups = accessibleSettingsGroups(settingsAccess);
  const activePage = resolution?.pageId ?? null;

  return (
    <PageContainer>
      <div className={isMobile ? 'flex items-start' : 'grid grid-cols-[minmax(13rem,1fr)_minmax(0,58rem)_minmax(0,1fr)] items-start gap-10 pr-24'}>
        {!isMobile && (
          <aside className="sticky top-14 max-h-[calc(100dvh-5rem)] w-52 shrink-0 justify-self-end overflow-y-auto">
            <SettingsNav groups={groups} activePage={activePage} touch={false} />
          </aside>
        )}
        {activePage === null && (
          <div className="min-w-0 flex-1">
            <h2 className="mb-6 text-xl font-semibold">{t('settings.page.title')}</h2>
            <SettingsNav groups={groups} activePage={null} touch />
          </div>
        )}
        {activePage !== null && (
          <div className="flex min-w-0 flex-1 flex-col">
            {isMobile && <BackToSettings />}
            <SettingsPageView pageId={activePage} canModify={canModifySettingsPage(activePage, settingsAccess)} />
          </div>
        )}
      </div>
    </PageContainer>
  );
};

export default Settings;
