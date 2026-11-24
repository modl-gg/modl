import { useTranslation } from 'react-i18next';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import {
  DURATION_KINDS,
  DURATION_UNITS,
  OFFENSE_LEVELS,
  type DurationKind,
  type DurationSetting,
  type DurationUnit,
  type OffenseDurations,
  type OffenseLevel,
} from '@/hooks/data/punishment-types';

const OFFENSE_LABEL_KEYS: Record<OffenseLevel, string> = {
  first: 'settings.punishmentTypeDialog.firstOffense',
  medium: 'settings.punishmentTypeDialog.mediumOffense',
  habitual: 'settings.punishmentTypeDialog.habitualOffense',
};

const UNIT_LABEL_KEYS: Record<DurationUnit, string> = {
  seconds: 'settings.punishmentTypeDialog.unitSeconds',
  minutes: 'settings.punishmentTypeDialog.unitMinutes',
  hours: 'settings.punishmentTypeDialog.unitHours',
  days: 'settings.punishmentTypeDialog.unitDays',
  weeks: 'settings.punishmentTypeDialog.unitWeeks',
  months: 'settings.punishmentTypeDialog.unitMonths',
};

const KIND_LABEL_KEYS: Record<DurationKind, string> = {
  mute: 'settings.punishmentTypeDialog.kindMute',
  ban: 'settings.punishmentTypeDialog.kindBan',
  'permanent mute': 'settings.punishmentTypeDialog.kindPermanentMute',
  'permanent ban': 'settings.punishmentTypeDialog.kindPermanentBan',
};

function isDurationUnit(value: string): value is DurationUnit {
  return DURATION_UNITS.some((unit) => unit === value);
}

function isDurationKind(value: string): value is DurationKind {
  return DURATION_KINDS.some((kind) => kind === value);
}

function isPermanent(setting: DurationSetting): boolean {
  return setting.type === 'permanent mute' || setting.type === 'permanent ban';
}

interface OffenseDurationsEditorProps {
  idPrefix: string;
  durations: OffenseDurations;
  valuePlaceholder: string;
  lockPermanentDurations: boolean;
  onChange: (level: OffenseLevel, setting: DurationSetting) => void;
}

export function OffenseDurationsEditor({
  idPrefix,
  durations,
  valuePlaceholder,
  lockPermanentDurations,
  onChange,
}: OffenseDurationsEditorProps) {
  const { t } = useTranslation();

  return (
    <div className="space-y-3">
      {OFFENSE_LEVELS.map((level) => {
        const setting = durations[level];
        const locked = lockPermanentDurations && isPermanent(setting);
        const lockedClass = locked ? 'opacity-50' : '';
        const inputId = `${idPrefix}-${level}`;
        return (
          <div key={level} className="min-w-0">
            <Label htmlFor={inputId} className="text-xs text-muted-foreground">
              {t(OFFENSE_LABEL_KEYS[level])}
            </Label>
            <div className="mt-1 flex gap-1">
              <Input
                id={inputId}
                type="number"
                min="0"
                value={setting.value === 0 ? '' : setting.value}
                onChange={(event) => {
                  onChange(level, { ...setting, value: Number(event.target.value) });
                }}
                disabled={locked}
                className={`h-9 w-16 shrink-0 ${lockedClass}`}
                placeholder={valuePlaceholder}
              />
              <Select
                value={setting.unit}
                onValueChange={(unit) => {
                  if (isDurationUnit(unit)) onChange(level, { ...setting, unit });
                }}
                disabled={locked}
              >
                <SelectTrigger className={`h-9 w-20 shrink-0 ${lockedClass}`}>
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {DURATION_UNITS.map((unit) => (
                    <SelectItem key={unit} value={unit}>{t(UNIT_LABEL_KEYS[unit])}</SelectItem>
                  ))}
                </SelectContent>
              </Select>
              <Select
                value={setting.type}
                onValueChange={(type) => {
                  if (isDurationKind(type)) onChange(level, { ...setting, type });
                }}
              >
                <SelectTrigger className="h-9 min-w-0 flex-1">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {DURATION_KINDS.map((kind) => (
                    <SelectItem key={kind} value={kind}>{t(KIND_LABEL_KEYS[kind])}</SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
          </div>
        );
      })}
    </div>
  );
}

interface PointsInputProps {
  id: string;
  value: number | undefined;
  onChange: (value: number) => void;
}

export function PointsInput({ id, value, onChange }: PointsInputProps) {
  const { t } = useTranslation();
  return (
    <div className="space-y-1">
      <Label htmlFor={id} className="text-xs text-muted-foreground">{t('settings.punishmentTypeDialog.pointsPlaceholder')}</Label>
      <Input
        id={id}
        type="number"
        min="0"
        value={value === undefined || value === 0 ? '' : value}
        onChange={(event) => {
          onChange(Number(event.target.value));
        }}
        className="h-9 w-20"
      />
    </div>
  );
}
