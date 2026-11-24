import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  DEFAULT_STATUS_THRESHOLDS,
  usePatchStatusThresholds,
  useStatusThresholdSettings,
} from '@/hooks/data/settings';
import {
  useCreatePunishmentType,
  useDeletePunishmentType,
  usePunishmentTypeConfigs,
  useUpdatePunishmentType,
  type CustomPunishmentCategory,
  type DurationSetting,
  type OffenseDurations,
  type PunishmentTypeConfig,
  type SeverityDurations,
  type SeverityPoints,
} from '@/hooks/data/punishment-types';
import { usePermissions } from '@/hooks/use-permissions';
import { useToast } from '@/hooks/use-toast';
import { describeError } from '@/lib/http-error';
import { isCustomPunishmentOrdinal } from '@/lib/manual-punishments';
import { useVersionedSettingsDraft } from './save/use-settings-draft';

function minutesOfMute(value: number): DurationSetting {
  return { value, unit: 'minutes', type: 'mute' };
}

function offenseMinutes(first: number, medium: number, habitual: number): OffenseDurations {
  return { first: minutesOfMute(first), medium: minutesOfMute(medium), habitual: minutesOfMute(habitual) };
}

const DEFAULT_DURATIONS: Record<CustomPunishmentCategory, SeverityDurations> = {
  Gameplay: {
    low: offenseMinutes(24, 72, 168),
    regular: offenseMinutes(72, 168, 336),
    severe: offenseMinutes(168, 336, 720),
  },
  Social: {
    low: offenseMinutes(24, 48, 96),
    regular: offenseMinutes(48, 96, 168),
    severe: offenseMinutes(72, 168, 336),
  },
};

const DEFAULT_POINTS: Record<CustomPunishmentCategory, SeverityPoints> = {
  Gameplay: { low: 2, regular: 4, severe: 6 },
  Social: { low: 1, regular: 3, severe: 5 },
};

const NO_PUNISHMENT_TYPES: PunishmentTypeConfig[] = [];

export function useStatusThresholdsDraft() {
  const { canLoadSettingsData } = usePermissions();
  const thresholdsQuery = useStatusThresholdSettings({ enabled: canLoadSettingsData('statusThresholds') });
  const patchThresholds = usePatchStatusThresholds();
  const draft = useVersionedSettingsDraft({
    persisted: thresholdsQuery.data,
    fallback: DEFAULT_STATUS_THRESHOLDS,
    save: patchThresholds.mutateAsync,
  });
  return { draft, saved: thresholdsQuery.data?.value ?? DEFAULT_STATUS_THRESHOLDS, isLoading: thresholdsQuery.isPending };
}

export function usePunishmentTypesState() {
  const { t } = useTranslation();
  const { toast } = useToast();
  const { canLoadSettingsData } = usePermissions();
  const { data: punishmentTypes = NO_PUNISHMENT_TYPES } = usePunishmentTypeConfigs({ enabled: canLoadSettingsData('punishmentTypes') });
  const createType = useCreatePunishmentType();
  const deleteType = useDeletePunishmentType();
  const updateType = useUpdatePunishmentType();
  const [isCreating, setIsCreating] = useState(false);
  const [editingType, setEditingType] = useState<PunishmentTypeConfig | null>(null);

  const showError = (error: unknown, fallbackKey: string) => {
    toast({ title: t('toast.error'), description: describeError(error, t(fallbackKey)), variant: 'destructive' });
  };

  const addPunishmentType = (name: string, category: CustomPunishmentCategory) => {
    createType.mutate(
      {
        name,
        category,
        durations: DEFAULT_DURATIONS[category],
        points: DEFAULT_POINTS[category],
      },
      {
        onSuccess: () => {
          setIsCreating(false);
        },
        onError: (error) => {
          showError(error, 'settings.page.createPunishmentTypeFailed');
        },
      },
    );
  };

  const removePunishmentType = (id: number) => {
    const type = punishmentTypes.find((candidate) => candidate.id === id);
    if (type === undefined || !isCustomPunishmentOrdinal(type.ordinal)) return;
    deleteType.mutate(type.ordinal, {
      onError: (error) => {
        showError(error, 'settings.page.deletePunishmentTypeFailed');
      },
    });
  };

  const editPunishmentType = ({ id }: { id: number }) => {
    setEditingType(punishmentTypes.find((candidate) => candidate.id === id) ?? null);
  };

  const saveEditedType = (config: PunishmentTypeConfig) => {
    updateType.mutate(config, {
      onSuccess: () => {
        setEditingType(null);
      },
      onError: (error) => {
        showError(error, 'settings.page.updatePunishmentTypeFailed');
      },
    });
  };

  return {
    punishmentTypes,
    isCreating,
    openCreator: () => {
      setIsCreating(true);
    },
    closeCreator: () => {
      setIsCreating(false);
    },
    addPunishmentType,
    isAdding: createType.isPending,
    removePunishmentType,
    editPunishmentType,
    editingType,
    closeEditor: () => {
      setEditingType(null);
    },
    saveEditedType,
    isSavingEditedType: updateType.isPending,
  };
}
