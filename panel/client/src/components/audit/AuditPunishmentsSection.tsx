import { useTranslation } from 'react-i18next';
import type { TFunction } from 'i18next';
import { Bar, BarChart, CartesianGrid, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { ReportColumns, ReportSection } from '@/components/report/ReportSection';
import type { PunishmentAnalytics, StaffPunishmentCount } from '@/hooks/data/audit';
import { formatChartDateLabel } from '@/utils/date-utils';
import { AuditPunishmentsList } from './AuditPunishmentsList';
import { CHART_AXIS_PROPS, CHART_BAR_STYLE, CHART_GRID_PROPS, ChartEmpty, ChartTooltip } from './chart-kit';

function issuerLabel(issuer: StaffPunishmentCount, t: TFunction): string {
  switch (issuer.kind) {
    case 'staff':
    case 'automated':
      return issuer.username;
    case 'console':
      return t('audit.issuerConsole');
    case 'unknown':
      return t('audit.unknown');
  }
}

export function AuditPunishmentsSection({ analytics, staffNames }: {
  analytics: PunishmentAnalytics | undefined;
  staffNames: readonly string[];
}) {
  const { t } = useTranslation();
  const byType = analytics?.byType ?? [];
  const daily = analytics?.dailyPunishments ?? [];
  const byStaff = analytics?.byStaff ?? [];
  const formatCountName = (name: string) => name === 'count' ? t('audit.count') : name;

  return (
    <div className="space-y-10">
      <ReportColumns>
        <ReportSection title={t('audit.punishmentsByType')}>
          {byType.length === 0 ? (
            <ChartEmpty>{t('audit.noPunishmentTypeData')}</ChartEmpty>
          ) : (
            <ResponsiveContainer width="100%" height={250}>
              <BarChart data={byType}>
                <CartesianGrid {...CHART_GRID_PROPS} />
                <XAxis dataKey="type" {...CHART_AXIS_PROPS} />
                <YAxis {...CHART_AXIS_PROPS} />
                <Tooltip cursor={false} content={<ChartTooltip formatName={formatCountName} />} />
                <Bar dataKey="count" fill="var(--color-destructive-text)" style={CHART_BAR_STYLE} />
              </BarChart>
            </ResponsiveContainer>
          )}
        </ReportSection>

        <ReportSection title={t('audit.dailyPunishmentTrend')}>
          {daily.length === 0 ? (
            <ChartEmpty>{t('audit.noPunishmentTrendData')}</ChartEmpty>
          ) : (
            <ResponsiveContainer width="100%" height={250}>
              <LineChart data={daily}>
                <CartesianGrid {...CHART_GRID_PROPS} />
                <XAxis dataKey="date" {...CHART_AXIS_PROPS} tickFormatter={formatChartDateLabel} />
                <YAxis {...CHART_AXIS_PROPS} />
                <Tooltip content={<ChartTooltip formatLabel={formatChartDateLabel} formatName={formatCountName} />} />
                <Line type="monotone" dataKey="count" stroke="var(--color-destructive-text)" strokeWidth={2} dot={false} activeDot={false} />
              </LineChart>
            </ResponsiveContainer>
          )}
        </ReportSection>
      </ReportColumns>

      <ReportSection title={t('audit.topPunishers')}>
        {byStaff.length === 0 ? (
          <ChartEmpty>{t('audit.noStaffPunishmentData')}</ChartEmpty>
        ) : (
          <ul className="space-y-2">
            {byStaff.map((issuer) => (
              <li key={`${issuer.kind}:${issuer.username}`} className="flex items-center justify-between py-1.5 text-sm">
                <span className="font-medium">{issuerLabel(issuer, t)}</span>
                <span className="tabular-nums text-muted-foreground">{issuer.count}</span>
              </li>
            ))}
          </ul>
        )}
      </ReportSection>

      <AuditPunishmentsList staffNames={staffNames} />
    </div>
  );
}
