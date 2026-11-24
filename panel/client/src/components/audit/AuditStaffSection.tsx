import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Bar, BarChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { ReportColumns, ReportSection } from '@/components/report/ReportSection';
import { ReportHeaderCell, ReportTable } from '@/components/report/ReportTable';
import type { StaffPerformance } from '@/hooks/data/audit';
import { CHART_AXIS_PROPS, CHART_BAR_STYLE, CHART_GRID_PROPS, ChartEmpty, ChartTooltip } from './chart-kit';
import { StaffDetailModal } from './StaffDetailModal';

interface OpenStaffDetails {
  staff: StaffPerformance;
  session: number;
}

function StaffRow({ staff, onOpen }: { staff: StaffPerformance; onOpen: (staff: StaffPerformance) => void }) {
  return (
    <tr className="cursor-pointer border-b transition-colors hover:bg-muted/50" onClick={() => { onOpen(staff); }}>
      <td className="p-2 font-medium">
        <button
          type="button"
          className="rounded-sm text-left focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
          onClick={(event) => {
            event.stopPropagation();
            onOpen(staff);
          }}
        >
          {staff.username}
        </button>
      </td>
      <td className="p-2 text-muted-foreground">{staff.role}</td>
      <td className="p-2">{staff.ticketResponses}</td>
      <td className="p-2">{staff.punishmentsIssued}</td>
      <td className="p-2 font-medium">{staff.totalActions}</td>
    </tr>
  );
}

export function AuditStaffSection({ staff, period }: { staff: readonly StaffPerformance[]; period: string }) {
  const { t } = useTranslation();
  const [details, setDetails] = useState<OpenStaffDetails | null>(null);

  const openDetails = (member: StaffPerformance) => {
    setDetails((current) => ({ staff: member, session: (current?.session ?? 0) + 1 }));
  };

  return (
    <div className="space-y-10">
      <ReportColumns>
        <ReportSection title={t('audit.actionsByStaff')}>
          {staff.length === 0 ? (
            <ChartEmpty>{t('audit.noStaffPerformanceData')}</ChartEmpty>
          ) : (
            <ResponsiveContainer width="100%" height={250}>
              <BarChart data={staff}>
                <CartesianGrid {...CHART_GRID_PROPS} />
                <XAxis dataKey="username" {...CHART_AXIS_PROPS} />
                <YAxis {...CHART_AXIS_PROPS} />
                <Tooltip cursor={false} content={<ChartTooltip formatName={(name) => name === 'totalActions' ? t('audit.colTotalActions') : name} />} />
                <Bar dataKey="totalActions" fill="var(--color-chart-2)" style={CHART_BAR_STYLE} />
              </BarChart>
            </ResponsiveContainer>
          )}
        </ReportSection>

        <ReportSection title={t('audit.ticketResponsesTitle')}>
          {staff.length === 0 ? (
            <ChartEmpty>{t('audit.noStaffTicketResponseData')}</ChartEmpty>
          ) : (
            <ResponsiveContainer width="100%" height={250}>
              <BarChart data={staff}>
                <CartesianGrid {...CHART_GRID_PROPS} />
                <XAxis dataKey="username" {...CHART_AXIS_PROPS} />
                <YAxis {...CHART_AXIS_PROPS} />
                <Tooltip cursor={false} content={<ChartTooltip formatName={(name) => name === 'ticketResponses' ? t('audit.colTicketResponses') : name} />} />
                <Bar dataKey="ticketResponses" fill="var(--color-chart-5)" style={CHART_BAR_STYLE} />
              </BarChart>
            </ResponsiveContainer>
          )}
        </ReportSection>
      </ReportColumns>

      <ReportSection title={t('audit.staffActivityDetails')}>
        {staff.length === 0 ? (
          <ChartEmpty>{t('audit.noStaffActivityData')}</ChartEmpty>
        ) : (
          <ReportTable
            header={(
              <>
                <ReportHeaderCell>{t('audit.colUsername')}</ReportHeaderCell>
                <ReportHeaderCell>{t('audit.colRole')}</ReportHeaderCell>
                <ReportHeaderCell>{t('audit.colTicketResponses')}</ReportHeaderCell>
                <ReportHeaderCell>{t('audit.colPunishmentsIssued')}</ReportHeaderCell>
                <ReportHeaderCell>{t('audit.colTotalActions')}</ReportHeaderCell>
              </>
            )}
          >
            {staff.map((member) => <StaffRow key={member.id} staff={member} onOpen={openDetails} />)}
          </ReportTable>
        )}
      </ReportSection>

      {details !== null && (
        <StaffDetailModal
          key={details.session}
          staff={details.staff}
          onClose={() => { setDetails(null); }}
          initialPeriod={period}
        />
      )}
    </div>
  );
}
