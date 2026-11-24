import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { RefreshCw } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { StatusBanner } from '@/components/ui/status-banner';
import { ReportTabs } from '@/components/report/ReportTabs';
import { ReportQueryGate, type ReportQuery } from '@/components/report/ReportQueryGate';
import PageContainer from '@/components/layout/PageContainer';
import { PermissionWrapper } from '@/components/PermissionWrapper';
import { AuditPeriodSelect } from '@/components/audit/AuditPeriodSelect';
import { AuditOverviewSection, type AuditOverviewSectionProps } from '@/components/audit/AuditOverviewSection';
import { AuditTicketsSection } from '@/components/audit/AuditTicketsSection';
import { AuditPunishmentsSection } from '@/components/audit/AuditPunishmentsSection';
import { AuditStaffSection } from '@/components/audit/AuditStaffSection';
import { AuditPlayersSection } from '@/components/audit/AuditPlayersSection';
import { usePlayerActivity, usePunishmentAnalytics, useStaffPerformance, useTicketAnalytics, type StaffPerformance } from '@/hooks/data/audit';
import { PERMISSIONS } from '@/hooks/use-permissions';
import { cn } from '@/lib/utils';

type AuditTab = 'overview' | 'tickets' | 'punishments' | 'staff' | 'players';

const AUDIT_TABS = [
  { value: 'overview', labelKey: 'audit.tabOverview' },
  { value: 'tickets', labelKey: 'audit.tabTickets' },
  { value: 'punishments', labelKey: 'audit.tabPunishments' },
  { value: 'staff', labelKey: 'audit.tabStaff' },
  { value: 'players', labelKey: 'audit.tabPlayers' },
] as const satisfies readonly { value: AuditTab; labelKey: string }[];

const DEFAULT_PERIOD = '30d';
const NO_STAFF: readonly StaffPerformance[] = [];

function AuditReport() {
  const { t } = useTranslation();
  const [tab, setTab] = useState<AuditTab>('overview');
  const [period, setPeriod] = useState(DEFAULT_PERIOD);

  const staffPerformance = useStaffPerformance(period);
  const punishmentAnalytics = usePunishmentAnalytics(period);
  const playerActivity = usePlayerActivity(period);
  const ticketAnalytics = useTicketAnalytics(period);

  const queries = [staffPerformance, punishmentAnalytics, playerActivity, ticketAnalytics];
  const isRefreshing = queries.some((query) => query.isRefetching);
  const refresh = () => {
    for (const query of queries) void query.refetch();
  };

  return (
    <PageContainer>
      <div className="flex flex-col space-y-8">
        <div className="flex flex-wrap items-center justify-between gap-4">
          <h2 className="text-xl font-semibold">{t('audit.title')}</h2>
          <div className="flex items-center gap-2">
            <AuditPeriodSelect value={period} onChange={setPeriod} />
            <Button
              variant="ghost"
              size="icon"
              className="text-muted-foreground"
              onClick={refresh}
              disabled={isRefreshing}
              aria-label={t('audit.refresh')}
            >
              <RefreshCw className={cn('h-5 w-5', isRefreshing && 'animate-spin')} />
            </Button>
          </div>
        </div>

        <ReportTabs
          label={t('audit.title')}
          value={tab}
          onChange={setTab}
          tabs={AUDIT_TABS.map(({ value, labelKey }) => ({ value, label: t(labelKey) }))}
        >
          <ReportQueryGate queries={tabQueries(tab, { staffPerformance, punishmentAnalytics, playerActivity, ticketAnalytics })}>
            <AuditTabContent
              tab={tab}
              period={period}
              staff={staffPerformance.data ?? NO_STAFF}
              sections={{
                ticketAnalytics: ticketAnalytics.data,
                punishmentAnalytics: punishmentAnalytics.data,
                playerActivity: playerActivity.data,
              }}
            />
          </ReportQueryGate>
        </ReportTabs>
      </div>
    </PageContainer>
  );
}

interface AuditQueries {
  staffPerformance: ReportQuery;
  punishmentAnalytics: ReportQuery;
  playerActivity: ReportQuery;
  ticketAnalytics: ReportQuery;
}

function tabQueries(tab: AuditTab, queries: AuditQueries): readonly ReportQuery[] {
  switch (tab) {
    case 'overview':
      return [queries.ticketAnalytics, queries.punishmentAnalytics, queries.playerActivity];
    case 'tickets':
      return [queries.ticketAnalytics];
    case 'punishments':
      return [queries.punishmentAnalytics];
    case 'staff':
      return [queries.staffPerformance];
    case 'players':
      return [queries.playerActivity];
  }
}

function AuditTabContent({ tab, period, staff, sections }: {
  tab: AuditTab;
  period: string;
  staff: readonly StaffPerformance[];
  sections: AuditOverviewSectionProps;
}) {
  switch (tab) {
    case 'overview':
      return <AuditOverviewSection {...sections} />;
    case 'tickets':
      return <AuditTicketsSection analytics={sections.ticketAnalytics} />;
    case 'punishments':
      return <AuditPunishmentsSection analytics={sections.punishmentAnalytics} staffNames={staff.map((member) => member.username)} />;
    case 'staff':
      return <AuditStaffSection staff={staff} period={period} />;
    case 'players':
      return <AuditPlayersSection activity={sections.playerActivity} />;
  }
}

export default function AuditPage() {
  const { t } = useTranslation();
  return (
    <PermissionWrapper
      permissions={[PERMISSIONS.ADMIN_AUDIT_VIEW]}
      fallback={(
        <PageContainer title={t('audit.title')}>
          <StatusBanner variant="error">{t('audit.noPermission')}</StatusBanner>
        </PageContainer>
      )}
    >
      <AuditReport />
    </PermissionWrapper>
  );
}
