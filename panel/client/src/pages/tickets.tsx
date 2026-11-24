import { useState, useEffect, useMemo, useCallback, memo } from 'react';
import { useLocation } from 'wouter';
import { useTranslation } from 'react-i18next';
import {
  Search,
  ChevronLeft,
  ChevronRight,
} from 'lucide-react';
import { formatTimeAgo } from '../utils/date-utils';
import { Button } from '@/components/ui/button';
import { StatusBadge } from '@/components/ui/status-badge';
import { Card, CardContent } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Checkbox } from '@/components/ui/checkbox';
import { Skeleton } from '@/components/ui/skeleton';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import type { Label } from '@proto/modl/v1/settings_pb.ts';
import { DEFAULT_TICKET_LIST_FILTERS, useTickets, useTicketStatusCounts, useBulkUpdateTickets, useLabels, useStaff, useAddTicketTag, useRemoveTicketTag, type TicketListItem } from '@/hooks/use-data';
import { useIsMobile } from '@/hooks/use-mobile';
import PageContainer from '@/components/layout/PageContainer';
import { FilterDropdown } from '@/components/tickets/FilterDropdown';
import { BulkActionBar } from '@/components/tickets/BulkActionBar';
import { bulkTicketActions } from '@/lib/ticket-bulk-actions';
import { LabelChip, LabelPicker } from '@/components/ui/label-chip';
import { useToast } from '@/hooks/use-toast';
import { usePermissions } from '@/hooks/use-permissions';

type LabelHandler = (ticketId: string, labelName: string) => void;

const SKELETON_ROW_IDS = [1, 2, 3, 4, 5] as const;

interface TicketLabelControlsProps {
  ticket: TicketListItem;
  labels: Label[];
  labelByName: Map<string, Label>;
  onAddLabel?: LabelHandler | undefined;
  onRemoveLabel?: LabelHandler | undefined;
}

function TicketLabelControls({ ticket, labels, labelByName, onAddLabel, onRemoveLabel }: TicketLabelControlsProps) {
  const availableLabelsForTicket = labels.filter((l) => !ticket.tags.includes(l.name));

  return (
    <>
      {ticket.tags.map((tagName) => (
        <LabelChip
          key={tagName}
          name={tagName}
          color={labelByName.get(tagName)?.color}
          onRemove={onRemoveLabel ? () => onRemoveLabel(ticket.id, tagName) : undefined}
        />
      ))}
      {onAddLabel && availableLabelsForTicket.length > 0 && (
        <div onClick={(e) => e.stopPropagation()}>
          <LabelPicker
            labels={availableLabelsForTicket}
            triggerClassName="md:opacity-0 md:group-hover:opacity-100 focus-visible:opacity-100"
            onPick={(name) => { onAddLabel(ticket.id, name); }}
          />
        </div>
      )}
    </>
  );
}

interface TicketRowProps {
  ticket: TicketListItem;
  isSelected: boolean;
  labels: Label[];
  labelByName: Map<string, Label>;
  onSelect: (ticketId: string) => void;
  onNavigate: (ticketId: string) => void;
  onAddLabel?: LabelHandler | undefined;
  onRemoveLabel?: LabelHandler | undefined;
  ticketMetaLabel: string;
  hiddenLabel: string;
  repliesCountLabel: string;
}

const TicketRow = memo(({
  ticket,
  isSelected,
  labels,
  labelByName,
  onSelect,
  onNavigate,
  onAddLabel,
  onRemoveLabel,
  ticketMetaLabel,
  hiddenLabel,
  repliesCountLabel,
}: TicketRowProps) => {
  const assigneeDisplay = ticket.assignedTo.join(', ');

  return (
    <TableRow
      className={`group border-b border-border hover:bg-muted/50 cursor-pointer ${isSelected ? 'bg-primary/5' : ''}`}
    >
      <TableCell className="w-12 px-3" onClick={(e) => e.stopPropagation()}>
        <Checkbox
          checked={isSelected}
          onCheckedChange={() => onSelect(ticket.id)}
        />
      </TableCell>
      <TableCell onClick={() => onNavigate(ticket.id)}>
        <div className="flex items-start gap-3">
          <div className="min-w-0 flex-1">
            <div className="flex items-center gap-2 flex-wrap">
              <span className="font-medium text-sm">{ticket.subject}</span>
              {ticket.hidden && <StatusBadge intent="warning">{hiddenLabel}</StatusBadge>}
              <TicketLabelControls
                ticket={ticket}
                labels={labels}
                labelByName={labelByName}
                onAddLabel={onAddLabel}
                onRemoveLabel={onRemoveLabel}
              />
            </div>
            <div className="text-xs text-muted-foreground mt-0.5">
              {ticketMetaLabel}
            </div>
          </div>
        </div>
      </TableCell>
      <TableCell className="text-right text-sm text-muted-foreground">
        {assigneeDisplay !== '' && (
          <span>{assigneeDisplay}</span>
        )}
      </TableCell>
      <TableCell className="text-right text-sm text-muted-foreground whitespace-nowrap">
        {repliesCountLabel}
      </TableCell>
      <TableCell className="text-right text-sm text-muted-foreground whitespace-nowrap">
        {ticket.lastReply ? formatTimeAgo(new Date(ticket.lastReply.created)) : '-'}
      </TableCell>
    </TableRow>
  );
});

TicketRow.displayName = 'TicketRow';

interface TicketCardProps {
  ticket: TicketListItem;
  isSelected: boolean;
  labels: Label[];
  labelByName: Map<string, Label>;
  onSelect: (ticketId: string) => void;
  onNavigate: (ticketId: string) => void;
  onAddLabel?: LabelHandler | undefined;
  onRemoveLabel?: LabelHandler | undefined;
  ticketMetaLabel: string;
  hiddenLabel: string;
}

const TicketCard = memo(({
  ticket,
  isSelected,
  labels,
  labelByName,
  onSelect,
  onNavigate,
  onAddLabel,
  onRemoveLabel,
  ticketMetaLabel,
  hiddenLabel,
}: TicketCardProps) => {
  const assigneeDisplay = ticket.assignedTo.join(', ');

  return (
    <div
      className={`flex items-start gap-3 border-b border-border px-4 py-3 last:border-b-0 ${isSelected ? 'bg-primary/5' : ''}`}
      onClick={() => onNavigate(ticket.id)}
    >
      <div className="pt-0.5" onClick={(e) => e.stopPropagation()}>
        <Checkbox
          checked={isSelected}
          onCheckedChange={() => onSelect(ticket.id)}
        />
      </div>
      <div className="flex-1 min-w-0 space-y-1">
        <div className="flex flex-wrap items-center gap-2">
          <span className="font-medium text-sm">{ticket.subject}</span>
          {ticket.hidden && (
            <span className="px-1.5 py-0.5 rounded-md text-xs font-medium bg-warning/10 text-warning">
              {hiddenLabel}
            </span>
          )}
          <TicketLabelControls
            ticket={ticket}
            labels={labels}
            labelByName={labelByName}
            onAddLabel={onAddLabel}
            onRemoveLabel={onRemoveLabel}
          />
        </div>
        <div className="text-xs text-muted-foreground">{ticketMetaLabel}</div>
        {assigneeDisplay !== '' && (
          <div className="text-xs text-muted-foreground">{assigneeDisplay}</div>
        )}
      </div>
    </div>
  );
});

TicketCard.displayName = 'TicketCard';

const TableLoadingSkeleton = () => (
  <div className="divide-y divide-border">
    {SKELETON_ROW_IDS.map((rowId) => (
      <div key={rowId} className="flex items-center gap-3 px-3 py-4">
        <Skeleton className="h-5 w-5 shrink-0" />
        <Skeleton className="h-4 w-4 rounded-full shrink-0" />
        <div className="flex-1 min-w-0 space-y-2">
          <Skeleton className="h-4 w-2/3" />
          <Skeleton className="h-3 w-1/3" />
        </div>
        <Skeleton className="h-4 w-24 hidden md:block" />
        <Skeleton className="h-4 w-16 hidden md:block" />
      </div>
    ))}
  </div>
);

const Tickets = () => {
  const [, setLocation] = useLocation();
  const isMobile = useIsMobile();
  const { toast } = useToast();
  const { t } = useTranslation();
  const { canListStaff, canTagTickets, hasPermission } = usePermissions();

  // State for filters
  const [statusFilter, setStatusFilter] = useState<'open' | 'closed'>(DEFAULT_TICKET_LIST_FILTERS.status);
  const [searchQuery, setSearchQuery] = useState<string>(DEFAULT_TICKET_LIST_FILTERS.search);
  const [debouncedSearchQuery, setDebouncedSearchQuery] = useState<string>(DEFAULT_TICKET_LIST_FILTERS.search);
  const [authorFilter, setAuthorFilter] = useState<string[]>([]);
  const [labelFilters, setLabelFilters] = useState<string[]>(() => [...DEFAULT_TICKET_LIST_FILTERS.labels]);
  const [assigneeFilter, setAssigneeFilter] = useState<string[]>(() => [...DEFAULT_TICKET_LIST_FILTERS.assignees]);
  const [typeFilter, setTypeFilter] = useState<string[]>(() => [...DEFAULT_TICKET_LIST_FILTERS.types]);
  const [sortOption, setSortOption] = useState<string>(DEFAULT_TICKET_LIST_FILTERS.sort);
  const [currentPage, setCurrentPage] = useState<number>(DEFAULT_TICKET_LIST_FILTERS.page);

  // Multi-select state
  const [selectedTickets, setSelectedTickets] = useState<Set<string>>(() => new Set());

  // Debounce search
  useEffect(() => {
    const timer = setTimeout(() => {
      setDebouncedSearchQuery(searchQuery);
      setCurrentPage(1);
    }, 300);
    return () => clearTimeout(timer);
  }, [searchQuery]);

  const resetPageAndSelection = useCallback(() => {
    setCurrentPage(1);
    setSelectedTickets(new Set());
  }, []);

  const handleStatusFilter = useCallback((status: 'open' | 'closed') => {
    setStatusFilter(status);
    resetPageAndSelection();
  }, [resetPageAndSelection]);

  const handleLabelFiltersChange = useCallback((value: string[]) => {
    setLabelFilters(value);
    resetPageAndSelection();
  }, [resetPageAndSelection]);

  const handleAssigneeFilterChange = useCallback((value: string[]) => {
    setAssigneeFilter(value);
    resetPageAndSelection();
  }, [resetPageAndSelection]);

  const handleTypeFilterChange = useCallback((value: string[]) => {
    setTypeFilter(value);
    resetPageAndSelection();
  }, [resetPageAndSelection]);

  const handleSortChange = useCallback((value: string) => {
    setSortOption(value);
    resetPageAndSelection();
  }, [resetPageAndSelection]);

  const singleAuthorFilter = authorFilter.length === 1 ? authorFilter[0] ?? '' : '';

  // Fetch data
  const { data: ticketsResponse, isLoading } = useTickets({
    page: currentPage,
    limit: DEFAULT_TICKET_LIST_FILTERS.limit,
    search: debouncedSearchQuery,
    status: statusFilter,
    types: typeFilter,
    author: singleAuthorFilter,
    labels: labelFilters,
    assignees: assigneeFilter,
    sort: sortOption,
  });

  const { data: statusCounts } = useTicketStatusCounts({
    search: debouncedSearchQuery,
    types: typeFilter,
    author: singleAuthorFilter,
    labels: labelFilters,
    assignees: assigneeFilter,
  });

  const { data: labelsData } = useLabels();
  const { data: staffData } = useStaff({ enabled: canListStaff });
  const bulkUpdateMutation = useBulkUpdateTickets();
  const { mutate: addTicketTag } = useAddTicketTag();
  const { mutate: removeTicketTag } = useRemoveTicketTag();

  const tickets = ticketsResponse?.tickets ?? [];
  const pagination = ticketsResponse?.pagination ?? {
    current: 1,
    total: 1,
    totalTickets: 0,
    hasNext: false,
    hasPrev: false,
  };

  const labels = useMemo(() => labelsData ?? [], [labelsData]);

  const labelByName = useMemo(() => {
    const map = new Map<string, Label>();
    for (const label of labels) {
      map.set(label.name, label);
    }
    return map;
  }, [labels]);

  const labelFilterOptions = useMemo(
    () => labels.map((l) => ({ value: l.name, label: l.name, color: l.color })),
    [labels]
  );

  const staffMembers = useMemo(
    () => (staffData ?? []).map((s) => {
      const name = s.username || (s.email.split('@')[0] ?? '');
      return { value: name, label: name || t('common.unknown') };
    }),
    [staffData, t]
  );

  const assigneeFilterOptions = useMemo(
    () => [{ value: 'none', label: t('tickets.unassigned') }, ...staffMembers],
    [staffMembers, t]
  );

  const hasActiveFilters =
    debouncedSearchQuery.length > 0 ||
    authorFilter.length > 0 ||
    labelFilters.length > 0 ||
    assigneeFilter.length > 0 ||
    typeFilter.length > 0;

  const clearAllFilters = useCallback(() => {
    setSearchQuery('');
    setDebouncedSearchQuery('');
    setAuthorFilter([]);
    setLabelFilters([]);
    setAssigneeFilter([]);
    setTypeFilter([]);
    setCurrentPage(1);
    setSelectedTickets(new Set());
  }, []);

  // Selection handlers
  const handleSelectAll = () => {
    if (selectedTickets.size === tickets.length) {
      setSelectedTickets(new Set());
    } else {
      setSelectedTickets(new Set(tickets.map((ticket) => ticket.id)));
    }
  };

  const handleSelectTicket = useCallback((ticketId: string) => {
    setSelectedTickets((prev) => {
      const next = new Set(prev);
      if (next.has(ticketId)) {
        next.delete(ticketId);
      } else {
        next.add(ticketId);
      }
      return next;
    });
  }, []);

  const runBulkUpdate = (
    update: Omit<Parameters<typeof bulkUpdateMutation.mutate>[0], 'ticketIds'>,
    successDescription: string,
    failureDescription: string,
  ) => {
    bulkUpdateMutation.mutate(
      { ticketIds: Array.from(selectedTickets), ...update },
      {
        onSuccess: () => {
          toast({ title: t('toast.success'), description: successDescription });
          setSelectedTickets(new Set());
        },
        onError: () => {
          toast({ title: t('toast.error'), description: failureDescription, variant: 'destructive' });
        },
      },
    );
  };

  const handleBulkMarkAs = (status: 'open' | 'closed') => {
    const statusLabel = status === 'open' ? t('status.open') : t('status.closed');
    runBulkUpdate(
      { locked: status === 'closed' },
      t('tickets.bulkMarkedAs', { count: selectedTickets.size, status: statusLabel }),
      t('tickets.bulkMarkFailed', { status: statusLabel }),
    );
  };

  const handleBulkAddLabels = (labelsToAdd: string[]) => {
    runBulkUpdate(
      { addLabels: labelsToAdd },
      t('tickets.bulkLabelsAdded', { count: selectedTickets.size }),
      t('tickets.bulkLabelsFailed'),
    );
  };

  const handleBulkAssign = (assignees: string[]) => {
    runBulkUpdate(
      { assignTo: assignees.join(',') },
      t('tickets.bulkAssigned', { ticketCount: selectedTickets.size, staffCount: assignees.length }),
      t('tickets.bulkAssignFailed'),
    );
  };

  const handleNavigateToTicket = useCallback((ticketId: string) => {
    if (ticketId === '') return;
    const safeTicketId = ticketId.replace('#', 'ID-');
    setTimeout(() => setLocation(`/panel/tickets/${safeTicketId}`), 50);
  }, [setLocation]);

  const handleAddLabel = useCallback((ticketId: string, labelName: string) => {
    addTicketTag({ id: ticketId, tag: labelName }, {
      onError: () => {
        toast({ title: t('toast.error'), description: t('tickets.addLabelFailed'), variant: 'destructive' });
      },
    });
  }, [addTicketTag, toast, t]);

  const handleRemoveLabel = useCallback((ticketId: string, labelName: string) => {
    removeTicketTag({ id: ticketId, tag: labelName }, {
      onError: () => {
        toast({ title: t('toast.error'), description: t('tickets.removeLabelFailed'), variant: 'destructive' });
      },
    });
  }, [removeTicketTag, toast, t]);

  // Type filter options
  const typeOptions = [
    { value: 'support', label: t('tickets.support') },
    { value: 'bug', label: t('tickets.bugReport') },
    { value: 'player', label: t('tickets.playerReport') },
    { value: 'chat', label: t('tickets.chatReport') },
    { value: 'appeal', label: t('tickets.banAppeal') },
    { value: 'staff', label: t('tickets.staffApplication') },
  ];

  // Sort options
  const sortOptions = [
    { value: 'newest', label: t('tickets.newest') },
    { value: 'oldest', label: t('tickets.oldest') },
    { value: 'recently-updated', label: t('tickets.recentlyUpdated') },
    { value: 'least-recently-updated', label: t('tickets.leastRecentlyUpdated') },
  ];

  return (
    <PageContainer>
      <div className="flex flex-col space-y-4">
        {/* Header */}
        <div className="flex flex-col gap-4 md:flex-row md:justify-between md:items-center">
          <h2 className="text-xl font-semibold">{t('tickets.title')}</h2>
          <div className="relative w-full md:w-80">
            <Search className="absolute left-3 top-1/2 transform -translate-y-1/2 h-4 w-4 text-muted-foreground" />
            <Input
              placeholder={t('tickets.searchTickets')}
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              className="pl-10"
            />
          </div>
        </div>

        {/* Filter bar */}
        <div className="flex flex-wrap items-center gap-2 border-b border-border pb-4">
          {/* Status tabs */}
          <div className="flex items-center gap-1 mr-2">
            <Button
              variant={statusFilter === 'open' ? 'secondary' : 'ghost'}
              size="sm"
              onClick={() => handleStatusFilter('open')}
              aria-pressed={statusFilter === 'open'}
              className="h-8"
            >
              {statusCounts?.open ?? 0} {t('status.open')}
            </Button>
            <Button
              variant={statusFilter === 'closed' ? 'secondary' : 'ghost'}
              size="sm"
              onClick={() => handleStatusFilter('closed')}
              aria-pressed={statusFilter === 'closed'}
              className="h-8"
            >
              {statusCounts?.closed ?? 0} {t('status.closed')}
            </Button>
          </div>

          <div className="hidden md:block h-6 w-px bg-border mx-1" />

          {/* Filter dropdowns */}
          <FilterDropdown
            label={t('tickets.label')}
            options={labelFilterOptions}
            selected={labelFilters}
            onChange={handleLabelFiltersChange}
            multiSelect
          />

          <FilterDropdown
            label={t('tickets.assignee')}
            options={assigneeFilterOptions}
            selected={assigneeFilter}
            onChange={handleAssigneeFilterChange}
            multiSelect
          />

          <FilterDropdown
            label={t('table.type')}
            options={typeOptions}
            selected={typeFilter}
            onChange={handleTypeFilterChange}
            multiSelect
          />

          {/* Sort */}
          <Select value={sortOption} onValueChange={handleSortChange}>
            <SelectTrigger className="w-44 h-8 md:ml-auto" aria-label={t('tickets.sort')}>
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {sortOptions.map((opt) => (
                <SelectItem key={opt.value} value={opt.value}>
                  {opt.label}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>

        {/* Bulk action bar */}
        {selectedTickets.size > 0 && (
          <BulkActionBar
            selectedCount={selectedTickets.size}
            onClearSelection={() => setSelectedTickets(new Set())}
            onMarkAs={handleBulkMarkAs}
            onAddLabels={handleBulkAddLabels}
            onAssign={handleBulkAssign}
            availableLabels={labels}
            staffMembers={staffMembers}
            actions={bulkTicketActions(hasPermission)}
            isLoading={bulkUpdateMutation.isPending}
          />
        )}

        {/* Ticket list */}
        <Card className="shadow-card">
          <CardContent className="p-0">
            {isLoading ? (
              <TableLoadingSkeleton />
            ) : tickets.length === 0 ? (
              <div className="flex flex-col items-center justify-center gap-3 py-8 text-sm text-muted-foreground">
                <span>{t('tickets.noTickets')}</span>
                {hasActiveFilters && (
                  <Button variant="outline" size="sm" onClick={clearAllFilters}>
                    {t('tickets.clearFilters')}
                  </Button>
                )}
              </div>
            ) : isMobile ? (
              <div>
                <label className="flex items-center gap-3 border-b border-border px-4 py-3 text-sm text-muted-foreground">
                  <Checkbox
                    checked={selectedTickets.size === tickets.length && tickets.length > 0}
                    onCheckedChange={handleSelectAll}
                  />
                  {t('common.selectAll')}
                </label>
                {tickets.map((ticket) => (
                  <TicketCard
                    key={ticket.id}
                    ticket={ticket}
                    isSelected={selectedTickets.has(ticket.id)}
                    labels={labels}
                    labelByName={labelByName}
                    onSelect={handleSelectTicket}
                    onNavigate={handleNavigateToTicket}
                    onAddLabel={canTagTickets ? handleAddLabel : undefined}
                    onRemoveLabel={canTagTickets ? handleRemoveLabel : undefined}
                    ticketMetaLabel={t('tickets.ticketMeta', { id: ticket.id, timeAgo: formatTimeAgo(new Date(ticket.date)), by: ticket.reportedByName || ticket.reportedBy })}
                    hiddenLabel={t('tickets.hidden')}
                  />
                ))}
              </div>
            ) : (
              <Table>
                <TableHeader>
                  <TableRow className="hover:bg-transparent">
                    <TableHead className="w-12 px-3">
                      <Checkbox
                        checked={selectedTickets.size === tickets.length && tickets.length > 0}
                        onCheckedChange={handleSelectAll}
                        aria-label={t('common.selectAll')}
                      />
                    </TableHead>
                    <TableHead>{t('table.ticket')}</TableHead>
                    <TableHead className="text-right">{t('table.assignee')}</TableHead>
                    <TableHead className="text-right">{t('common.replies')}</TableHead>
                    <TableHead className="text-right">{t('table.lastReply')}</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {tickets.map((ticket) => (
                    <TicketRow
                      key={ticket.id}
                      ticket={ticket}
                      isSelected={selectedTickets.has(ticket.id)}
                      labels={labels}
                      labelByName={labelByName}
                      onSelect={handleSelectTicket}
                      onNavigate={handleNavigateToTicket}
                      onAddLabel={canTagTickets ? handleAddLabel : undefined}
                      onRemoveLabel={canTagTickets ? handleRemoveLabel : undefined}
                      ticketMetaLabel={t('tickets.ticketMeta', { id: ticket.id, timeAgo: formatTimeAgo(new Date(ticket.date)), by: ticket.reportedByName || ticket.reportedBy })}
                      hiddenLabel={t('tickets.hidden')}
                      repliesCountLabel={t('common.replyCount', { count: ticket.replyCount })}
                    />
                  ))}
                </TableBody>
              </Table>
            )}
          </CardContent>
        </Card>

        {/* Pagination */}
        {pagination.total > 1 && (
          <div className="flex flex-col space-y-2 md:flex-row md:space-y-0 md:justify-between md:items-center">
            <div className="text-sm text-muted-foreground text-center md:text-left">
              {t('tickets.showingTickets', { from: ((pagination.current - 1) * DEFAULT_TICKET_LIST_FILTERS.limit) + 1, to: Math.min(pagination.current * DEFAULT_TICKET_LIST_FILTERS.limit, pagination.totalTickets), total: pagination.totalTickets })}
            </div>
            <div className="flex justify-center space-x-1">
              <Button
                variant="outline"
                size="sm"
                disabled={!pagination.hasPrev}
                onClick={() => setCurrentPage((p) => p - 1)}
              >
                <ChevronLeft className="h-4 w-4" />
              </Button>
              {Array.from({ length: Math.min(5, pagination.total) }, (_, i) => {
                const page = Math.max(1, pagination.current - 2) + i;
                if (page > pagination.total) return null;
                return (
                  <Button
                    key={page}
                    variant={page === pagination.current ? 'default' : 'outline'}
                    size="sm"
                    onClick={() => setCurrentPage(page)}
                  >
                    {page}
                  </Button>
                );
              })}
              <Button
                variant="outline"
                size="sm"
                disabled={!pagination.hasNext}
                onClick={() => setCurrentPage((p) => p + 1)}
              >
                <ChevronRight className="h-4 w-4" />
              </Button>
            </div>
          </div>
        )}
      </div>
    </PageContainer>
  );
};

export default Tickets;
