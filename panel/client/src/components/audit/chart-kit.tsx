import type { ReactNode } from 'react';

const CHART_PALETTE = [
  'var(--color-chart-1)',
  'var(--color-chart-3)',
  'var(--color-chart-4)',
  'var(--color-chart-2)',
  'var(--color-chart-5)',
  'var(--color-muted-foreground)',
] as const;

export const CHART_GRID_PROPS = { vertical: false, stroke: 'var(--color-border)' } as const;
export const CHART_AXIS_PROPS = {
  stroke: 'var(--color-border)',
  tick: { fill: 'var(--color-muted-foreground)' },
  fontSize: 12,
} as const;
export const CHART_BAR_STYLE = { filter: 'none' } as const;

export function withPaletteFill<T extends object>(items: readonly T[]): (T & { fill: string })[] {
  return items.map((item, index) => ({ ...item, fill: CHART_PALETTE[index % CHART_PALETTE.length] ?? CHART_PALETTE[0] }));
}

export function formatPieSliceLabel(name: string | undefined, percent: number | undefined): string {
  return `${name ?? ''} ${((percent ?? 0) * 100).toFixed(0)}%`;
}

interface ChartTooltipEntry {
  dataKey: string;
  name?: string;
  value: number | string;
  color?: string;
}

interface ChartTooltipProps {
  active?: boolean;
  payload?: ChartTooltipEntry[];
  label?: string | number;
  formatValue?: (value: number | string) => string;
  formatName?: (name: string) => string;
  formatLabel?: (label: string | number) => string;
}

export function ChartTooltip({ active, payload, label, formatValue, formatName, formatLabel }: ChartTooltipProps) {
  if (active !== true || payload === undefined || payload.length === 0) return null;
  return (
    <div className="pointer-events-none z-50 rounded-lg border border-border bg-background p-3 shadow-lg">
      {label !== undefined && label !== '' && <p className="mb-2 text-sm font-medium">{formatLabel ? formatLabel(label) : label}</p>}
      {payload.map((entry) => {
        const name = entry.name || entry.dataKey;
        return (
          <div key={entry.dataKey} className="flex items-center gap-2 text-sm">
            <span className="h-3 w-3 shrink-0 rounded-full" style={{ backgroundColor: entry.color }} />
            <span className="text-muted-foreground">{formatName ? formatName(name) : name}:</span>
            <span className="font-medium">{formatValue ? formatValue(entry.value) : entry.value}</span>
          </div>
        );
      })}
    </div>
  );
}

export function ChartEmpty({ children }: { children: ReactNode }) {
  return <p className="py-8 text-center text-sm text-muted-foreground">{children}</p>;
}

export interface ChartLegendItem {
  label: string;
  swatchClassName: string;
}

export function ChartLegend({ items }: { items: readonly ChartLegendItem[] }) {
  return (
    <ul className="mt-4 flex justify-center gap-6 text-sm">
      {items.map((item) => (
        <li key={item.label} className="flex items-center gap-2">
          <span className={`h-3 w-3 rounded-full ${item.swatchClassName}`} />
          <span className="text-muted-foreground">{item.label}</span>
        </li>
      ))}
    </ul>
  );
}
