import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { PUNISHMENT_CATEGORY_LABEL_KEYS } from '@/lib/punishment-category-labels';
import { Pencil, Plus, Trash2 } from 'lucide-react';
import { GroupRow, GroupRows } from '@/components/ui/group-list';
import { IconAction } from '@/components/ui/icon-action';
import type { CategoryStatusThresholds, StatusThresholds } from '@/hooks/data/settings';
import type { PunishmentTypeConfig } from '@/hooks/data/punishment-types';
import { useConfirmation } from '@/hooks/use-confirmation';
import { Button } from '@/components/ui/button';
import { Label } from '@/components/ui/label';
import { Slider } from '@/components/ui/slider';
import NewPunishmentTypeDialog from './NewPunishmentTypeDialog';
import PunishmentTypeDialog from './PunishmentTypeDialog';
import { SettingsPageAction, SettingsPageActionButton, useSettingsPageCanModify } from './layout/SettingsPageFrame';
import { SettingsLoading, SettingsSection, SettingsSections } from './layout/SettingsSections';
import { usePunishmentTypesState, useStatusThresholdsDraft } from './use-punishment-settings-state';
import { isConfigurableInSettings, isCustomPunishmentOrdinal, isManualPunishmentOrdinal } from '@/lib/manual-punishments';
import { OFFENDER_POINTS_MIN, offenderPointsScaleMax, withHabitualThreshold, withMediumThreshold } from '@/lib/offender-thresholds';

type PunishmentType = PunishmentTypeConfig;

type OffenderThresholds = CategoryStatusThresholds;

type ThresholdGroupId = keyof StatusThresholds;

type SetStatusThresholds = (update: (prev: StatusThresholds) => StatusThresholds) => void;

const MONTHS_PER_YEAR = 12;

function useMonthsLabel() {
  const { i18n } = useTranslation();
  return (months: number): string => {
    const inYears = months % MONTHS_PER_YEAR === 0;
    return new Intl.NumberFormat(i18n.language, {
      style: 'unit',
      unit: inYears ? 'year' : 'month',
      unitDisplay: 'long',
    }).format(inYears ? months / MONTHS_PER_YEAR : months);
  };
}

interface ThresholdSliderProps {
  id: string;
  label: string;
  value: number;
  valueLabel: string;
  min: number;
  max: number;
  disabled: boolean;
  onChange: (value: number) => void;
}

function ThresholdSlider({ id, label, value, valueLabel, min, max, disabled, onChange }: ThresholdSliderProps) {
  const labelId = `${id}-label`;
  return (
    <div className="space-y-2">
      <div className="flex justify-between gap-2">
        <Label id={labelId}>{label}</Label>
        <span className="text-sm text-muted-foreground tabular-nums" aria-hidden="true">{valueLabel}</span>
      </div>
      <Slider
        labelledBy={labelId}
        valueText={valueLabel}
        disabled={disabled}
        value={[value]}
        min={min}
        max={max}
        step={1}
        onValueChange={(values) => { onChange(values[0] ?? value); }}
      />
    </div>
  );
}

interface ThresholdGroupProps {
  group: ThresholdGroupId;
  title: string;
  thresholds: OffenderThresholds;
  saved: OffenderThresholds;
  setStatusThresholds: SetStatusThresholds;
}

function ThresholdGroup({ group, title, thresholds, saved, setStatusThresholds }: ThresholdGroupProps) {
  const { t } = useTranslation();
  const canModify = useSettingsPageCanModify();
  const monthsLabel = useMonthsLabel();
  const update = (changes: (current: OffenderThresholds) => Partial<OffenderThresholds>) => {
    setStatusThresholds((prev) => ({ ...prev, [group]: { ...prev[group], ...changes(prev[group]) } }));
  };
  const scaleMax = offenderPointsScaleMax(saved);

  return (
    <SettingsSection title={title}>
      <ThresholdSlider
        id={`${group}-medium`}
        label={t('settings.punishment.mediumOffender')}
        value={thresholds.medium}
        valueLabel={t('settings.punishment.pointsAtLeast', { value: thresholds.medium })}
        min={OFFENDER_POINTS_MIN}
        max={scaleMax}
        disabled={!canModify}
        onChange={(medium) => { update((current) => withMediumThreshold(current, medium, scaleMax)); }}
      />
      <ThresholdSlider
        id={`${group}-habitual`}
        label={t('settings.punishment.habitualOffender')}
        value={thresholds.habitual}
        valueLabel={t('settings.punishment.pointsAtLeast', { value: thresholds.habitual })}
        min={OFFENDER_POINTS_MIN}
        max={scaleMax}
        disabled={!canModify}
        onChange={(habitual) => { update((current) => withHabitualThreshold(current, habitual, scaleMax)); }}
      />
      <ThresholdSlider
        id={`${group}-point-expiry`}
        label={t('settings.punishment.pointExpiry')}
        value={thresholds.pointExpiryMonths}
        valueLabel={monthsLabel(thresholds.pointExpiryMonths)}
        min={1}
        max={60}
        disabled={!canModify}
        onChange={(pointExpiryMonths) => { update(() => ({ pointExpiryMonths })); }}
      />
    </SettingsSection>
  );
}

interface PunishmentTypeRowProps {
  type: PunishmentType;
  configurable: boolean;
  deletable: boolean;
  onConfigure: (type: PunishmentType) => void;
  onDelete: (type: PunishmentType) => void;
}

function PunishmentTypeRow({ type, configurable, deletable, onConfigure, onDelete }: PunishmentTypeRowProps) {
  const { t } = useTranslation();
  const canModify = useSettingsPageCanModify();

  return (
    <GroupRow
      leading={<span className="w-6 shrink-0 text-right font-mono text-xs text-muted-foreground">{type.ordinal}</span>}
      title={type.name}
      actions={canModify && (configurable || deletable) && (
        <>
          {configurable && <IconAction icon={Pencil} label={t('common.configure')} onClick={() => { onConfigure(type); }} />}
          {deletable && (
            <IconAction icon={Trash2} label={t('settings.punishment.deletePunishmentType')} destructive onClick={() => { onDelete(type); }} />
          )}
        </>
      )}
    />
  );
}

const byOrdinal = (a: PunishmentType, b: PunishmentType) => a.ordinal - b.ordinal;

const inCategory = (category: string) => (type: PunishmentType) =>
  type.category.toLowerCase().trim() === category && isCustomPunishmentOrdinal(type.ordinal);

export function EscalationSettings() {
  const { t } = useTranslation();
  const { draft, saved, isLoading } = useStatusThresholdsDraft();
  if (isLoading) return <SettingsLoading />;

  return (
    <SettingsSections>
      <ThresholdGroup
        group="gameplay"
        title={t(PUNISHMENT_CATEGORY_LABEL_KEYS.gameplay)}
        thresholds={draft.value.gameplay}
        saved={saved.gameplay}
        setStatusThresholds={draft.update}
      />
      <ThresholdGroup
        group="social"
        title={t(PUNISHMENT_CATEGORY_LABEL_KEYS.social)}
        thresholds={draft.value.social}
        saved={saved.social}
        setStatusThresholds={draft.update}
      />
    </SettingsSections>
  );
}

export function PunishmentTypesSettings() {
  const { t } = useTranslation();
  const state = usePunishmentTypesState();
  const { punishmentTypes } = state;
  const [showCorePunishments, setShowCorePunishments] = useState(false);
  const confirmation = useConfirmation();

  const deleteType = async (type: PunishmentType) => {
    const confirmed = await confirmation.confirm({
      title: t('settings.punishment.deletePunishmentType'),
      description: t('settings.punishment.deletePunishmentTypeConfirm', { name: type.name }),
      action: t('common.delete'),
      destructive: true,
    });
    if (confirmed) state.removePunishmentType(type.id);
  };

  const customGroups = [
    { id: 'gameplay', title: t(PUNISHMENT_CATEGORY_LABEL_KEYS.gameplay) },
    { id: 'social', title: t(PUNISHMENT_CATEGORY_LABEL_KEYS.social) },
  ] as const;

  return (
    <SettingsSections>
      <SettingsPageAction>
        <SettingsPageActionButton icon={Plus} label={t('settings.punishment.addNewType')} onClick={state.openCreator} />
      </SettingsPageAction>
      {customGroups.map((group) => (
        <SettingsSection key={group.id} title={group.title}>
          <GroupRows>
            {punishmentTypes.filter(inCategory(group.id)).sort(byOrdinal).map((type) => (
              <PunishmentTypeRow
                key={type.id}
                type={type}
                configurable={type.isCustomizable}
                deletable={type.isCustomizable}
                onConfigure={state.editPunishmentType}
                onDelete={(deleted) => { void deleteType(deleted); }}
              />
            ))}
          </GroupRows>
        </SettingsSection>
      ))}

      <SettingsSection>
        <div className="flex items-center justify-between gap-2">
          <h3 className="text-sm font-semibold">{t('settings.punishment.coreAdminPunishments')}</h3>
          <Button
            variant="ghost"
            size="sm"
            aria-expanded={showCorePunishments}
            onClick={() => { setShowCorePunishments(!showCorePunishments); }}
          >
            {showCorePunishments ? t('common.hide') : t('common.show')}
          </Button>
        </div>
        {showCorePunishments && (
          <GroupRows>
            {punishmentTypes.filter((type) => isManualPunishmentOrdinal(type.ordinal)).sort(byOrdinal).map((type) => (
              <PunishmentTypeRow
                key={type.id}
                type={type}
                configurable={isConfigurableInSettings(type.ordinal)}
                deletable={false}
                onConfigure={state.editPunishmentType}
                onDelete={(deleted) => { void deleteType(deleted); }}
              />
            ))}
          </GroupRows>
        )}
      </SettingsSection>

      {confirmation.dialog}

      {state.isCreating && (
        <NewPunishmentTypeDialog isSaving={state.isAdding} onSubmit={state.addPunishmentType} onClose={state.closeCreator} />
      )}

      {state.editingType !== null && (
        <PunishmentTypeDialog
          key={state.editingType.ordinal}
          punishment={state.editingType}
          onClose={state.closeEditor}
          onSave={state.saveEditedType}
          isSaving={state.isSavingEditedType}
        />
      )}
    </SettingsSections>
  );
}
