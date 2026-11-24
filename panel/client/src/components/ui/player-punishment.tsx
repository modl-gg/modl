import type React from 'react';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { PUNISHMENT_CATEGORY_LABEL_KEYS } from '@/lib/punishment-category-labels';
import { z } from 'zod';
import { Button } from '@/components/ui/button';
import { Checkbox } from '@/components/ui/checkbox';
import { ArrowLeft } from 'lucide-react';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { useToast } from '@/hooks/use-toast';
import MediaUpload from '@/components/MediaUpload';
import { useActivePunishmentSearch, usePunishmentPreview, type ActivePunishmentSearchResult } from '@/hooks/data/punishments';
import {
  hasStaffChosenDuration,
  isManualPunishment,
  punishmentOptionsLayout,
  reasonPlaceholderKey,
  requiresOnlinePlayer,
  type ManualPunishmentToggle,
  type PunishmentOptionsLayout,
} from '@/lib/manual-punishments';
import { describeError } from '@/lib/http-error';
import { PUNISHMENT_FORM_ISSUE_KEYS, PunishmentFormError, punishmentFormIssue } from '@/lib/punishment-request';
import {
  DURATION_UNITS,
  OFFENSE_LEVELS,
  SEVERITY_OPTIONS,
  describePunishment,
  findDurationUnit,
  isPermanentUntilIdentityChange,
  offenseLevelToApply,
  type PunishmentSelection,
  type PunishmentTypeConfig,
} from '@/lib/punishment-preview';
import { allPunishmentTypes as flattenPunishmentTypes, type PunishmentTypesByCategory } from '@/lib/punishment-categories';
import { useStableRowKeys } from './stable-keys';

export interface PlayerPunishmentData extends PunishmentSelection {
  selectedPunishmentCategory?: string | undefined;
  reason?: string | undefined;
  evidence?: string[] | undefined;
  staffNotes?: string | undefined;
  altBlocking?: boolean | undefined;
  statWiping?: boolean | undefined;
  silentPunishment?: boolean | undefined;
  kickSameIP?: boolean | undefined;
  attachReports?: string[] | undefined;
  banToLink?: string | undefined;
  banLinkedAccounts?: boolean | undefined;
}

interface PlayerPunishmentProps {
  playerId?: string;
  playerName?: string;
  playerStatus?: string;
  data: PlayerPunishmentData;
  onChange: (data: PlayerPunishmentData) => void;
  onApply: (data: PlayerPunishmentData) => Promise<void>;
  punishmentTypesByCategory: PunishmentTypesByCategory;
  isLoading?: boolean;
  compact?: boolean;
  availableTickets?: {id: string; subject: string; type: string; status: string; locked?: boolean}[];
}

interface ToggleBinding {
  readonly labelKey: string;
  readonly isChecked: (data: PlayerPunishmentData) => boolean | undefined;
  readonly update: (checked: boolean) => Partial<PlayerPunishmentData>;
}

const TOGGLE_BINDINGS = {
  kickSameIP: {
    labelKey: 'punishment.kickSameIp',
    isChecked: (data) => data.kickSameIP,
    update: (checked) => ({ kickSameIP: checked }),
  },
  banLinkedAccounts: {
    labelKey: 'punishment.banLinkedAccounts',
    isChecked: (data) => data.banLinkedAccounts,
    update: (checked) => ({ banLinkedAccounts: checked }),
  },
  wipeAccount: {
    labelKey: 'punishment.wipeAccount',
    isChecked: (data) => data.statWiping,
    update: (checked) => ({ statWiping: checked }),
  },
} as const satisfies Record<ManualPunishmentToggle, ToggleBinding>;

type TypeSpecificOptions = Required<Pick<
  PlayerPunishmentData,
  | 'selectedSeverity'
  | 'selectedOffenseLevel'
  | 'duration'
  | 'isPermanent'
  | 'altBlocking'
  | 'statWiping'
  | 'silentPunishment'
  | 'kickSameIP'
  | 'banToLink'
  | 'banLinkedAccounts'
>>;

const NO_TYPE_SPECIFIC_OPTIONS: TypeSpecificOptions = {
  selectedSeverity: undefined,
  selectedOffenseLevel: undefined,
  duration: undefined,
  isPermanent: false,
  altBlocking: false,
  statWiping: false,
  silentPunishment: false,
  kickSameIP: false,
  banToLink: '',
  banLinkedAccounts: false,
};

const EMPTY_PUNISHMENT_FORM: PlayerPunishmentData = {
  ...NO_TYPE_SPECIFIC_OPTIONS,
  selectedPunishmentCategory: undefined,
  reason: '',
  evidence: [],
  staffNotes: '',
  attachReports: [],
};

const uploadedEvidenceSchema = z.object({ fileName: z.string() });

function isUploadedEvidence(evidence: string): boolean {
  return evidence.startsWith('{');
}

function evidenceDisplayValue(evidence: string): string {
  if (evidence.startsWith('{')) {
    try {
      const parsed: unknown = JSON.parse(evidence);
      const uploaded = uploadedEvidenceSchema.safeParse(parsed);
      return uploaded.success ? uploaded.data.fileName : evidence;
    } catch {
      return evidence;
    }
  }
  return evidence;
}

const PlayerPunishment: React.FC<PlayerPunishmentProps> = ({
  playerId,
  playerStatus = 'Offline',
  data,
  onChange,
  onApply,
  punishmentTypesByCategory,
  isLoading = false,
  compact = false,
  availableTickets = []
}) => {
  const { t } = useTranslation();
  const { toast } = useToast();
  const [isApplying, setIsApplying] = useState(false);
  const [linkedBanSearch, setLinkedBanSearch] = useState('');
  const [linkedBanQuery, setLinkedBanQuery] = useState('');
  const linkedBanSearchResults = useActivePunishmentSearch(linkedBanQuery);

  const evidence = data.evidence ?? [];
  const evidenceRows = useStableRowKeys(evidence.length);

  const allPunishmentTypes = flattenPunishmentTypes(punishmentTypesByCategory);

  const updateData = (updates: Partial<PlayerPunishmentData>) => {
    onChange({ ...data, ...updates });
  };

  const currentPunishmentType = data.selectedPunishmentCategory
    ? allPunishmentTypes.find(type => type.name === data.selectedPunishmentCategory)
    : undefined;
  const levelToApply = (offenderStatus: string | undefined) => currentPunishmentType === undefined
    ? undefined
    : offenseLevelToApply(currentPunishmentType, data.selectedOffenseLevel, offenderStatus);
  const { data: punishmentPreview } = usePunishmentPreview(playerId, currentPunishmentType?.ordinal, levelToApply(undefined));
  const appliedSelection: PlayerPunishmentData = {
    ...data,
    selectedOffenseLevel: levelToApply(punishmentPreview?.offenderStatus),
  };

  const getSelectedSeverityPreview = () => {
    if (!punishmentPreview) return null;
    if (currentPunishmentType?.singleSeverityPunishment) {
      return punishmentPreview.singleSeverity ?? null;
    }
    switch (data.selectedSeverity) {
      case 'Lenient':
        return punishmentPreview.lenient ?? null;
      case 'Regular':
        return punishmentPreview.regular ?? null;
      case 'Aggravated':
        return punishmentPreview.aggravated ?? null;
      case undefined:
        return null;
    }
  };

  const clearLinkedBanSearch = () => {
    setLinkedBanSearch('');
    setLinkedBanQuery('');
  };

  const resetAfterApply = () => {
    clearLinkedBanSearch();
    updateData(EMPTY_PUNISHMENT_FORM);
  };

  const applyPunishment = async () => {
    if (!currentPunishmentType) return;
    setIsApplying(true);
    try {
      await onApply(appliedSelection);
      resetAfterApply();
      toast({ title: t('punishment.applied') });
    } catch (error) {
      const issueKeys = error instanceof PunishmentFormError ? PUNISHMENT_FORM_ISSUE_KEYS[error.issue] : null;
      toast({
        title: issueKeys === null ? t('punishment.applyFailed') : t(issueKeys.title),
        description: issueKeys === null ? describeError(error, t('common.unknownError')) : t(issueKeys.description),
        variant: "destructive",
      });
    } finally {
      setIsApplying(false);
    }
  };

  const handleApplyPunishment = () => {
    void applyPunishment();
  };

  const handleCategorySelect = (type: PunishmentTypeConfig) => {
    clearLinkedBanSearch();
    updateData({ ...NO_TYPE_SPECIFIC_OPTIONS, selectedPunishmentCategory: type.name });
  };

  const selectLinkedBan = (punishment: ActivePunishmentSearchResult) => {
    updateData({ banToLink: punishment.id });
    setLinkedBanSearch(punishment.id);
    setLinkedBanQuery('');
  };

  const resolvePunishmentTypeName = (result: ActivePunishmentSearchResult): string => {
    const ordinal = result.typeOrdinal;
    if (ordinal === undefined || ordinal === null) return '';
    const match = allPunishmentTypes.find((type) => type.ordinal === ordinal || type.id === ordinal);
    return match?.name ?? String(ordinal);
  };

  const updateEvidenceAt = (index: number, value: string) => {
    const newEvidence = [...evidence];
    newEvidence[index] = value;
    updateData({ evidence: newEvidence });
  };

  const removeEvidenceAt = (index: number) => {
    evidenceRows.removeKeyAt(index);
    updateData({ evidence: evidence.filter((_, i) => i !== index) });
  };

  const renderCategoryGrid = (types: PunishmentTypeConfig[], title: string) => (
    <div className="space-y-1.5">
      <p className="text-xs font-medium text-muted-foreground">{title}</p>
      {types.length > 0 ? (
        <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-6 gap-2">
          {types.map(type => (
            <Button
              key={type.name}
              variant="outline"
              size="sm"
              className="text-xs"
              disabled={requiresOnlinePlayer(type.name) && playerStatus !== 'Online'}
              onClick={() => handleCategorySelect(type)}
            >
              {type.name}
            </Button>
          ))}
        </div>
      ) : (
        <p className="text-xs text-muted-foreground">
          {isLoading ? t('common.loading') : t('punishment.noTypesInCategory')}
        </p>
      )}
    </div>
  );

  const renderSegmented = <T extends string>(
    label: string,
    value: T | undefined,
    options: readonly { readonly value: T; readonly labelKey: string }[],
    onSelect: (value: T) => void,
  ) => (
    <div className="space-y-2">
      <Label>{label}</Label>
      <Tabs
        value={value ?? ''}
        onValueChange={(next) => {
          const option = options.find((candidate) => candidate.value === next);
          if (option) onSelect(option.value);
        }}
      >
        <TabsList className="grid w-full" style={{ gridTemplateColumns: `repeat(${String(options.length)}, minmax(0, 1fr))` }}>
          {options.map((option) => (
            <TabsTrigger key={option.value} value={option.value}>{t(option.labelKey)}</TabsTrigger>
          ))}
        </TabsList>
      </Tabs>
    </div>
  );

  const hidesSeverityControls = (punishmentType: PunishmentTypeConfig) =>
    isManualPunishment(punishmentType.name) || isPermanentUntilIdentityChange(punishmentType);

  const renderSeveritySelection = (punishmentType: PunishmentTypeConfig) => {
    if (hidesSeverityControls(punishmentType) || punishmentType.singleSeverityPunishment) return null;

    return renderSegmented(t('punishment.severity'), data.selectedSeverity, SEVERITY_OPTIONS, (selectedSeverity) => updateData({ selectedSeverity }));
  };

  const renderOffenseSelection = (punishmentType: PunishmentTypeConfig) => {
    if (hidesSeverityControls(punishmentType) || !punishmentType.singleSeverityPunishment) return null;

    return renderSegmented(
      t('punishment.status'),
      appliedSelection.selectedOffenseLevel,
      OFFENSE_LEVELS.map((level) => ({ value: level.id, labelKey: level.labelKey })),
      (selectedOffenseLevel) => updateData({ selectedOffenseLevel }),
    );
  };

  const renderDurationControls = (punishmentType: PunishmentTypeConfig) => {
    if (!hasStaffChosenDuration(punishmentType.name)) return null;

    const permanentCheckboxId = `permanent-${punishmentType.name.toLowerCase().replace(' ', '-')}`;
    const durationUnit = data.duration?.unit ?? 'hours';

    return (
      <div className="space-y-2">
        <div className="flex items-center justify-between">
          <Label htmlFor={`${permanentCheckboxId}-value`}>{t('punishment.duration')}</Label>
          <div className="flex items-center">
            <Checkbox
              id={permanentCheckboxId}
              checked={data.isPermanent ?? false}
              onCheckedChange={(checked) => {
                updateData({
                  isPermanent: checked === true,
                  duration: checked === true ? undefined : { value: 24, unit: 'hours' }
                });
              }}
            />
            <label
              htmlFor={permanentCheckboxId}
              className="text-sm ml-2"
            >
              {t('punishment.permanent')}
            </label>
          </div>
        </div>

        {!data.isPermanent && (
          <div className="flex gap-2">
            <Input
              id={`${permanentCheckboxId}-value`}
              type="number"
              className="flex-1"
              value={data.duration?.value ?? ''}
              onChange={(e) => updateData({
                duration: {
                  value: Number.parseInt(e.target.value, 10) || 1,
                  unit: durationUnit
                }
              })}
              min={1}
            />
            <Select
              value={durationUnit}
              onValueChange={(value) => {
                const unit = findDurationUnit(value);
                if (!unit) return;
                updateData({
                  duration: {
                    value: data.duration?.value ?? 1,
                    unit: unit.value
                  }
                });
              }}
            >
              <SelectTrigger className="w-32" aria-label={t('punishment.duration')}>
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {DURATION_UNITS.map(unit => (
                  <SelectItem key={unit.value} value={unit.value}>{t(unit.labelKey)}</SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
        )}
      </div>
    );
  };

  const renderOptionCheckbox = (
    id: string,
    checked: boolean | undefined,
    onCheckedChange: (checked: boolean) => void,
    label: string,
    description?: string
  ) => (
    <div key={id} className="flex items-start gap-2">
      <Checkbox
        id={id}
        className="mt-0.5"
        checked={checked ?? false}
        onCheckedChange={(state) => onCheckedChange(state === true)}
      />
      <div className="grid gap-0.5">
        <Label htmlFor={id} className="font-normal">{label}</Label>
        {description !== undefined && <p className="text-xs text-muted-foreground">{description}</p>}
      </div>
    </div>
  );

  const renderLinkedBanSearch = () => (
    <div className="space-y-3">
      <div className="space-y-2">
        <Label htmlFor="linked-ban-search">{t('punishment.searchToLink')}</Label>
        <div className="relative">
          <Input
            id="linked-ban-search"
            placeholder={t('punishment.linkedBanSearchPlaceholder')}
            value={linkedBanSearch}
            onChange={(e) => {
              setLinkedBanSearch(e.target.value);
              setLinkedBanQuery(e.target.value);
            }}
          />
          {linkedBanSearchResults.length > 0 && (
            <div className="absolute top-full left-0 right-0 z-10 bg-background border border-border rounded-md shadow-lg max-h-32 overflow-y-auto">
              {linkedBanSearchResults.map((punishment) => (
                <button
                  type="button"
                  key={punishment.id}
                  className="block w-full p-2 text-left hover:bg-muted border-b last:border-b-0"
                  onClick={() => selectLinkedBan(punishment)}
                >
                  <span className="block text-sm font-medium">{punishment.id}</span>
                  <span className="block text-xs text-muted-foreground">
                    {[punishment.playerName, resolvePunishmentTypeName(punishment), punishment.status].filter(Boolean).join(' · ')}
                  </span>
                </button>
              ))}
            </div>
          )}
        </div>
        {data.banToLink && (
          <p className="text-xs text-muted-foreground">{t('punishment.selectedLinkedBan', { id: data.banToLink })}</p>
        )}
      </div>
    </div>
  );

  const configuredTypeOptions = (punishmentType: PunishmentTypeConfig): React.ReactNode[] => {
    const options: React.ReactNode[] = [];
    if (punishmentType.canBeAltBlocking) {
      options.push(renderOptionCheckbox('altBlocking', data.altBlocking, (checked) => updateData({ altBlocking: checked }), t('punishment.altBlocking'), t('punishment.altBlockingDesc')));
    }
    if (punishmentType.canBeStatWiping) {
      options.push(renderOptionCheckbox('statWiping', data.statWiping, (checked) => updateData({ statWiping: checked }), t('punishment.statWiping'), t('punishment.statWipingDesc')));
    }
    return options;
  };

  const renderToggle = (toggle: ManualPunishmentToggle) => {
    const binding: ToggleBinding = TOGGLE_BINDINGS[toggle];
    return renderOptionCheckbox(toggle, binding.isChecked(data), (checked) => updateData(binding.update(checked)), t(binding.labelKey));
  };

  const renderOptionList = (options: React.ReactNode[]) => (
    <fieldset className="space-y-2">
      <legend className="mb-2 text-sm font-medium">{t('punishment.options')}</legend>
      <div className="space-y-2">
        {options}
        {renderOptionCheckbox('silentPunishment', data.silentPunishment, (checked) => updateData({ silentPunishment: checked }), t('punishment.silent'), t('punishment.silentDesc'))}
      </div>
    </fieldset>
  );

  const renderSpecialOptions = (punishmentType: PunishmentTypeConfig, layout: PunishmentOptionsLayout) => {
    switch (layout.kind) {
      case 'linkedBanSearch':
        return renderLinkedBanSearch();
      case 'none':
        return null;
      case 'configured':
        return renderOptionList(configuredTypeOptions(punishmentType));
      case 'toggles':
        return renderOptionList(layout.toggles.map(renderToggle));
    }
  };

  const renderTicketSelection = () => {
    const openTickets = availableTickets.filter(ticket => !ticket.locked);
    if (openTickets.length === 0) return null;
    const attachedReports = data.attachReports ?? [];

    return (
      <fieldset className="space-y-2">
        <legend className="mb-1 text-sm font-medium">{t('punishment.linkTickets')}</legend>
        <p className="text-xs text-muted-foreground">{t('punishment.linkTicketsDesc')}</p>
        <div className="space-y-1.5 max-h-32 overflow-y-auto">
          {openTickets.map(ticket => (
            <div key={ticket.id} className="flex items-center gap-2">
              <Checkbox
                id={`ticket-${ticket.id}`}
                checked={attachedReports.includes(ticket.id)}
                onCheckedChange={(checked) => {
                  updateData({
                    attachReports: checked === true
                      ? [...attachedReports, ticket.id]
                      : attachedReports.filter(id => id !== ticket.id)
                  });
                }}
              />
              <label htmlFor={`ticket-${ticket.id}`} className="text-sm cursor-pointer flex-1">
                <span className="font-medium">{ticket.id}</span>
                <span className="text-muted-foreground ml-2">{ticket.subject || ticket.type}</span>
              </label>
            </div>
          ))}
        </div>
      </fieldset>
    );
  };

  const renderReasonField = (punishmentType: PunishmentTypeConfig) => {
    const placeholderKey = reasonPlaceholderKey(punishmentType.name);
    if (placeholderKey === null) return null;

    return (
      <div className="space-y-2">
        <Label htmlFor="punishment-reason">{t('punishment.reason')}</Label>
        <Textarea
          id="punishment-reason"
          className="min-h-[60px]"
          placeholder={t(placeholderKey)}
          value={data.reason ?? ''}
          onChange={(e) => updateData({ reason: e.target.value })}
        />
      </div>
    );
  };

  const renderEvidenceFields = () => (
    <div className="space-y-2">
      <Label>{t('punishment.evidence')}</Label>

      <div className="space-y-2">
        {evidence.map((evidenceItem, index) => {
          const uploaded = isUploadedEvidence(evidenceItem);
          return (
            <div key={evidenceRows.keys[index]} className="flex items-center gap-2">
              <Input
                type="text"
                className={uploaded ? 'h-9 flex-1 bg-muted text-muted-foreground' : 'h-9 flex-1'}
                aria-label={t('punishment.evidence')}
                placeholder={t('punishment.evidencePlaceholder')}
                value={evidenceDisplayValue(evidenceItem)}
                onChange={(e) => {
                  if (uploaded) return;
                  updateEvidenceAt(index, e.target.value);
                }}
                readOnly={uploaded}
              />

              <MediaUpload
                uploadType="evidence"
                onUploadComplete={(result, file) => {
                  updateEvidenceAt(index, JSON.stringify({
                    url: result.url,
                    fileName: file?.name || t('evidence.unknownFile'),
                    fileType: file?.type || 'application/octet-stream',
                    fileSize: file?.size ?? 0
                  }));
                }}
                metadata={{
                  playerId: playerId,
                  category: 'punishment'
                }}
                variant="button-only"
                maxFiles={1}
              />

              <Button
                variant="outline"
                size="sm"
                onClick={() => removeEvidenceAt(index)}
              >
                {t('common.remove')}
              </Button>
            </div>
          );
        })}

        <Button
          variant="outline"
          size="sm"
          onClick={() => updateData({ evidence: [...evidence, ''] })}
        >
          {t('player.addEvidence')}
        </Button>
      </div>
    </div>
  );

  const renderTextFields = (punishmentType: PunishmentTypeConfig) => (
    <>
      {renderReasonField(punishmentType)}
      {renderEvidenceFields()}
      <div className="space-y-2">
        <Label htmlFor="punishment-staff-notes">{t('punishment.staffNotes')}</Label>
        <Textarea
          id="punishment-staff-notes"
          className="min-h-[60px]"
          value={data.staffNotes ?? ''}
          onChange={(e) => updateData({ staffNotes: e.target.value })}
        />
      </div>
    </>
  );

  if (!data.selectedPunishmentCategory) {
    return (
      <div className={compact ? "space-y-3" : "space-y-4"}>
        <div className="space-y-4">
          {renderCategoryGrid(punishmentTypesByCategory.Administrative, t(PUNISHMENT_CATEGORY_LABEL_KEYS.administrative))}
          {renderCategoryGrid(punishmentTypesByCategory.Social, t(PUNISHMENT_CATEGORY_LABEL_KEYS.social))}
          {renderCategoryGrid(punishmentTypesByCategory.Gameplay, t(PUNISHMENT_CATEGORY_LABEL_KEYS.gameplay))}
        </div>
      </div>
    );
  }

  if (!currentPunishmentType) return null;

  const punishmentDescription = describePunishment(currentPunishmentType, appliedSelection, getSelectedSeverityPreview(), t);

  return (
    <div className="space-y-4">
      <div className="flex items-center gap-2">
        <Button
          variant="ghost"
          size="icon"
          className="h-8 w-8"
          onClick={() => updateData({ selectedPunishmentCategory: undefined })}
          aria-label={t('common.back')}
        >
          <ArrowLeft className="h-4 w-4" />
        </Button>
        <span className="font-medium">{currentPunishmentType.name}</span>
      </div>

      <div className="space-y-4">
        {renderSeveritySelection(currentPunishmentType)}
        {renderOffenseSelection(currentPunishmentType)}
        {renderDurationControls(currentPunishmentType)}
        {renderSpecialOptions(currentPunishmentType, punishmentOptionsLayout(currentPunishmentType.name))}
        {renderTextFields(currentPunishmentType)}
        {renderTicketSelection()}
      </div>

      <Button
        onClick={handleApplyPunishment}
        disabled={isApplying || punishmentFormIssue(data, currentPunishmentType) !== null}
        className="w-full"
      >
        {isApplying
          ? t('punishment.applying')
          : t('punishment.applyWithPreview', { preview: punishmentDescription || t('punishment.selectOptions') })}
      </Button>
      {playerStatus !== 'Online' && (
        <p className="text-xs text-muted-foreground text-center">
          {t('punishment.offlineTimerNotice')}
        </p>
      )}
    </div>
  );
};

export default PlayerPunishment;
