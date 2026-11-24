import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { punishmentCategoryLabelKey } from '@/lib/punishment-category-labels';
import { Pencil, Plus, Trash2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Field } from '@/components/ui/field';
import { GroupRow, GroupRows } from '@/components/ui/group-list';
import { IconAction } from '@/components/ui/icon-action';
import { Label } from '@/components/ui/label';
import { Badge } from '@/components/ui/badge';
import { Switch } from '@/components/ui/switch';
import { Textarea } from '@/components/ui/textarea';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import type { PunishmentType } from '@proto/modl/v1/settings_pb.ts';
import { usePermissions } from '@/hooks/use-permissions';
import { useConfirmation } from '@/hooks/use-confirmation';
import { AiProviderSettings } from './ai/AiProviderSettings';
import { useSettingsPageCanModify } from './layout/SettingsPageFrame';
import { aiPunishmentConfigKey, canToggleAiReview, selectableAiPunishmentTypes } from '@/lib/ai-moderation';
import { useAiStatus } from '@/hooks/data/ai';
import { usePunishmentTypes } from '@/hooks/data/punishments';
import { SettingsLoading, SettingsSection, SettingsSections } from './layout/SettingsSections';
import { useAiModerationDraft } from './use-ai-moderation-state';

export interface AIPunishmentConfig {
  id: string;
  name: string;
  aiDescription: string;
  enabled: boolean;
}

const NO_PUNISHMENT_TYPES: PunishmentType[] = [];

const TicketAiModerationSettings = () => {
  const { t } = useTranslation();
  const { canLoadSettingsData } = usePermissions();
  const { data: punishmentTypes = NO_PUNISHMENT_TYPES } = usePunishmentTypes({ enabled: canLoadSettingsData('punishmentTypes') });
  const { draft, isLoading } = useAiModerationDraft();
  const { data: aiStatus } = useAiStatus(true);
  const [isAddDialogOpen, setIsAddDialogOpen] = useState(false);
  const [editedConfig, setEditedConfig] = useState<AIPunishmentConfig | null>(null);
  const confirmation = useConfirmation();
  const canModify = useSettingsPageCanModify();
  const aiModerationSettings = draft.value;
  const setAiModerationSettings = draft.update;
  const configs = Object.values(aiModerationSettings.aiPunishmentConfigs);

  const removeConfig = async (config: AIPunishmentConfig) => {
    const confirmed = await confirmation.confirm({
      title: t('settings.tickets.removeAIPunishmentTitle'),
      description: t('settings.tickets.removeAIPunishmentConfirm', { name: config.name || t('common.unknown') }),
      action: t('common.remove'),
      destructive: true,
    });
    if (!confirmed) return;
    setAiModerationSettings((prev) => ({
      ...prev,
      aiPunishmentConfigs: Object.fromEntries(Object.entries(prev.aiPunishmentConfigs).filter(([configId]) => configId !== config.id)),
    }));
  };

  return (
    <SettingsSections>
      <SettingsSection>
        <AiProviderSettings />
      </SettingsSection>

      {isLoading ? <SettingsLoading /> : (
        <SettingsSection title={t('settings.tickets.reviewTitle')}>
          <div className="flex items-center justify-between gap-4">
            <Label htmlFor="ai-review-enabled">{t('settings.tickets.enableAIModeration')}</Label>
            <Switch
              id="ai-review-enabled"
              checked={aiModerationSettings.enableAIReview}
              disabled={!canModify || !canToggleAiReview(aiModerationSettings.enableAIReview, aiStatus?.ready === true)}
              onCheckedChange={(checked) => {
                setAiModerationSettings((prev) => ({ ...prev, enableAIReview: checked }));
              }}
            />
          </div>
          {aiModerationSettings.enableAIReview && (
            <>
              <div className="flex items-center justify-between gap-4">
                <Label htmlFor="ai-automated-actions">{t('settings.tickets.enableAutomatedActions')}</Label>
                <Switch
                  id="ai-automated-actions"
                  checked={aiModerationSettings.enableAutomatedActions}
                  disabled={!canModify}
                  onCheckedChange={(checked) => {
                    setAiModerationSettings((prev) => ({ ...prev, enableAutomatedActions: checked }));
                  }}
                />
              </div>

              <div className="space-y-3">
                <div className="flex min-h-9 items-center justify-between gap-2">
                  <h4 className="text-sm font-medium">{t('settings.tickets.aiPunishmentTypes')}</h4>
                  {canModify && <IconAction icon={Plus} label={t('settings.tickets.addPunishmentType')} variant="outline" onClick={() => { setIsAddDialogOpen(true); }} />}
                </div>
                {configs.length > 0 ? (
                  <GroupRows>
                    {configs.map((config) => (
                      <GroupRow
                        key={config.id}
                        leading={(
                          <Switch
                            checked={config.enabled}
                            disabled={!canModify}
                            aria-label={config.name || t('common.unknown')}
                            onCheckedChange={(checked) => {
                              setAiModerationSettings((prev) => ({
                                ...prev,
                                aiPunishmentConfigs: { ...prev.aiPunishmentConfigs, [config.id]: { ...(prev.aiPunishmentConfigs[config.id] ?? config), enabled: checked } },
                              }));
                            }}
                          />
                        )}
                        title={config.name || t('common.unknown')}
                        description={config.aiDescription}
                        actions={canModify && (
                          <>
                            <IconAction icon={Pencil} label={t('settings.tickets.editAIPunishmentConfig')} onClick={() => { setEditedConfig(config); }} />
                            <IconAction
                              icon={Trash2}
                              label={t('settings.tickets.removeAIPunishmentTitle')}
                              destructive
                              onClick={() => { void removeConfig(config); }}
                            />
                          </>
                        )}
                      />
                    ))}
                  </GroupRows>
                ) : (
                  <p className="py-8 text-center text-sm text-muted-foreground">{t('settings.tickets.noAIPunishmentTypes')}</p>
                )}
              </div>
            </>
          )}
        </SettingsSection>
      )}

      {isAddDialogOpen && (
        <AddAIPunishmentDialog
          punishmentTypes={punishmentTypes}
          existingConfigs={aiModerationSettings.aiPunishmentConfigs}
          onEnable={(selectedType, aiDescription) => {
            const configKey = aiPunishmentConfigKey(selectedType);
            if (configKey !== null) {
              setAiModerationSettings((prev) => ({
                ...prev,
                aiPunishmentConfigs: {
                  ...prev.aiPunishmentConfigs,
                  [configKey]: { id: configKey, name: selectedType.name, aiDescription, enabled: true },
                },
              }));
            }
            setIsAddDialogOpen(false);
          }}
          onClose={() => { setIsAddDialogOpen(false); }}
        />
      )}

      {confirmation.dialog}

      {editedConfig && (
        <EditAIPunishmentDialog
          config={editedConfig}
          onSave={(aiDescription) => {
            setAiModerationSettings((prev) => ({
              ...prev,
              aiPunishmentConfigs: {
                ...prev.aiPunishmentConfigs,
                [editedConfig.id]: { ...(prev.aiPunishmentConfigs[editedConfig.id] ?? editedConfig), aiDescription },
              },
            }));
            setEditedConfig(null);
          }}
          onClose={() => { setEditedConfig(null); }}
        />
      )}
    </SettingsSections>
  );
};

interface AddAIPunishmentDialogProps {
  punishmentTypes: PunishmentType[];
  existingConfigs: Record<string, AIPunishmentConfig>;
  onEnable: (punishmentType: PunishmentType, aiDescription: string) => void;
  onClose: () => void;
}

const AddAIPunishmentDialog = ({ punishmentTypes, existingConfigs, onEnable, onClose }: AddAIPunishmentDialogProps) => {
  const { t } = useTranslation();
  const categoryLabel = (category: string): string => {
    const key = punishmentCategoryLabelKey(category);
    return key === undefined ? category : t(key);
  };
  const [selectedTypeId, setSelectedTypeId] = useState<number | null>(null);
  const [description, setDescription] = useState('');
  const selectedType = selectedTypeId !== null ? punishmentTypes.find(pt => pt.id === selectedTypeId) : undefined;

  return (
    <Dialog open onOpenChange={(open) => { if (!open) onClose(); }}>
      <DialogContent className="max-w-lg">
        <DialogHeader>
          <DialogTitle>{t('settings.tickets.enableAIPunishmentType')}</DialogTitle>
          <DialogDescription>
            {selectedTypeId !== null
              ? (selectedType ? t('settings.tickets.configureAIDescFor', { name: selectedType.name }) : t('settings.tickets.configureAIDescSelected'))
              : t('settings.tickets.selectPunishmentTypeForAI')}
          </DialogDescription>
        </DialogHeader>

        <div className="space-y-4">
          {selectedType && (
            <div className="bg-muted/30 p-3 rounded-lg">
              <div className="flex items-center justify-between">
                <div>
                  <h5 className="font-medium">{selectedType.name}</h5>
                  <div className="flex items-center gap-2 mt-1">
                    <Badge variant="outline">{categoryLabel(selectedType.category)}</Badge>
                  </div>
                </div>
              </div>
            </div>
          )}

          {selectedTypeId === null && (
            <Field label={t('settings.tickets.selectPunishmentType')}>
              {(control) => (
                <Select value="" onValueChange={(value) => setSelectedTypeId(Number.parseInt(value, 10))}>
                  <SelectTrigger {...control}>
                    <SelectValue placeholder={t('settings.tickets.choosePunishmentType')} />
                  </SelectTrigger>
                  <SelectContent className="max-h-60 overflow-y-auto">
                    {selectableAiPunishmentTypes(punishmentTypes, Object.keys(existingConfigs))
                      .map((punishmentType) => (
                        <SelectItem key={punishmentType.id} value={String(punishmentType.id)}>
                          {t('settings.tickets.punishmentTypeOption', { name: punishmentType.name, category: categoryLabel(punishmentType.category) })}
                        </SelectItem>
                      ))}
                  </SelectContent>
                </Select>
              )}
            </Field>
          )}

          {selectedTypeId !== null && (
            <div className="space-y-2">
              <Label htmlFor="ai-punishment-desc">{t('settings.tickets.aiDescription')}</Label>
              <Textarea
                id="ai-punishment-desc"
                className="min-h-[100px]"
                placeholder={t('settings.tickets.aiDescriptionPlaceholder')}
                value={description}
                onChange={(e) => setDescription(e.target.value)}
              />
            </div>
          )}
        </div>

        <DialogFooter>
          <Button variant="outline" onClick={onClose}>
            {t('common.cancel')}
          </Button>
          <Button
            onClick={() => {
              if (selectedType && description.trim()) {
                onEnable(selectedType, description.trim());
              }
            }}
            disabled={!selectedType || !description.trim()}
          >
            {t('settings.tickets.enableForAI')}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

interface EditAIPunishmentDialogProps {
  config: AIPunishmentConfig;
  onSave: (aiDescription: string) => void;
  onClose: () => void;
}

const EditAIPunishmentDialog = ({ config, onSave, onClose }: EditAIPunishmentDialogProps) => {
  const { t } = useTranslation();
  const [description, setDescription] = useState(config.aiDescription);

  return (
    <Dialog open onOpenChange={(open) => { if (!open) onClose(); }}>
      <DialogContent className="max-w-lg">
        <DialogHeader>
          <DialogTitle>{t('settings.tickets.editAIPunishmentConfig')}</DialogTitle>
          <DialogDescription className="sr-only">{config.name}</DialogDescription>
        </DialogHeader>

        <div className="space-y-4">
          <div className="space-y-2">
            <Label htmlFor="edit-ai-punishment-desc">{t('settings.tickets.aiDescription')}</Label>
            <Textarea
              id="edit-ai-punishment-desc"
              className="min-h-[100px]"
              value={description}
              onChange={(e) => { setDescription(e.target.value); }}
            />
          </div>
        </div>

        <DialogFooter>
          <Button variant="outline" onClick={onClose}>
            {t('common.cancel')}
          </Button>
          <Button
            onClick={() => {
              if (description.trim()) {
                onSave(description.trim());
              }
            }}
            disabled={!description.trim()}
          >
            {t('common.save')}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default TicketAiModerationSettings;
