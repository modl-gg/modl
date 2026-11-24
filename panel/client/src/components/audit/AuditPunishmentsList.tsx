import { useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import type { TFunction } from 'i18next';
import { ArrowUpDown, Loader2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Chip, ChipButton } from '@/components/ui/chip';
import { PunishmentStatusBadge } from '@/components/ui/punishment-status-badge';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { ReportSection } from '@/components/report/ReportSection';
import { ReportQueryGate } from '@/components/report/ReportQueryGate';
import { ReportHeaderCell, ReportTable } from '@/components/report/ReportTable';
import { usePlayerWindow } from '@/contexts/PlayerWindowContext';
import {
  useAuditPunishments,
  useAuditPunishmentTypes,
  type AuditPunishment,
  type AuditPunishmentEvidence,
  type AuditPunishmentEvidenceFilter,
  type AuditPunishmentPhase,
  type AuditPunishmentQuery,
  type AuditPunishmentSortDirection,
  type AuditPunishmentSortField,
  type AuditPunishmentStatusFilter,
} from '@/hooks/data/audit';
import { useNow } from '@/hooks/use-now';
import { usePermissions } from '@/hooks/use-permissions';
import { cn, openExternalUrl } from '@/lib/utils';
import { formatDateOnly } from '@/utils/date-utils';
import type { PunishmentStatusKind } from '@/utils/punishment-status';
import { BulkPunishmentActionsModal } from './BulkPunishmentActionsModal';
import { formatCompactDuration } from '@/components/player/punishment-timeline';
import { DAY_MS, HOUR_MS, MINUTE_MS, pickOption, punishmentTypeBadgeVariant } from './audit-format';
import { ChartEmpty } from './chart-kit';

interface SortOrder {
  field: AuditPunishmentSortField;
  direction: AuditPunishmentSortDirection;
}

const ALL = 'all';
const STATUS_FILTERS: readonly AuditPunishmentStatusFilter[] = ['active', 'unstarted', 'ended', 'all'];
const PHASE_STATUS_KINDS: Record<AuditPunishmentPhase, PunishmentStatusKind> = {
  active: 'active',
  unstarted: 'unstarted',
  ended: 'inactive',
};
const EVIDENCE_FILTERS: readonly AuditPunishmentEvidenceFilter[] = ['all', 'yes', 'no'];
const NO_PUNISHMENTS: readonly AuditPunishment[] = [];
const NEWEST_FIRST: SortOrder = { field: 'issued', direction: 'desc' };

function hasNarrowingFilter(query: AuditPunishmentQuery): boolean {
  return query.staff !== null || query.typeOrdinal !== null || query.evidence !== 'all';
}

function typeOrdinalOf(value: string): number | null {
  const ordinal = Number.parseInt(value, 10);
  return value === ALL || Number.isNaN(ordinal) ? null : ordinal;
}

function formatDuration(durationMs: number | null, t: TFunction): string {
  return durationMs === null || durationMs <= 0 ? t('audit.permanent') : formatCompactDuration(durationMs, t);
}

function formatTimeRemaining(expires: Date | null, now: number, t: TFunction): string {
  if (expires === null) return t('audit.permanent');
  const remainingMs = expires.getTime() - now;
  if (remainingMs <= 0) return t('audit.expiring');
  const days = Math.floor(remainingMs / DAY_MS);
  const hours = Math.floor((remainingMs % DAY_MS) / HOUR_MS);
  if (days > 0) return t('audit.daysHoursLeft', { days, hours });
  if (hours > 0) return t('audit.hoursLeft', { hours });
  return t('audit.minutesLeft', { minutes: Math.floor((remainingMs % HOUR_MS) / MINUTE_MS) });
}

function SortHeader({ field, label, sort, onSort }: {
  field: AuditPunishmentSortField;
  label: string;
  sort: SortOrder;
  onSort: (field: AuditPunishmentSortField) => void;
}) {
  const active = sort.field === field;
  return (
    <th className="p-2 text-left" aria-sort={active ? (sort.direction === 'asc' ? 'ascending' : 'descending') : 'none'}>
      <button
        type="button"
        className="inline-flex items-center gap-1 rounded-sm hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
        onClick={() => { onSort(field); }}
      >
        {label}
        <ArrowUpDown className={cn('h-3 w-3', active ? 'text-primary' : 'text-muted-foreground')} aria-hidden="true" />
      </button>
    </th>
  );
}

function EvidenceChips({ evidence }: { evidence: readonly AuditPunishmentEvidence[] }) {
  const { t } = useTranslation();
  return (
    <div className="flex flex-wrap gap-1">
      {evidence.map((item) => {
        const label = item.fileName || item.type || `#${String(item.position)}`;
        const title = item.text || item.fileName || t('audit.colEvidence');
        return item.url === ''
          ? <Chip key={item.key} title={title}>{label}</Chip>
          : <ChipButton key={item.key} title={title} onClick={() => { openExternalUrl(item.url); }}>{label}</ChipButton>;
      })}
    </div>
  );
}

function RemainingTime({ punishment, now }: { punishment: AuditPunishment; now: number }) {
  const { t } = useTranslation();
  if (punishment.phase !== 'active') return <span className="text-xs text-muted-foreground">{t('common.notAvailable')}</span>;
  return (
    <span className={cn('text-xs font-medium', punishment.expires === null ? 'text-destructive-text' : 'text-warning')}>
      {formatTimeRemaining(punishment.expires, now, t)}
    </span>
  );
}

function FilterSelect({ value, onChange, label, className, children }: {
  value: string;
  onChange: (value: string) => void;
  label: string;
  className: string;
  children: ReactNode;
}) {
  return (
    <Select value={value} onValueChange={onChange}>
      <SelectTrigger className={cn('h-8 text-xs', className)} aria-label={label}>
        <SelectValue placeholder={label} />
      </SelectTrigger>
      <SelectContent>{children}</SelectContent>
    </Select>
  );
}

export function AuditPunishmentsList({ staffNames }: { staffNames: readonly string[] }) {
  const { t } = useTranslation();
  const { isSuperAdmin } = usePermissions();
  const { openPlayerWindow } = usePlayerWindow();
  const now = useNow(MINUTE_MS);
  const [statusFilter, setStatusFilter] = useState<AuditPunishmentStatusFilter>('active');
  const [staffFilter, setStaffFilter] = useState<string | null>(null);
  const [typeFilter, setTypeFilter] = useState<number | null>(null);
  const [evidenceFilter, setEvidenceFilter] = useState<AuditPunishmentEvidenceFilter>('all');
  const [sort, setSort] = useState<SortOrder>(NEWEST_FIRST);

  const query: AuditPunishmentQuery = {
    status: statusFilter,
    sort: sort.field,
    direction: sort.direction,
    staff: staffFilter,
    typeOrdinal: typeFilter,
    evidence: evidenceFilter,
  };
  const punishmentsQuery = useAuditPunishments(query);
  const { data: punishmentTypes = [] } = useAuditPunishmentTypes();
  const { hasNextPage, isFetchingNextPage, isFetchNextPageError, fetchNextPage } = punishmentsQuery;
  const punishments = punishmentsQuery.data ?? NO_PUNISHMENTS;
  const loadedCount = `${String(punishments.length)}${hasNextPage ? '+' : ''}`;
  const showStatus = statusFilter === 'all';

  const toggleSort = (field: AuditPunishmentSortField) => {
    setSort((current) => current.field === field
      ? { field, direction: current.direction === 'desc' ? 'asc' : 'desc' }
      : { field, direction: 'desc' });
  };

  return (
    <ReportSection
      title={(
        <span className="flex items-baseline gap-2">
          {t('audit.punishmentsList')}
          {punishmentsQuery.data !== undefined && (
            <span className="font-normal tabular-nums text-muted-foreground">{loadedCount}</span>
          )}
        </span>
      )}
      actions={(
        <div className="flex flex-wrap items-center gap-2">
          <FilterSelect
            value={statusFilter}
            onChange={(value) => {
              const status = pickOption(STATUS_FILTERS, value);
              if (status !== undefined) setStatusFilter(status);
            }}
            label={t('audit.colStatus')}
            className="w-[140px]"
          >
            <SelectItem value="active">{t('status.active')}</SelectItem>
            <SelectItem value="unstarted">{t('player.unstarted')}</SelectItem>
            <SelectItem value="ended">{t('status.inactive')}</SelectItem>
            <SelectItem value="all">{t('audit.all')}</SelectItem>
          </FilterSelect>
          <FilterSelect
            value={staffFilter ?? ALL}
            onChange={(value) => { setStaffFilter(value === ALL ? null : value); }}
            label={t('audit.colStaff')}
            className="w-[140px]"
          >
            <SelectItem value={ALL}>{t('audit.allStaff')}</SelectItem>
            {staffNames.map((name) => <SelectItem key={name} value={name}>{name}</SelectItem>)}
          </FilterSelect>
          <FilterSelect
            value={typeFilter === null ? ALL : String(typeFilter)}
            onChange={(value) => { setTypeFilter(typeOrdinalOf(value)); }}
            label={t('audit.colType')}
            className="w-[140px]"
          >
            <SelectItem value={ALL}>{t('audit.allTypes')}</SelectItem>
            {punishmentTypes.map((type) => (
              <SelectItem key={type.ordinal} value={String(type.ordinal)}>{type.name}</SelectItem>
            ))}
          </FilterSelect>
          <FilterSelect
            value={evidenceFilter}
            onChange={(value) => {
              const filter = pickOption(EVIDENCE_FILTERS, value);
              if (filter !== undefined) setEvidenceFilter(filter);
            }}
            label={t('audit.colEvidence')}
            className="w-[140px]"
          >
            <SelectItem value="all">{t('audit.all')}</SelectItem>
            <SelectItem value="yes">{t('audit.hasEvidence')}</SelectItem>
            <SelectItem value="no">{t('audit.noEvidenceFilter')}</SelectItem>
          </FilterSelect>
          {isSuperAdmin && <BulkPunishmentActionsModal />}
        </div>
      )}
    >
      <ReportQueryGate queries={[punishmentsQuery]}>
        {punishments.length === 0 ? (
          <ChartEmpty>{hasNarrowingFilter(query) ? t('audit.noPunishmentsMatchFilters') : t('audit.noPunishmentsFound')}</ChartEmpty>
        ) : (
          <ReportTable
            header={(
              <>
                <ReportHeaderCell>{t('audit.colPlayer')}</ReportHeaderCell>
                <ReportHeaderCell>{t('audit.colType')}</ReportHeaderCell>
                {showStatus && <ReportHeaderCell>{t('audit.colStatus')}</ReportHeaderCell>}
                <ReportHeaderCell>{t('audit.colStaff')}</ReportHeaderCell>
                <SortHeader field="issued" label={t('audit.colIssued')} sort={sort} onSort={toggleSort} />
                <SortHeader field="started" label={t('audit.colStarted')} sort={sort} onSort={toggleSort} />
                <SortHeader field="duration" label={t('audit.colDuration')} sort={sort} onSort={toggleSort} />
                <ReportHeaderCell>{t('audit.colRemaining')}</ReportHeaderCell>
                <ReportHeaderCell>{t('audit.colEvidence')}</ReportHeaderCell>
              </>
            )}
          >
            {punishments.map((punishment) => (
              <tr key={punishment.id} className="border-b hover:bg-muted/50">
                <td className="p-2">
                  <Button
                    variant="link"
                    className="h-auto p-0 text-left font-medium"
                    onClick={() => { openPlayerWindow(punishment.playerId, punishment.playerName); }}
                  >
                    {punishment.playerName}
                  </Button>
                </td>
                <td className="p-2">
                  <div className="flex items-center gap-1">
                    <Badge variant={punishmentTypeBadgeVariant(punishment.banLike)}>{punishment.type}</Badge>
                    <Badge variant="outline">{punishment.category}</Badge>
                  </div>
                </td>
                {showStatus && (
                  <td className="p-2">
                    <PunishmentStatusBadge status={PHASE_STATUS_KINDS[punishment.phase]} />
                  </td>
                )}
                <td className="p-2 text-muted-foreground">{punishment.staffName}</td>
                <td className="p-2">{punishment.issued ? formatDateOnly(punishment.issued) : '--'}</td>
                <td className="p-2">
                  {punishment.started ? formatDateOnly(punishment.started) : <span className="text-muted-foreground">--</span>}
                </td>
                <td className="p-2">{formatDuration(punishment.durationMs, t)}</td>
                <td className="p-2"><RemainingTime punishment={punishment} now={now} /></td>
                <td className="p-2">
                  {punishment.hasEvidence
                    ? <EvidenceChips evidence={punishment.evidence} />
                    : <span className="text-xs text-muted-foreground">{t('audit.none')}</span>}
                </td>
              </tr>
            ))}
          </ReportTable>
        )}
        {hasNextPage && (
          <div className="flex flex-col items-center gap-2 pt-4">
            {isFetchNextPageError && <p role="alert" className="text-sm text-destructive-text">{t('common.unknownError')}</p>}
            <Button variant="outline" size="sm" disabled={isFetchingNextPage} onClick={() => { void fetchNextPage(); }}>
              {isFetchingNextPage && <Loader2 className="h-4 w-4 animate-spin" />}
              {isFetchNextPageError ? t('common.retry') : t('common.showMore')}
            </Button>
          </div>
        )}
      </ReportQueryGate>
    </ReportSection>
  );
}
