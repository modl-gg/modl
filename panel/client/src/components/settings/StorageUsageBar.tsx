import { useTranslation } from 'react-i18next';
import type { StorageBackendId, StorageUsageGroup, StorageUsageGroupId } from '@/hooks/data/operations';
import { formatFileSize } from '@/utils/file-utils';

const GROUP_COLORS: Record<StorageUsageGroupId, string> = {
  replays: 'bg-chart-1',
  evidence: 'bg-chart-2',
  tickets: 'bg-chart-3',
  articles: 'bg-chart-4',
  icons: 'bg-chart-5',
  other: 'bg-muted-foreground',
};

const MIN_SEGMENT_PERCENT = 0.6;

interface StorageUsageBarProps {
  backend: StorageBackendId;
  usedBytes: number;
  capacityBytes: number;
  usage: readonly StorageUsageGroup[];
}

export function StorageUsageBar({ backend, usedBytes, capacityBytes, usage }: StorageUsageBarProps) {
  const { t } = useTranslation();
  const scale = capacityBytes > 0 ? Math.max(capacityBytes, usedBytes) : usedBytes;
  const groups = usage.filter((entry) => entry.bytes > 0);
  const freeBytes = capacityBytes > 0 ? Math.max(0, capacityBytes - usedBytes) : 0;
  const percentOf = (bytes: number) => (scale > 0 ? Math.max(MIN_SEGMENT_PERCENT, (bytes / scale) * 100) : 0);

  return (
    <div className="max-w-2xl space-y-2">
      <div className="flex flex-wrap items-baseline justify-between gap-2 text-sm">
        <span className="font-medium">{t(`settings.storage.backendName.${backend}`)}</span>
        <span className="tabular-nums text-muted-foreground">
          {capacityBytes > 0
            ? t('settings.storage.usedOf', { used: formatFileSize(usedBytes), total: formatFileSize(capacityBytes) })
            : t('settings.storage.usedOnly', { used: formatFileSize(usedBytes) })}
        </span>
      </div>
      <div className="flex h-6 w-full overflow-hidden rounded-md bg-muted" aria-hidden="true">
        {groups.map((entry) => (
          <div
            key={entry.group}
            className={`${GROUP_COLORS[entry.group]} h-full border-r border-background last:border-r-0`}
            style={{ width: `${String(percentOf(entry.bytes))}%` }}
          />
        ))}
        {freeBytes > 0 && (
          <div className="flex h-full flex-1 items-center justify-center text-xs font-medium tabular-nums text-muted-foreground">
            {formatFileSize(freeBytes)}
          </div>
        )}
      </div>
      <ul className="flex flex-wrap gap-x-4 gap-y-1 text-xs text-muted-foreground">
        {usage.map((entry) => (
          <li key={entry.group} className="flex items-center gap-1.5">
            <span className={`h-2 w-2 rounded-full ${GROUP_COLORS[entry.group]}`} aria-hidden="true" />
            <span>{t(`settings.storage.group.${entry.group}`)}</span>
            <span className="tabular-nums text-foreground/80">{formatFileSize(entry.bytes)}</span>
          </li>
        ))}
      </ul>
    </div>
  );
}
