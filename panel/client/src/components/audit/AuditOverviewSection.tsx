import { useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import { CartesianGrid, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { ReportSection } from '@/components/report/ReportSection';
import type { PlayerActivity, PunishmentAnalytics, TicketAnalytics } from '@/hooks/data/audit';
import { formatChartDateLabel } from '@/utils/date-utils';
import { CHART_AXIS_PROPS, CHART_GRID_PROPS, ChartLegend, ChartTooltip } from './chart-kit';

type OverviewMetric = 'tickets' | 'punishments' | 'players';

interface DailyMetrics extends Record<OverviewMetric, number> {
  date: string;
}

const METRIC_STROKES = {
  tickets: 'var(--color-chart-1)',
  punishments: 'var(--color-destructive-text)',
  players: 'var(--color-chart-3)',
} as const satisfies Record<OverviewMetric, string>;

const METRIC_SWATCHES = {
  tickets: 'bg-chart-1',
  punishments: 'bg-destructive-text',
  players: 'bg-chart-3',
} as const satisfies Record<OverviewMetric, string>;

const METRIC_LABEL_KEYS = {
  tickets: 'audit.legendNewTickets',
  punishments: 'audit.legendPunishments',
  players: 'audit.legendNewPlayers',
} as const satisfies Record<OverviewMetric, string>;

const METRICS: readonly OverviewMetric[] = ['tickets', 'punishments', 'players'];

function isOverviewMetric(name: string): name is OverviewMetric {
  return METRICS.some((metric) => metric === name);
}

function combineDailyMetrics(series: Readonly<Record<OverviewMetric, readonly { date: string; count: number }[]>>): DailyMetrics[] {
  const byDate = new Map<string, DailyMetrics>();
  for (const metric of METRICS) {
    for (const { date, count } of series[metric]) {
      const entry = byDate.get(date) ?? { date, tickets: 0, punishments: 0, players: 0 };
      entry[metric] = count;
      byDate.set(date, entry);
    }
  }
  return Array.from(byDate.values()).sort((a, b) => a.date.localeCompare(b.date));
}

export interface AuditOverviewSectionProps {
  ticketAnalytics: TicketAnalytics | undefined;
  punishmentAnalytics: PunishmentAnalytics | undefined;
  playerActivity: PlayerActivity | undefined;
}

export function AuditOverviewSection({ ticketAnalytics, punishmentAnalytics, playerActivity }: AuditOverviewSectionProps) {
  const { t } = useTranslation();
  const dailyMetrics = useMemo(() => combineDailyMetrics({
    tickets: ticketAnalytics?.dailyTickets ?? [],
    punishments: punishmentAnalytics?.dailyPunishments ?? [],
    players: playerActivity?.newPlayersTrend ?? [],
  }), [ticketAnalytics, punishmentAnalytics, playerActivity]);

  if (dailyMetrics.length === 0) return null;

  const formatMetricName = (name: string) => isOverviewMetric(name) ? t(METRIC_LABEL_KEYS[name]) : name;

  return (
    <ReportSection title={t('audit.activityOverview')}>
      <ResponsiveContainer width="100%" height={300}>
        <LineChart data={dailyMetrics}>
          <CartesianGrid {...CHART_GRID_PROPS} />
          <XAxis dataKey="date" {...CHART_AXIS_PROPS} tickFormatter={formatChartDateLabel} />
          <YAxis {...CHART_AXIS_PROPS} />
          <Tooltip content={<ChartTooltip formatLabel={formatChartDateLabel} formatName={formatMetricName} />} />
          {METRICS.map((metric) => (
            <Line
              key={metric}
              type="monotone"
              dataKey={metric}
              name={metric}
              stroke={METRIC_STROKES[metric]}
              strokeWidth={2}
              dot={false}
              activeDot={false}
            />
          ))}
        </LineChart>
      </ResponsiveContainer>
      <ChartLegend items={METRICS.map((metric) => ({ label: t(METRIC_LABEL_KEYS[metric]), swatchClassName: METRIC_SWATCHES[metric] }))} />
    </ReportSection>
  );
}
