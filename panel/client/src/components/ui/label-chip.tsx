import { useTranslation } from 'react-i18next';
import { Plus } from 'lucide-react';
import { Chip, ChipRemoveButton, ColorDot } from '@/components/ui/chip';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { cn } from '@/lib/utils';

export const DEFAULT_LABEL_COLOR = '#6b7280';

interface LabelChipProps {
  name: string;
  color: string | undefined;
  onRemove?: (() => void) | undefined;
}

export function LabelChip({ name, color, onRemove }: LabelChipProps) {
  const { t } = useTranslation();
  return (
    <Chip>
      <ColorDot color={color || DEFAULT_LABEL_COLOR} />
      <span className="truncate">{name}</span>
      {onRemove !== undefined && (
        <ChipRemoveButton
          label={t('tickets.removeLabel', { label: name })}
          onClick={(event) => {
            event.stopPropagation();
            onRemove();
          }}
        />
      )}
    </Chip>
  );
}

export interface PickableLabel {
  id: string;
  name: string;
  color: string;
}

interface LabelPickerProps {
  labels: readonly PickableLabel[];
  onPick: (name: string) => void;
  triggerClassName?: string | undefined;
}

export function LabelPicker({ labels, onPick, triggerClassName }: LabelPickerProps) {
  const { t } = useTranslation();
  if (labels.length === 0) return null;
  return (
    <Popover>
      <PopoverTrigger asChild>
        <button
          type="button"
          aria-label={t('tickets.bulk.addLabel')}
          className={cn(
            'flex h-5 w-5 items-center justify-center rounded-full border border-dashed border-muted-foreground/30 text-muted-foreground transition-colors hover:border-primary hover:text-primary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring',
            triggerClassName,
          )}
        >
          <Plus className="h-3 w-3" aria-hidden="true" />
        </button>
      </PopoverTrigger>
      <PopoverContent className="w-48 p-1" align="start">
        {labels.map((label) => (
          <button
            key={label.id}
            type="button"
            className="flex w-full items-center gap-2 rounded px-2 py-1.5 text-left text-sm transition-colors hover:bg-muted"
            onClick={() => { onPick(label.name); }}
          >
            <ColorDot color={label.color || DEFAULT_LABEL_COLOR} className="h-3 w-3" />
            <span className="truncate">{label.name}</span>
          </button>
        ))}
      </PopoverContent>
    </Popover>
  );
}
