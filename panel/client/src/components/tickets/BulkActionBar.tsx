import { useState } from 'react';
import { X } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { FilterDropdown } from './FilterDropdown';
import { useTranslation } from 'react-i18next';
import type { BulkTicketActions } from '@/lib/ticket-bulk-actions';

interface Label {
  id: string;
  name: string;
  color: string;
}

interface BulkActionBarProps {
  selectedCount: number;
  onClearSelection: () => void;
  onMarkAs: (status: 'open' | 'closed') => void;
  onAddLabels: (labels: string[]) => void;
  onAssign: (assignees: string[]) => void;
  availableLabels: Label[];
  staffMembers: { value: string; label: string }[];
  actions: BulkTicketActions;
  isLoading?: boolean;
}

export function BulkActionBar({
  selectedCount,
  onClearSelection,
  onMarkAs,
  onAddLabels,
  onAssign,
  availableLabels,
  staffMembers,
  actions,
  isLoading = false,
}: BulkActionBarProps) {
  const { t } = useTranslation();
  const [selectedLabels, setSelectedLabels] = useState<string[]>([]);
  const [selectedAssignees, setSelectedAssignees] = useState<string[]>([]);
  const [selectedStatus, setSelectedStatus] = useState<string[]>([]);

  const handleAddLabels = () => {
    if (selectedLabels.length > 0) {
      onAddLabels(selectedLabels);
      setSelectedLabels([]);
    }
  };

  const handleAssign = () => {
    if (selectedAssignees.length > 0) {
      onAssign(selectedAssignees);
      setSelectedAssignees([]);
    }
  };

  const handleMarkAs = () => {
    const status = selectedStatus[0];
    if (status === 'open' || status === 'closed') {
      onMarkAs(status);
      setSelectedStatus([]);
    }
  };

  const labelOptions = availableLabels.map((label) => ({
    value: label.name,
    label: label.name,
    color: label.color,
  }));

  const statusOptions = [
    { value: 'open', label: t('status.open') },
    { value: 'closed', label: t('status.closed') },
  ];

  return (
    <div className="flex flex-wrap items-center gap-3 p-3 bg-muted/50 border border-border rounded-lg">
      <div className="flex flex-wrap items-center gap-3">
        <div className="flex items-center gap-2">
          <Button
            variant="ghost"
            size="sm"
            onClick={onClearSelection}
            className="h-7 w-7 p-0"
            disabled={isLoading}
            aria-label={t('tickets.bulk.clearSelection')}
          >
            <X className="h-4 w-4" />
          </Button>
          <span className="text-sm font-medium">
            {t('tickets.bulk.selected', { count: selectedCount })}
          </span>
        </div>

        <div className="flex flex-wrap items-center gap-2">
          {actions.changeStatus && (
            <div className="flex items-center gap-1">
              <div className="h-4 w-px bg-border mr-2" />
              <FilterDropdown
                label={t('tickets.bulk.markAs')}
                options={statusOptions}
                selected={selectedStatus}
                onChange={setSelectedStatus}
                placeholder={t('tickets.bulk.selectStatus')}
              />
              {selectedStatus.length > 0 && (
                <Button size="sm" onClick={handleMarkAs} disabled={isLoading} className="h-8">
                  {t('tickets.bulk.apply')}
                </Button>
              )}
            </div>
          )}

          {actions.label && (
            <div className="flex items-center gap-1">
              <div className="h-4 w-px bg-border mr-2" />
              <FilterDropdown
                label={t('tickets.bulk.addLabel')}
                options={labelOptions}
                selected={selectedLabels}
                onChange={setSelectedLabels}
                multiSelect
                searchable
                placeholder={t('tickets.bulk.selectLabels')}
              />
              {selectedLabels.length > 0 && (
                <Button size="sm" onClick={handleAddLabels} disabled={isLoading} className="h-8">
                  {t('tickets.bulk.apply')}
                </Button>
              )}
            </div>
          )}

          {actions.assign && (
            <div className="flex items-center gap-1">
              <div className="h-4 w-px bg-border mr-2" />
              <FilterDropdown
                label={t('tickets.bulk.assign')}
                options={staffMembers}
                selected={selectedAssignees}
                onChange={setSelectedAssignees}
                multiSelect
                searchable
                placeholder={t('tickets.bulk.selectAssignees')}
              />
              {selectedAssignees.length > 0 && (
                <Button size="sm" onClick={handleAssign} disabled={isLoading} className="h-8">
                  {t('tickets.bulk.apply')}
                </Button>
              )}
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
