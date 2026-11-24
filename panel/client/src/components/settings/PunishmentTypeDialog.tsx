import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { PUNISHMENT_CATEGORY_LABEL_KEYS } from '@/lib/punishment-category-labels';
import { Button } from '@/components/ui/button';
import { Checkbox } from '@/components/ui/checkbox';
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { Textarea } from '@/components/ui/textarea';
import {
  SEVERITIES,
  type CustomPunishmentCategory,
  type DurationSetting,
  type OffenseDurations,
  type OffenseLevel,
  type PunishmentTypeConfig,
  type Severity,
  type SeverityDurations,
  type SeverityPoints,
} from '@/hooks/data/punishment-types';
import AppealFormEditor, { EMPTY_APPEAL_FORM } from './AppealFormEditor';
import { OffenseDurationsEditor, PointsInput } from './PunishmentDurationsEditor';

type PermanentTrigger = 'username' | 'skin';

const DEFAULT_SINGLE_SEVERITY_POINTS = 3;

const DEFAULT_SINGLE_SEVERITY_DURATIONS: OffenseDurations = {
  first: { value: 24, unit: 'hours', type: 'mute' },
  medium: { value: 3, unit: 'days', type: 'mute' },
  habitual: { value: 7, unit: 'days', type: 'mute' },
};

const UNSET_DURATION: DurationSetting = { value: 0, unit: 'hours', type: 'mute' };

const UNSET_OFFENSE_DURATIONS: OffenseDurations = { first: UNSET_DURATION, medium: UNSET_DURATION, habitual: UNSET_DURATION };

const UNSET_SEVERITY_DURATIONS: SeverityDurations = {
  low: UNSET_OFFENSE_DURATIONS,
  regular: UNSET_OFFENSE_DURATIONS,
  severe: UNSET_OFFENSE_DURATIONS,
};

const UNSET_POINTS: SeverityPoints = { low: 0, regular: 0, severe: 0 };

const SEVERITY_LABEL_KEYS: Record<Severity, string> = {
  low: 'settings.page.lowSeverity',
  regular: 'settings.page.regularSeverity',
  severe: 'settings.page.severeSeverity',
};

const SEVERITY_VALUE_PLACEHOLDERS: Record<Severity, string> = { low: '24', regular: '48', severe: '72' };

function isCustomCategory(value: string): value is CustomPunishmentCategory {
  return value === 'Gameplay' || value === 'Social';
}

function permanentChange(trigger: PermanentTrigger, checked: boolean): Partial<PunishmentTypeConfig> {
  if (!checked) {
    return trigger === 'username' ? { permanentUntilUsernameChange: false } : { permanentUntilSkinChange: false };
  }
  return {
    permanentUntilUsernameChange: trigger === 'username',
    permanentUntilSkinChange: trigger === 'skin',
    singleSeverityPunishment: false,
    canBeAltBlocking: false,
    canBeStatWiping: false,
  };
}

function singleSeverityChange(type: PunishmentTypeConfig, checked: boolean): Partial<PunishmentTypeConfig> {
  if (!checked) return { singleSeverityPunishment: false };
  const currentPoints = type.singleSeverityPoints ?? 0;
  return {
    singleSeverityPunishment: true,
    singleSeverityDurations: type.singleSeverityDurations ?? DEFAULT_SINGLE_SEVERITY_DURATIONS,
    singleSeverityPoints: currentPoints === 0 ? DEFAULT_SINGLE_SEVERITY_POINTS : currentPoints,
  };
}

interface OptionCheckboxProps {
  id: string;
  label: string;
  checked: boolean;
  disabled?: boolean;
  onChange: (checked: boolean) => void;
}

function OptionCheckbox({ id, label, checked, disabled = false, onChange }: OptionCheckboxProps) {
  return (
    <div className="flex items-center gap-2">
      <Checkbox
        id={id}
        checked={checked}
        disabled={disabled}
        onCheckedChange={(state) => {
          onChange(state === true);
        }}
      />
      <Label htmlFor={id} className="font-normal">{label}</Label>
    </div>
  );
}

interface PunishmentTypeDialogProps {
  punishment: PunishmentTypeConfig;
  onClose: () => void;
  onSave: (punishment: PunishmentTypeConfig) => void;
  isSaving: boolean;
}

export default function PunishmentTypeDialog({ punishment, onClose, onSave, isSaving }: PunishmentTypeDialogProps) {
  const { t } = useTranslation();
  const [draft, setDraft] = useState(punishment);

  const change = (patch: Partial<PunishmentTypeConfig>) => {
    setDraft((current) => ({ ...current, ...patch }));
  };

  const changeSingleSeverityDuration = (level: OffenseLevel, setting: DurationSetting) => {
    setDraft((current) => ({
      ...current,
      singleSeverityDurations: { ...DEFAULT_SINGLE_SEVERITY_DURATIONS, ...current.singleSeverityDurations, [level]: setting },
    }));
  };

  const changeSeverityDuration = (severity: Severity, level: OffenseLevel, setting: DurationSetting) => {
    setDraft((current) => {
      const durations = current.durations ?? UNSET_SEVERITY_DURATIONS;
      return { ...current, durations: { ...durations, [severity]: { ...durations[severity], [level]: setting } } };
    });
  };

  const changePoints = (severity: Severity, value: number) => {
    setDraft((current) => ({ ...current, points: { ...(current.points ?? UNSET_POINTS), [severity]: value } }));
  };

  const permanentUntilChange = draft.permanentUntilUsernameChange || draft.permanentUntilSkinChange;
  const usesSeverityScale = !draft.singleSeverityPunishment && !permanentUntilChange;

  return (
    <Dialog open onOpenChange={(open) => {
      if (!open) onClose();
    }}>
      <DialogContent className="max-w-4xl max-h-[90vh] overflow-hidden" aria-describedby={undefined}>
        <DialogHeader>
          <DialogTitle>{punishment.name}</DialogTitle>
        </DialogHeader>

        <Tabs defaultValue="configuration" className="min-w-0">
          <TabsList className="grid w-full grid-cols-2">
            <TabsTrigger value="configuration">{t('settings.page.configuration')}</TabsTrigger>
            <TabsTrigger value="appeal-form">{t('settings.page.appealForm')}</TabsTrigger>
          </TabsList>

          <TabsContent value="configuration" className="mt-4 max-h-[60vh] space-y-6 overflow-y-auto px-1 pb-1">
            {draft.isCustomizable && (
              <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
                <div className="space-y-2">
                  <Label htmlFor="edit-punishment-name">{t('settings.page.punishmentName')}</Label>
                  <Input
                    id="edit-punishment-name"
                    value={draft.name}
                    onChange={(event) => {
                      change({ name: event.target.value });
                    }}
                  />
                </div>
                <div className="space-y-2">
                  <Label htmlFor="edit-punishment-category">{t('settings.page.category')}</Label>
                  <Select
                    value={draft.category}
                    onValueChange={(category) => {
                      if (isCustomCategory(category)) change({ category });
                    }}
                  >
                    <SelectTrigger id="edit-punishment-category">
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      <SelectItem value="Gameplay">{t(PUNISHMENT_CATEGORY_LABEL_KEYS.gameplay)}</SelectItem>
                      <SelectItem value="Social">{t(PUNISHMENT_CATEGORY_LABEL_KEYS.social)}</SelectItem>
                    </SelectContent>
                  </Select>
                </div>
              </div>
            )}

            <div className="space-y-2">
              <Label htmlFor="staff-description">{t('settings.page.staffDescription')}</Label>
              <Textarea
                id="staff-description"
                placeholder={t('settings.page.staffDescriptionPlaceholder')}
                value={draft.staffDescription}
                onChange={(event) => {
                  change({ staffDescription: event.target.value });
                }}
              />
            </div>
            <div className="space-y-2">
              <Label htmlFor="player-description">{t('settings.page.playerDescription')}</Label>
              <Textarea
                id="player-description"
                placeholder={t('settings.page.playerDescriptionPlaceholder')}
                value={draft.playerDescription}
                onChange={(event) => {
                  change({ playerDescription: event.target.value });
                }}
              />
            </div>

            {draft.isCustomizable && (
              <>
                <section className="space-y-3">
                  <h4 className="text-sm font-semibold">{t('settings.page.punishmentOptions')}</h4>
                  <OptionCheckbox
                    id="canBeAltBlocking"
                    label={t('settings.page.canBeAltBlocking')}
                    checked={draft.canBeAltBlocking}
                    disabled={permanentUntilChange}
                    onChange={(canBeAltBlocking) => {
                      change({ canBeAltBlocking });
                    }}
                  />
                  <OptionCheckbox
                    id="canBeStatWiping"
                    label={t('settings.page.canBeStatWiping')}
                    checked={draft.canBeStatWiping}
                    disabled={permanentUntilChange}
                    onChange={(canBeStatWiping) => {
                      change({ canBeStatWiping });
                    }}
                  />
                  <OptionCheckbox
                    id="permanentUntilUsernameChange"
                    label={t('settings.page.permanentUntilUsernameChange')}
                    checked={draft.permanentUntilUsernameChange}
                    onChange={(checked) => {
                      change(permanentChange('username', checked));
                    }}
                  />
                  <OptionCheckbox
                    id="permanentUntilSkinChange"
                    label={t('settings.page.permanentUntilSkinChange')}
                    checked={draft.permanentUntilSkinChange}
                    onChange={(checked) => {
                      change(permanentChange('skin', checked));
                    }}
                  />
                  <OptionCheckbox
                    id="singleSeverityPunishment"
                    label={t('settings.page.singleSeverityPunishment')}
                    checked={draft.singleSeverityPunishment}
                    disabled={permanentUntilChange}
                    onChange={(checked) => {
                      setDraft((current) => ({ ...current, ...singleSeverityChange(current, checked) }));
                    }}
                  />
                  {draft.singleSeverityPunishment && (
                    <div className="grid max-w-sm grid-cols-1 gap-3 pl-6">
                      <OffenseDurationsEditor
                        idPrefix={`single-${draft.ordinal}`}
                        durations={draft.singleSeverityDurations ?? DEFAULT_SINGLE_SEVERITY_DURATIONS}
                        valuePlaceholder={SEVERITY_VALUE_PLACEHOLDERS.low}
                        lockPermanentDurations={false}
                        onChange={changeSingleSeverityDuration}
                      />
                      <PointsInput
                        id={`single-points-${draft.ordinal}`}
                        value={draft.singleSeverityPoints}
                        onChange={(singleSeverityPoints) => {
                          change({ singleSeverityPoints });
                        }}
                      />
                    </div>
                  )}
                </section>

                {usesSeverityScale && (
                  <section className="space-y-3">
                    <h4 className="text-sm font-semibold">{t('settings.page.durations')}</h4>
                    <div className="grid grid-cols-1 gap-6 md:grid-cols-3">
                      {SEVERITIES.map((severity) => (
                        <div key={severity} className="space-y-3">
                          <h5 className="text-sm font-medium">{t(SEVERITY_LABEL_KEYS[severity])}</h5>
                          <OffenseDurationsEditor
                            idPrefix={`${severity}-${draft.ordinal}`}
                            durations={(draft.durations ?? UNSET_SEVERITY_DURATIONS)[severity]}
                            valuePlaceholder={SEVERITY_VALUE_PLACEHOLDERS[severity]}
                            lockPermanentDurations
                            onChange={(level, setting) => {
                              changeSeverityDuration(severity, level, setting);
                            }}
                          />
                          <PointsInput
                            id={`${severity}-points-${draft.ordinal}`}
                            value={draft.points?.[severity]}
                            onChange={(value) => {
                              changePoints(severity, value);
                            }}
                          />
                        </div>
                      ))}
                    </div>
                  </section>
                )}
              </>
            )}
          </TabsContent>

          <TabsContent value="appeal-form" className="mt-4 max-h-[60vh] space-y-4 overflow-y-auto px-1 pb-1">
            <OptionCheckbox
              id="isAppealable"
              label={t('settings.page.isAppealable')}
              checked={draft.isAppealable}
              onChange={(isAppealable) => {
                change({ isAppealable });
              }}
            />
            <AppealFormEditor
              appealForm={draft.appealForm}
              appealable={draft.isAppealable}
              onChange={(update) => {
                setDraft((current) => ({ ...current, appealForm: update(current.appealForm ?? EMPTY_APPEAL_FORM) }));
              }}
            />
          </TabsContent>
        </Tabs>

        <DialogFooter className="gap-2">
          <Button variant="outline" onClick={onClose}>{t('common.cancel')}</Button>
          <Button
            disabled={isSaving}
            onClick={() => {
              onSave(draft);
            }}
          >
            {t('common.save')}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
