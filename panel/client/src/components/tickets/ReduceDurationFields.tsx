import { useId } from 'react';
import { useTranslation } from 'react-i18next';
import { Checkbox } from '@/components/ui/checkbox';

export type ReduceDurationUnit = 'hours' | 'days' | 'weeks' | 'months';

export interface ReduceDuration {
  value: number;
  unit: ReduceDurationUnit;
  isPermanent: boolean;
}

const UNIT_LABEL_KEYS = {
  hours: 'punishment.hours',
  days: 'punishment.days',
  weeks: 'punishment.weeks',
  months: 'punishment.months',
} as const satisfies Record<ReduceDurationUnit, string>;

const UNITS: readonly ReduceDurationUnit[] = ['hours', 'days', 'weeks', 'months'];

function reduceUnitOf(value: string): ReduceDurationUnit | undefined {
  return UNITS.find((unit) => unit === value);
}

interface ReduceDurationFieldsProps {
  duration: ReduceDuration;
  onChange: (duration: ReduceDuration) => void;
}

export function ReduceDurationFields({ duration, onChange }: ReduceDurationFieldsProps) {
  const { t } = useTranslation();
  const fieldId = useId();
  const permanentId = `${fieldId}-permanent`;
  const valueId = `${fieldId}-value`;
  const unitId = `${fieldId}-unit`;

  return (
    <div className="mb-3 p-3 border rounded-md bg-muted/10">
      <div className="flex items-center mb-2">
        <Checkbox
          id={permanentId}
          checked={duration.isPermanent}
          onCheckedChange={(checked) => {
            onChange({ ...duration, isPermanent: checked === true });
          }}
        />
        <label htmlFor={permanentId} className="ml-2 text-sm font-medium">
          {t('ticket.reduce.permanentBan')}
        </label>
      </div>

      {!duration.isPermanent && (
        <div className="grid grid-cols-2 gap-2">
          <div>
            <label htmlFor={valueId} className="text-xs text-muted-foreground">{t('punishment.duration')}</label>
            <input
              id={valueId}
              type="number"
              className="w-full mt-1 h-8 rounded-md border border-input bg-background px-3 py-1 text-sm"
              value={duration.value > 0 ? duration.value : ''}
              onChange={(event) => {
                onChange({ ...duration, value: Number.parseInt(event.target.value, 10) || 0 });
              }}
              min={1}
            />
          </div>
          <div>
            <label htmlFor={unitId} className="text-xs text-muted-foreground">{t('player.unit')}</label>
            <select
              id={unitId}
              className="w-full mt-1 h-8 rounded-md border border-input bg-background px-3 py-1 text-sm"
              value={duration.unit}
              onChange={(event) => {
                onChange({ ...duration, unit: reduceUnitOf(event.target.value) ?? duration.unit });
              }}
            >
              {UNITS.map((unit) => (
                <option key={unit} value={unit}>{t(UNIT_LABEL_KEYS[unit])}</option>
              ))}
            </select>
          </div>
        </div>
      )}
    </div>
  );
}
