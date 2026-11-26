import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Undo2 } from 'lucide-react';
import { Area, AreaChart, CartesianGrid, Pie, PieChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import type { PieLabelRenderProps } from 'recharts';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Dialog, DialogContent, DialogHeader, DialogPortal, DialogTitle } from '@/components/ui/dialog';
import { PunishmentStatusBadge } from '@/components/ui/punishment-status-badge';
import { TicketStatusBadge } from '@/components/ui/ticket-status-badge';
import { Kpi, KpiStrip } from '@/components/report/KpiStrip';
import { ReportColumns, ReportSection } from '@/components/report/ReportSection';
import { ReportEmptyRow, ReportHeaderCell, ReportTable } from '@/components/report/ReportTable';
import { ReportQueryGate } from '@/components/report/ReportQueryGate';
import { usePlayerWindow } from '@/contexts/PlayerWindowContext';
import { useStaffDetails, type StaffDetailPunishment, type StaffDetailTicket, type StaffPerformance } from '@/hooks/data/audit';
import { useNow } from '@/hooks/use-now';
import { usePermissions } from '@/hooks/use-permissions';
import { normalizeTicketStatus } from '@/lib/ticket-enums';
import { formatChartDateLabel, formatDateOnly } from '@/utils/date-utils';
import type { PunishmentStatusKind } from '@/utils/punishment-status';
import { AuditPeriodSelect } from './AuditPeriodSelect';
import { formatCompactDuration } from '@/components/player/punishment-timeline';
import { MINUTE_MS, PHASE_STATUS_KINDS, punishmentTypeBadgeVariant } from './audit-format';
import { CHART_AXIS_PROPS, CHART_GRID_PROPS, ChartEmpty, ChartTooltip, formatPieSliceLabel, withPaletteFill } from './chart-kit';
import { RollbackPunishmentDialog, type RollbackTarget } from './RollbackPunishmentDialog';
import { useModalBackground } from './use-modal-background';

const NO_PUNISHMENTS: readonly StaffDetailPunishment[] = [];
const NO_TICKETS: readonly StaffDetailTicket[] = [];

function recentPunishmentStatus(punishment: StaffDetailPunishment): PunishmentStatusKind {
  if (punishment.rolledBack) return 'rolledBack';
  return PHASE_STATUS_KINDS[punishment.phase];
}

function isInsidePlayerWindow(target: EventTarget | null): boolean {
  return target instanceof Element && target.closest('[data-player-windows]') !== null;
}

function RecentPunishmentsTable({ punishments, onRollback }: {
  punishments: readonly StaffDetailPunishment[];
  onRollback: ((target: RollbackTarget) => void) | null;
}) {
  const { t } = useTranslation();
  const { openPlayerWindow } = usePlayerWindow();
  const unknown = t('audit.unknown');

  return (
    <ReportTable
      header={(
        <>
          <ReportHeaderCell>{t('audit.colPlayer')}</ReportHeaderCell>
          <ReportHeaderCell>{t('audit.colType')}</ReportHeaderCell>
          <ReportHeaderCell>{t('audit.colDuration')}</ReportHeaderCell>
          <ReportHeaderCell>{t('audit.colDate')}</ReportHeaderCell>
          <ReportHeaderCell>{t('audit.colStatus')}</ReportHeaderCell>
        </>
      )}
    >
      {punishments.length === 0 && <ReportEmptyRow columns={5}>{t('audit.noRecentPunishments')}</ReportEmptyRow>}
      {punishments.map((punishment) => {
        const status = recentPunishmentStatus(punishment);
        return (
          <tr key={punishment.id} className="border-b">
            <td className="p-2 font-medium">
              <Button
                variant="link"
                className="h-auto p-0 text-left font-medium"
                onClick={() => { openPlayerWindow(punishment.playerId, punishment.playerName); }}
              >
                {punishment.playerName || unknown}
              </Button>
            </td>
            <td className="p-2">
              <Badge variant={punishmentTypeBadgeVariant(punishment.banLike)}>{punishment.type}</Badge>
            </td>
            <td className="p-2">{punishment.durationMs === null ? t('audit.permanent') : formatCompactDuration(punishment.durationMs, t)}</td>
            <td className="p-2">{punishment.issued ? formatDateOnly(punishment.issued) : '--'}</td>
            <td className="p-2">
              <div className="flex items-center gap-2">
                <PunishmentStatusBadge status={status} />
                {punishment.phase !== 'ended' && onRollback !== null && (
                  <Button
                    variant="outline"
                    size="icon"
                    className="h-7 w-7"
                    onClick={() => {
                      onRollback({
                        punishmentId: punishment.id,
                        summary: `${punishment.type || unknown}, ${punishment.playerName || unknown}`,
                      });
                    }}
                    aria-label={t('audit.rollbackThisPunishment')}
                  >
                    <Undo2 className="h-3 w-3" />
                  </Button>
                )}
              </div>
            </td>
          </tr>
        );
      })}
    </ReportTable>
  );
}

function RecentTicketsTable({ tickets }: { tickets: readonly StaffDetailTicket[] }) {
  const { t } = useTranslation();
  const now = useNow(MINUTE_MS);

  return (
    <ReportTable
      header={(
        <>
          <ReportHeaderCell>{t('audit.colTicketId')}</ReportHeaderCell>
          <ReportHeaderCell>{t('audit.colSubject')}</ReportHeaderCell>
          <ReportHeaderCell>{t('audit.colStatus')}</ReportHeaderCell>
          <ReportHeaderCell>{t('audit.colTimeSinceActivity')}</ReportHeaderCell>
        </>
      )}
    >
      {tickets.length === 0 && (
        <ReportEmptyRow columns={4}>{t('audit.noRecentTicketResponses')}</ReportEmptyRow>
      )}
      {tickets.map((ticket) => (
        <tr key={ticket.id} className="border-b">
          <td className="p-2 font-medium">
            <Button variant="link" className="h-auto p-0 text-left font-medium" asChild>
              <a href={`/panel/tickets/${encodeURIComponent(ticket.id)}`} target="_blank" rel="noopener noreferrer">
                #{ticket.id}
              </a>
            </Button>
          </td>
          <td className="p-2">{ticket.subject || t('audit.noSubject')}</td>
          <td className="p-2">
            <TicketStatusBadge status={normalizeTicketStatus(ticket.status)} />
          </td>
          <td className="p-2">{ticket.lastActivity ? formatCompactDuration(now - ticket.lastActivity.getTime(), t) : '--'}</td>
        </tr>
      ))}
    </ReportTable>
  );
}

interface StaffDetailModalProps {
  staff: StaffPerformance;
  onClose: () => void;
  initialPeriod: string;
}

export function StaffDetailModal({ staff, onClose, initialPeriod }: StaffDetailModalProps) {
  const { t } = useTranslation();
  const { isSuperAdmin } = usePermissions();
  const [selectedPeriod, setSelectedPeriod] = useState(initialPeriod);
  const [rollbackTarget, setRollbackTarget] = useState<RollbackTarget | null>(null);
  const staffDetailsQuery = useStaffDetails(staff.username, selectedPeriod);
  const staffDetails = staffDetailsQuery.data;
  useModalBackground();

  const dailyActivity = staffDetails?.dailyActivity ?? [];
  const punishmentTypeSlices = useMemo(
    () => withPaletteFill(staffDetails?.punishmentTypeBreakdown ?? []),
    [staffDetails],
  );

  return (
    <Dialog
      open
      modal={false}
      onOpenChange={(open) => {
        if (!open) onClose();
      }}
    >
      <DialogPortal>
        <div aria-hidden="true" className="fixed inset-0 z-40 bg-black/80" />
      </DialogPortal>
      <DialogContent
        className="z-40 max-h-[90vh] max-w-6xl overflow-y-auto"
        onInteractOutside={(event) => {
          if (isInsidePlayerWindow(event.target)) event.preventDefault();
        }}
      >
        <DialogHeader>
          <DialogTitle className="flex flex-wrap items-center gap-2">
            {staff.username}
            <span className="text-sm font-normal text-muted-foreground">{staff.role}</span>
          </DialogTitle>
        </DialogHeader>

        <div className="space-y-8">
          <AuditPeriodSelect value={selectedPeriod} onChange={setSelectedPeriod} />

          <ReportQueryGate queries={[staffDetailsQuery]}>
            {staffDetails !== undefined && (
              <KpiStrip>
                <Kpi label={t('audit.statPunishmentsIssued')} value={staffDetails.summary.totalPunishments} />
                <Kpi label={t('audit.colTicketResponses')} value={staffDetails.summary.totalTickets} />
                <Kpi label={t('audit.statEvidenceUploaded')} value={staffDetails.summary.evidenceUploads} />
                <Kpi label={t('audit.statAvgResponseTime')} value={t('audit.minutesValue', { value: staffDetails.summary.avgResponseMinutes })} />
              </KpiStrip>
            )}

            <ReportColumns>
              <ReportSection title={t('audit.dailyActivityBreakdown')}>
                {dailyActivity.length === 0 ? (
                  <ChartEmpty>{t('audit.noDailyActivityData')}</ChartEmpty>
                ) : (
                  <ResponsiveContainer width="100%" height={300}>
                    <AreaChart data={dailyActivity}>
                      <CartesianGrid {...CHART_GRID_PROPS} />
                      <XAxis dataKey="date" {...CHART_AXIS_PROPS} tickFormatter={formatChartDateLabel} />
                      <YAxis {...CHART_AXIS_PROPS} />
                      <Tooltip content={<ChartTooltip formatLabel={formatChartDateLabel} />} />
                      <Area type="monotone" dataKey="punishments" name={t('audit.legendPunishments')} stackId="1" stroke="var(--color-destructive-text)" fill="var(--color-destructive-text)" dot={false} activeDot={false} />
                      <Area type="monotone" dataKey="tickets" name={t('audit.colTickets')} stackId="1" stroke="var(--color-chart-1)" fill="var(--color-chart-1)" dot={false} activeDot={false} />
                    </AreaChart>
                  </ResponsiveContainer>
                )}
              </ReportSection>

              <ReportSection title={t('audit.punishmentTypesIssued')}>
                {punishmentTypeSlices.length === 0 ? (
                  <ChartEmpty>{t('audit.noPunishmentTypeData')}</ChartEmpty>
                ) : (
                  <ResponsiveContainer width="100%" height={300}>
                    <PieChart>
                      <Pie
                        data={punishmentTypeSlices}
                        cx="50%"
                        cy="50%"
                        outerRadius={80}
                        dataKey="count"
                        nameKey="type"
                        label={({ type, percent }: PieLabelRenderProps & { type?: string }) => formatPieSliceLabel(type, percent)}
                      />
                      <Tooltip content={<ChartTooltip />} />
                    </PieChart>
                  </ResponsiveContainer>
                )}
              </ReportSection>
            </ReportColumns>

            <ReportSection title={t('audit.recentPunishmentsIssued')}>
              <RecentPunishmentsTable
                punishments={staffDetails?.punishments ?? NO_PUNISHMENTS}
                onRollback={isSuperAdmin ? setRollbackTarget : null}
              />
            </ReportSection>

            <ReportSection title={t('audit.recentTicketResponses')}>
              <RecentTicketsTable tickets={staffDetails?.tickets ?? NO_TICKETS} />
            </ReportSection>
          </ReportQueryGate>
        </div>
      </DialogContent>
      <RollbackPunishmentDialog target={rollbackTarget} onClose={() => { setRollbackTarget(null); }} />
    </Dialog>
  );
}
