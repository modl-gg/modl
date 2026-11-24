import { useTranslation } from 'react-i18next';
import { Area, AreaChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { Kpi, KpiStrip } from '@/components/report/KpiStrip';
import { ReportColumns, ReportSection } from '@/components/report/ReportSection';
import type { PlayerActivity } from '@/hooks/data/audit';
import { formatChartDateLabel } from '@/utils/date-utils';
import { CHART_AXIS_PROPS, CHART_GRID_PROPS, ChartEmpty, ChartTooltip } from './chart-kit';

const VISIBLE_COUNTRIES = 8;

function CountryLogins({ logins }: { logins: PlayerActivity['loginsByCountry'] }) {
  const max = logins.reduce((highest, item) => Math.max(highest, item.count), 0);
  return (
    <ul className="space-y-2 py-4">
      {logins.slice(0, VISIBLE_COUNTRIES).map((country) => (
        <li key={country.country} className="flex items-center justify-between">
          <span>{country.country}</span>
          <div className="flex items-center gap-2">
            <div className="h-2 w-20 rounded-full bg-muted">
              <div className="h-2 rounded-full bg-primary" style={{ width: `${String(max > 0 ? (country.count / max) * 100 : 0)}%` }} />
            </div>
            <span className="text-sm font-medium">{country.count}</span>
          </div>
        </li>
      ))}
    </ul>
  );
}

export function AuditPlayersSection({ activity }: { activity: PlayerActivity | undefined }) {
  const { t } = useTranslation();
  const newPlayers = activity?.newPlayersTrend ?? [];
  const logins = activity?.loginsByCountry ?? [];

  return (
    <div className="space-y-10">
      <ReportColumns>
        <ReportSection title={t('audit.playerLoginActivity')}>
          {newPlayers.length === 0 ? (
            <ChartEmpty>{t('audit.noPlayerActivityData')}</ChartEmpty>
          ) : (
            <ResponsiveContainer width="100%" height={250}>
              <AreaChart data={newPlayers}>
                <CartesianGrid {...CHART_GRID_PROPS} />
                <XAxis dataKey="date" {...CHART_AXIS_PROPS} tickFormatter={formatChartDateLabel} />
                <YAxis {...CHART_AXIS_PROPS} />
                <Tooltip content={<ChartTooltip formatLabel={formatChartDateLabel} formatName={(name) => name === 'count' ? t('audit.legendNewPlayers') : name} />} />
                <Area type="monotone" dataKey="count" stroke="var(--color-chart-3)" fill="var(--color-chart-3)" fillOpacity={0.3} dot={false} activeDot={false} name={t('audit.legendNewPlayers')} />
              </AreaChart>
            </ResponsiveContainer>
          )}
        </ReportSection>

        <ReportSection title={t('audit.loginsByCountry')}>
          {logins.length === 0 ? <ChartEmpty>{t('audit.noLoginData')}</ChartEmpty> : <CountryLogins logins={logins} />}
        </ReportSection>
      </ReportColumns>

      <ReportSection title={t('audit.securityAlerts')}>
        <KpiStrip>
          <Kpi
            label={t('audit.vpnAddresses')}
            value={activity?.vpnYesCount ?? 0}
            detail={t('audit.vpnBreakdown', { clean: activity?.vpnNoCount ?? 0, unknown: activity?.vpnUnknownCount ?? 0 })}
          />
          <Kpi label={t('audit.hostingIps')} value={activity?.hostingCount ?? 0} />
        </KpiStrip>
      </ReportSection>
    </div>
  );
}
