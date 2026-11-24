import i18n from '@/lib/i18n';

const BYTES_PER_STEP = 1024;
const SIZE_UNITS = ['byte', 'kilobyte', 'megabyte', 'gigabyte', 'terabyte'] as const;

function unitIndexFor(bytes: number): number {
  if (bytes < BYTES_PER_STEP) return 0;
  return Math.min(Math.floor(Math.log(bytes) / Math.log(BYTES_PER_STEP)), SIZE_UNITS.length - 1);
}

export const formatFileSize = (bytes: number): string => {
  const index = unitIndexFor(bytes);
  const value = bytes / Math.pow(BYTES_PER_STEP, index);
  return new Intl.NumberFormat(i18n.language, {
    style: 'unit',
    unit: SIZE_UNITS[index] ?? 'byte',
    unitDisplay: index === 0 ? 'long' : 'short',
    maximumFractionDigits: 2,
  }).format(value);
};
