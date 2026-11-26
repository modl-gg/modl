import { useId, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Checkbox } from '@/components/ui/checkbox';
import { Label } from '@/components/ui/label';
import { Notice } from '@/components/ui/notice';
import { useBeforeContinue } from '@/hooks/use-before-continue';
import {
  useReplayServerStatuses,
  useReplaySettings,
  useSaveReplaySettings,
  type VersionedReplaySettings,
} from '@/hooks/data/replay-settings';
import { isConflictError } from '@/lib/http-error';
import { formatList } from '@/lib/list-format';
import { useRememberedChoice } from '../setup-choices';
import { StepChoice, useStepChoice, type StepOption } from './StepChoice';
import { StepLoadState } from './StepLoadState';
import type { SetupStepProps } from './step-props';

type Toggle = 'on' | 'off';

const REPLAY_OPTIONS: readonly StepOption<Toggle>[] = [
  { value: 'on', labelKey: 'pages.setup.replays.replaysOn' },
  { value: 'off', labelKey: 'pages.setup.replays.replaysOff' },
];

const ANTICHEAT_OPTIONS: readonly StepOption<Toggle>[] = [
  { value: 'on', labelKey: 'pages.setup.replays.anticheatOn' },
  { value: 'off', labelKey: 'pages.setup.replays.anticheatOff' },
];

function toggleOf(on: boolean): Toggle {
  return on ? 'on' : 'off';
}

function DetectedAnticheats() {
  const { t, i18n } = useTranslation();
  const { statuses } = useReplayServerStatuses();
  const names = [...new Set(statuses.flatMap((server) => server.detectedAnticheats))].sort();
  if (names.length === 0) return null;
  return <p className="text-sm text-muted-foreground">{t('pages.setup.replays.detected', { names: formatList(names, i18n.language) })}</p>;
}

function ReplayChoices({ settings, done }: { settings: VersionedReplaySettings; done: boolean }) {
  const { t } = useTranslation();
  const replaysLabelId = useId();
  const anticheatLabelId = useId();
  const recordId = useId();
  const save = useSaveReplaySettings();
  const [conflict, setConflict] = useState(false);
  const current = settings.value;
  const replays = useStepChoice<Toggle>({
    step: 'replays',
    slot: 'replays',
    options: REPLAY_OPTIONS,
    saved: null,
    persisted: settings.choiceMade ? toggleOf(current.enabled) : null,
    done,
    none: 'off',
    configured: true,
  });
  const anticheat = useStepChoice<Toggle>({
    step: 'replays',
    slot: 'anticheat',
    options: ANTICHEAT_OPTIONS,
    saved: null,
    persisted: settings.choiceMade ? toggleOf(current.anticheatIntegration) : null,
    done,
    none: 'off',
    configured: true,
  });
  const [rememberedRecord, rememberRecord] = useRememberedChoice('replays', 'record');
  const recordEveryPlayer = rememberedRecord === undefined ? current.recordEveryPlayer : rememberedRecord === 'on';

  useBeforeContinue(async () => {
    if (replays.value === null || anticheat.value === null) return false;
    const next = {
      ...current,
      enabled: replays.value === 'on',
      recordEveryPlayer: replays.value === 'on' ? recordEveryPlayer : current.recordEveryPlayer,
      anticheatIntegration: anticheat.value === 'on',
    };
    const unchanged = next.enabled === current.enabled && next.recordEveryPlayer === current.recordEveryPlayer && next.anticheatIntegration === current.anticheatIntegration;
    if (settings.choiceMade && unchanged) return true;
    setConflict(false);
    try {
      await save.mutateAsync({ expectedVersion: settings.version, value: next });
      return true;
    } catch (error) {
      if (!isConflictError(error)) throw error;
      setConflict(true);
      return false;
    }
  });

  return (
    <div className="space-y-8">
      <div className="space-y-3">
        <h3 id={replaysLabelId} className="text-sm font-semibold">{t('pages.setup.replays.replaysTitle')}</h3>
        <StepChoice
          options={REPLAY_OPTIONS}
          labelledBy={replaysLabelId}
          details={{
            on: (
              <div className="flex items-center gap-2 pl-6">
                <Checkbox id={recordId} checked={recordEveryPlayer} onCheckedChange={(checked) => { rememberRecord(toggleOf(checked === true)); }} />
                <Label htmlFor={recordId}>{t('settings.replays.recordEveryPlayer')}</Label>
              </div>
            ),
          }}
          {...replays}
        />
      </div>
      <div className="space-y-3">
        <h3 id={anticheatLabelId} className="text-sm font-semibold">{t('pages.setup.replays.anticheatTitle')}</h3>
        <StepChoice options={ANTICHEAT_OPTIONS} labelledBy={anticheatLabelId} {...anticheat} />
        <DetectedAnticheats />
      </div>
      {conflict && <Notice variant="error" announce>{t('pages.setup.replays.conflict')}</Notice>}
    </div>
  );
}

export function ReplaysStep({ done }: SetupStepProps) {
  const settings = useReplaySettings();
  if (settings.data === undefined || settings.data === null) {
    return <StepLoadState isError={settings.isError || settings.data === null} onRetry={() => { void settings.refetch(); }} />;
  }
  return <ReplayChoices settings={settings.data} done={done} />;
}
