import { useId, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useModifyPunishment } from '@/hooks/data/punishments';
import { usePermissions } from '@/hooks/use-permissions';
import { toast } from '@/hooks/use-toast';
import { apiErrorCode, describeError } from '@/lib/http-error';
import { canShorten, endsEarlierWith, SHORTENING_NEEDS_PARDON_CODE, type ModificationType } from '@/lib/punishment-actions';
import { DURATION_UNITS, type DurationUnit } from '@/lib/punishment-preview';
import { durationToMilliseconds, type PunishmentFormDuration } from '@/lib/punishment-request';
import { positiveWholeNumberOf } from '@/lib/whole-number';
import type { PunishmentHistoryEntry } from '../player-profile';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Checkbox } from '@/components/ui/checkbox';
import { Textarea } from '@/components/ui/textarea';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { ActionFormPanel } from './ActionFormPanel';

interface ModifyPunishmentFormProps {
  readonly playerId: string;
  readonly entry: PunishmentHistoryEntry;
  readonly modifications: readonly ModificationType[];
  readonly now: number;
  readonly onClose: () => void;
}

const DEFAULT_DURATION_UNIT: DurationUnit = 'minutes';

const MODIFICATION_LABEL_KEYS = {
  MANUAL_DURATION_CHANGE: 'player.changeDuration',
  MANUAL_PARDON: 'player.pardon',
  SET_ALT_BLOCKING_TRUE: 'player.enableAltBlocking',
  SET_ALT_BLOCKING_FALSE: 'player.disableAltBlocking',
  SET_WIPING_TRUE: 'player.enableWiping',
  SET_WIPING_FALSE: 'player.disableWiping',
} as const satisfies Record<ModificationType, string>;

const PERMANENT_DURATION_VALUE = 0;

function requestedDuration(isPermanent: boolean, amountText: string, unit: DurationUnit): PunishmentFormDuration | null {
  if (isPermanent) return { value: PERMANENT_DURATION_VALUE, unit };
  const value = positiveWholeNumberOf(amountText);
  return value === null ? null : { value, unit };
}

export function ModifyPunishmentForm({ playerId, entry, modifications, now, onClose }: ModifyPunishmentFormProps) {
  const { t } = useTranslation();
  const { hasPermission } = usePermissions();
  const modifyPunishment = useModifyPunishment();
  const [modificationType, setModificationType] = useState<ModificationType | null>(null);
  const [durationText, setDurationText] = useState('');
  const [isPermanent, setIsPermanent] = useState(false);
  const [durationUnit, setDurationUnit] = useState<DurationUnit>(DEFAULT_DURATION_UNIT);
  const [reason, setReason] = useState('');
  const fieldId = useId();
  const isDurationChange = modificationType === 'MANUAL_DURATION_CHANGE';
  const newDuration = isDurationChange ? requestedDuration(isPermanent, durationText, durationUnit) : null;
  const newDurationMs = newDuration === null ? null : durationToMilliseconds(newDuration);
  const shorteningRefused = newDurationMs !== null && !canShorten(hasPermission)
    && endsEarlierWith(entry.punishment, newDurationMs, now);
  const canSubmit = modificationType !== null
    && reason.trim() !== ''
    && (!isDurationChange || newDuration !== null)
    && !shorteningRefused
    && !modifyPunishment.isPending;

  const submit = () => {
    if (modificationType === null) return;
    modifyPunishment.mutate({
      uuid: playerId,
      punishmentId: entry.punishment.id,
      modificationType,
      reason,
      newDuration: newDuration ?? undefined,
    }, {
      onSuccess: () => {
        toast({ title: t('player.punishmentModified') });
        onClose();
      },
      onError: (error) => {
        const description = apiErrorCode(error) === SHORTENING_NEEDS_PARDON_CODE
          ? t('player.shorteningNeedsPardon')
          : describeError(error, t('common.unknownError'));
        toast({ title: t('player.modifyPunishmentFailed'), description, variant: 'destructive' });
      },
    });
  };

  return (
    <ActionFormPanel
      title={t('player.modifyPunishment')}
      submitLabel={t('player.applyModification')}
      canSubmit={canSubmit}
      onSubmit={submit}
      onCancel={onClose}
    >
      <div className="space-y-3">
        <div className="space-y-1.5">
          <Label htmlFor={`${fieldId}-type`}>{t('player.modificationType')}</Label>
          <Select
            value={modificationType ?? ''}
            onValueChange={(value) => setModificationType(modifications.find((type) => type === value) ?? null)}
          >
            <SelectTrigger id={`${fieldId}-type`}>
              <SelectValue placeholder={t('player.selectModType')} />
            </SelectTrigger>
            <SelectContent>
              {modifications.map((type) => (
                <SelectItem key={type} value={type}>{t(MODIFICATION_LABEL_KEYS[type])}</SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
        {isDurationChange && (
          <div className="flex items-center gap-2">
            <Checkbox
              id={`${fieldId}-permanent`}
              checked={isPermanent}
              onCheckedChange={(checked) => { setIsPermanent(checked === true); }}
            />
            <Label htmlFor={`${fieldId}-permanent`} className="font-normal">{t('punishment.permanent')}</Label>
          </div>
        )}
        {isDurationChange && !isPermanent && (
          <div className="grid grid-cols-2 gap-2">
            <div className="space-y-1.5">
              <Label htmlFor={`${fieldId}-duration`}>{t('player.newDuration')}</Label>
              <Input
                id={`${fieldId}-duration`}
                type="number"
                min="1"
                step="1"
                inputMode="numeric"
                placeholder={t('player.durationAmountPlaceholder')}
                value={durationText}
                aria-invalid={durationText !== '' && newDuration === null}
                onChange={(event) => { setDurationText(event.target.value); }}
              />
            </div>
            <div className="space-y-1.5">
              <Label htmlFor={`${fieldId}-unit`}>{t('player.unit')}</Label>
              <Select
                value={durationUnit}
                onValueChange={(value) => setDurationUnit(DURATION_UNITS.find((unit) => unit.value === value)?.value ?? DEFAULT_DURATION_UNIT)}
              >
                <SelectTrigger id={`${fieldId}-unit`}>
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {DURATION_UNITS.map((unit) => (
                    <SelectItem key={unit.value} value={unit.value}>{t(unit.labelKey)}</SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
          </div>
        )}
        {shorteningRefused && (
          <p className="text-sm text-destructive-text">{t('player.shorteningNeedsPardon')}</p>
        )}
        <div className="space-y-1.5">
          <Label htmlFor={`${fieldId}-reason`}>{t('common.reason')}</Label>
          <Textarea
            id={`${fieldId}-reason`}
            className="h-16 resize-none"
            value={reason}
            onChange={(event) => setReason(event.target.value)}
          />
        </div>
      </div>
    </ActionFormPanel>
  );
}
