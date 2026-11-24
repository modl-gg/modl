import { useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import type { TFunction } from 'i18next';
import { Bar, BarChart, CartesianGrid, Line, LineChart, Pie, PieChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import type { PieLabelRenderProps } from 'recharts';
import { Kpi, KpiStrip } from '@/components/report/KpiStrip';
import { ReportColumns, ReportSection } from '@/components/report/ReportSection';
import type { TicketAnalytics } from '@/hooks/data/audit';
import { formatChartDateLabel } from '@/utils/date-utils';
import { CHART_AXIS_PROPS, CHART_BAR_STYLE, CHART_GRID_PROPS, ChartEmpty, ChartTooltip, formatPieSliceLabel, withPaletteFill } from './chart-kit';

function ticketStatusLabel(status: string, t: TFunction): string {
  switch (status) {
    case 'open':
      return t('status.open');
    case 'closed':
      return t('status.closed');
    default:
      return t('audit.unknown');
  }
}

export function AuditTicketsSection({ analytics }: { analytics: TicketAnalytics | undefined }) {
  const { t } = useTranslation();
  const statusSlices = useMemo(
    () => withPaletteFill((analytics?.byStatus ?? []).map(({ status, count }) => ({ label: ticketStatusLabel(status, t), count }))),
    [analytics, t],
  );
  const totalTickets = statusSlices.reduce((sum, item) => sum + item.count, 0);
  const byCategory = analytics?.byCategory ?? [];
  const dailyTickets = analytics?.dailyTickets ?? [];
  const resolutionByCategory = analytics?.avgResolutionByCategory ?? [];

  return (
    <div className="space-y-10">
      <KpiStrip>
        <Kpi label={t('audit.totalTickets')} value={totalTickets} />
        <Kpi label={t('audit.avgResolutionTime')} value={t('audit.hoursValue', { value: Math.round(analytics?.avgResolutionHours ?? 0) })} />
        <Kpi label={t('audit.categories')} value={byCategory.length} />
      </KpiStrip>

      <ReportColumns>
        <ReportSection title={t('audit.ticketsByStatus')}>
          {statusSlices.length === 0 ? (
            <ChartEmpty>{t('audit.noStatusData')}</ChartEmpty>
          ) : (
            <ResponsiveContainer width="100%" height={250}>
              <PieChart>
                <Pie
                  data={statusSlices}
                  cx="50%"
                  cy="50%"
                  outerRadius={80}
                  dataKey="count"
                  nameKey="label"
                  label={({ label, percent }: PieLabelRenderProps & { label?: string }) => formatPieSliceLabel(label, percent)}
                />
                <Tooltip content={<ChartTooltip />} />
              </PieChart>
            </ResponsiveContainer>
          )}
        </ReportSection>

        <ReportSection title={t('audit.ticketsByCategory')}>
          {byCategory.length === 0 ? (
            <ChartEmpty>{t('audit.noCategoryData')}</ChartEmpty>
          ) : (
            <ResponsiveContainer width="100%" height={250}>
              <BarChart data={byCategory}>
                <CartesianGrid {...CHART_GRID_PROPS} />
                <XAxis dataKey="category" {...CHART_AXIS_PROPS} />
                <YAxis {...CHART_AXIS_PROPS} />
                <Tooltip cursor={false} content={<ChartTooltip />} />
                <Bar dataKey="count" name={t('audit.colTickets')} fill="var(--color-chart-1)" style={CHART_BAR_STYLE} />
              </BarChart>
            </ResponsiveContainer>
          )}
        </ReportSection>
      </ReportColumns>

      <ReportSection title={t('audit.dailyTicketTrend')}>
        {dailyTickets.length === 0 ? (
          <ChartEmpty>{t('audit.noTicketTrendData')}</ChartEmpty>
        ) : (
          <ResponsiveContainer width="100%" height={300}>
            <LineChart data={dailyTickets}>
              <CartesianGrid {...CHART_GRID_PROPS} />
              <XAxis dataKey="date" {...CHART_AXIS_PROPS} tickFormatter={formatChartDateLabel} />
              <YAxis {...CHART_AXIS_PROPS} />
              <Tooltip content={<ChartTooltip formatLabel={formatChartDateLabel} />} />
              <Line type="monotone" dataKey="count" stroke="var(--color-chart-1)" strokeWidth={2} dot={false} activeDot={false} name={t('audit.colTickets')} />
            </LineChart>
          </ResponsiveContainer>
        )}
      </ReportSection>

      {resolutionByCategory.length > 0 && (
        <ReportSection title={t('audit.avgResolutionByCategory')}>
          <ResponsiveContainer width="100%" height={250}>
            <BarChart data={resolutionByCategory}>
              <CartesianGrid {...CHART_GRID_PROPS} />
              <XAxis dataKey="category" {...CHART_AXIS_PROPS} />
              <YAxis {...CHART_AXIS_PROPS} />
              <Tooltip
                cursor={false}
                content={<ChartTooltip formatValue={(value) => t('audit.hoursValue', { value: Number(value).toFixed(1) })} />}
              />
              <Bar dataKey="avgHours" fill="var(--color-chart-3)" style={CHART_BAR_STYLE} name={t('audit.avgHours')} />
            </BarChart>
          </ResponsiveContainer>
        </ReportSection>
      )}
    </div>
  );
}
