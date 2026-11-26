import { useTranslation } from 'react-i18next';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { wholeNumberInRange, type WholeNumberRange } from '@/lib/whole-number';
import { FIELD_WIDTH } from './layout/SettingsSections';

interface DaysFieldProps {
  id: string;
  label: string;
  hint?: string | undefined;
  value: string;
  range: WholeNumberRange;
  disabled: boolean;
  onChange: (value: string) => void;
}

export function DaysField({ id, label, hint, value, range, disabled, onChange }: DaysFieldProps) {
  const { t } = useTranslation();
  const invalid = wholeNumberInRange(value, range) === null;
  const hintId = `${id}-hint`;
  const message = invalid ? t('settings.dataRetention.invalidDays', { min: range.min, max: range.max }) : hint;
  return (
    <div className="space-y-2">
      <Label htmlFor={id}>{label}</Label>
      <div className="flex items-center gap-2">
        <Input
          id={id}
          type="number"
          inputMode="numeric"
          min={range.min}
          max={range.max}
          step={1}
          value={value}
          onChange={(event) => { onChange(event.target.value); }}
          disabled={disabled}
          aria-invalid={invalid}
          aria-describedby={message === undefined ? undefined : hintId}
          className={FIELD_WIDTH.number}
        />
        <span className="text-sm text-muted-foreground">{t('settings.dataRetention.days')}</span>
      </div>
      {message !== undefined && (
        <p id={hintId} className={invalid ? 'text-sm text-destructive-text' : 'text-xs text-muted-foreground'}>{message}</p>
      )}
    </div>
  );
}
