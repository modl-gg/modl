import { useCallback, useMemo, useSyncExternalStore, type Dispatch } from 'react';
import { useTranslation } from 'react-i18next';
import { Plus, X } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { IconAction } from '@/components/ui/icon-action';
import { Label } from '@/components/ui/label';
import { Slider } from '@/components/ui/slider';
import { Textarea } from '@/components/ui/textarea';
import { useSubmitReplayLabels } from '@/hooks/data/replays';
import { useToast } from '@/hooks/use-toast';
import { cn } from '@/lib/utils';
import type { ReplayPlayer } from '../../engine/ReplayPlayer';
import { formatClock } from '../hud/game-text';
import { SHORTCUT_EXEMPT_MARKER } from '../stage/shortcuts';
import {
  CHEAT_TYPES,
  labelsToSubmit,
  playerDraft,
  VERDICTS,
  type CheatDraft,
  type CheatType,
  type LabelAction,
  type LabelDraftStore,
  type LabelSubject,
  type PlayerDraft,
  type Verdict,
} from './label-drafts';

const NOTES_MAX_LENGTH = 1000;

const VERDICT_LABELS: Readonly<Record<Verdict, string>> = {
  legit: 'replayViewer.labels.verdict.legit',
  cheating: 'replayViewer.labels.verdict.cheating',
  unsure: 'replayViewer.labels.verdict.unsure',
};

const CHEAT_LABELS: Readonly<Record<CheatType, string>> = {
  aim: 'replayViewer.labels.cheat.aim',
  trigger: 'replayViewer.labels.cheat.trigger',
  strafe_wtap: 'replayViewer.labels.cheat.strafeWtap',
  connection: 'replayViewer.labels.cheat.connection',
  scaffold: 'replayViewer.labels.cheat.scaffold',
  automation: 'replayViewer.labels.cheat.automation',
  xray_esp: 'replayViewer.labels.cheat.xrayEsp',
  movement: 'replayViewer.labels.cheat.movement',
  other: 'replayViewer.labels.cheat.other',
};

interface LabelPanelProps {
  readonly hidden: boolean;
  readonly replayId: string;
  readonly player: ReplayPlayer;
  readonly store: LabelDraftStore;
  readonly onClose: () => void;
}

export function LabelPanel({ hidden, replayId, player, store, onClose }: LabelPanelProps) {
  const { t } = useTranslation();
  const { toast } = useToast();
  const drafts = useSyncExternalStore(store.subscribe, store.snapshot);
  const dispatch = store.dispatch;
  const submit = useSubmitReplayLabels(replayId);
  const subjects = useMemo<LabelSubject[]>(() => [...player.players()]
    .sort((left, right) => Number(right.isTarget) - Number(left.isTarget) || left.name.localeCompare(right.name))
    .map((info) => ({ uuid: info.uuid, name: info.name })), [player]);
  const currentTime = useCallback(() => player.state().timeMs, [player]);
  const labels = labelsToSubmit(drafts, subjects);

  const handleSubmit = () => {
    if (labels === null) return;
    submit.mutate(labels, {
      onSuccess: () => {
        toast({ title: t('replayViewer.labels.submitted') });
        dispatch({ kind: 'reset' });
        onClose();
      },
      onError: () => {
        toast({ title: t('toast.error'), description: t('replayViewer.labels.submitFailed'), variant: 'destructive' });
      },
    });
  };

  return (
    <aside
      hidden={hidden}
      {...SHORTCUT_EXEMPT_MARKER}
      aria-label={t('replayViewer.labels.title')}
      className="absolute inset-0 z-20 flex flex-col bg-background sm:static sm:w-80 sm:shrink-0 sm:border-l"
    >
      <div className="flex h-11 shrink-0 items-center justify-between border-b pl-4 pr-2">
        <h2 className="text-sm font-semibold">{t('replayViewer.labels.title')}</h2>
        <IconAction icon={X} label={t('common.close')} onClick={onClose} />
      </div>
      <div className="min-h-0 flex-1 divide-y overflow-y-auto">
        {subjects.map((subject) => (
          <SubjectLabel key={subject.uuid} subject={subject} draft={playerDraft(drafts, subject.uuid)} currentTime={currentTime} dispatch={dispatch} />
        ))}
      </div>
      <div className="shrink-0 border-t p-3">
        <Button type="button" className="w-full" disabled={labels === null || submit.isPending} onClick={handleSubmit}>
          {t('replayViewer.labels.submit')}
        </Button>
      </div>
    </aside>
  );
}

interface SubjectLabelProps {
  readonly subject: LabelSubject;
  readonly draft: PlayerDraft;
  readonly currentTime: () => number;
  readonly dispatch: Dispatch<LabelAction>;
}

function SubjectLabel({ subject, draft, currentTime, dispatch }: SubjectLabelProps) {
  const { t } = useTranslation();
  const uuid = subject.uuid;
  return (
    <section className="space-y-3 p-4">
      <h3 className="truncate text-sm font-medium">{subject.name}</h3>
      <div className="grid grid-cols-3 gap-1" role="group" aria-label={subject.name}>
        {VERDICTS.map((verdict) => (
          <Button
            key={verdict}
            type="button"
            size="sm"
            variant={draft.verdict === verdict ? 'default' : 'outline'}
            aria-pressed={draft.verdict === verdict}
            onClick={() => { dispatch({ kind: 'verdict', uuid, verdict }); }}
          >
            {t(VERDICT_LABELS[verdict])}
          </Button>
        ))}
      </div>
      {draft.verdict === 'cheating' && (
        <div className="flex flex-wrap gap-1">
          {CHEAT_TYPES.map((cheat) => {
            const selected = draft.cheats.some((entry) => entry.type === cheat);
            return (
              <button
                key={cheat}
                type="button"
                aria-pressed={selected}
                className={cn(
                  'rounded-full border px-2.5 py-0.5 text-xs transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring',
                  selected ? 'border-primary bg-primary text-primary-foreground' : 'border-border text-muted-foreground hover:bg-muted hover:text-foreground',
                )}
                onClick={() => { dispatch({ kind: 'toggle-cheat', uuid, cheat }); }}
              >
                {t(CHEAT_LABELS[cheat])}
              </button>
            );
          })}
        </div>
      )}
      {draft.verdict === 'cheating' && draft.cheats.map((cheat) => (
        <CheatRanges key={cheat.type} uuid={uuid} cheat={cheat} currentTime={currentTime} dispatch={dispatch} />
      ))}
      {draft.verdict !== null && <LabelDetails uuid={uuid} draft={draft} dispatch={dispatch} />}
    </section>
  );
}

interface CheatRangesProps {
  readonly uuid: string;
  readonly cheat: CheatDraft;
  readonly currentTime: () => number;
  readonly dispatch: Dispatch<LabelAction>;
}

function CheatRanges({ uuid, cheat, currentTime, dispatch }: CheatRangesProps) {
  const { t } = useTranslation();
  return (
    <div className="space-y-1.5">
      <div className="flex items-center justify-between">
        <p className="text-xs font-medium text-muted-foreground">{t(CHEAT_LABELS[cheat.type])}</p>
        <IconAction icon={Plus} label={t('replayViewer.labels.addRange')} className="h-7 w-7" onClick={() => { dispatch({ kind: 'add-range', uuid, cheat: cheat.type }); }} />
      </div>
      {cheat.ranges.map((range) => (
        <div key={range.id} className="flex items-center gap-1" role="group" aria-label={t('replayViewer.labels.range', { start: range.startMs === null ? '' : formatClock(range.startMs), end: range.endMs === null ? '' : formatClock(range.endMs) })}>
          <Button type="button" size="sm" variant="outline" className="flex-1 tabular-nums" onClick={() => { dispatch({ kind: 'set-range', uuid, cheat: cheat.type, id: range.id, edge: 'start', timeMs: currentTime() }); }}>
            {range.startMs === null ? t('replayViewer.labels.start') : formatClock(range.startMs)}
          </Button>
          <Button
            type="button"
            size="sm"
            variant="outline"
            className={cn('flex-1 tabular-nums', range.startMs !== null && range.endMs !== null && range.endMs < range.startMs && 'border-destructive text-destructive-text')}
            onClick={() => { dispatch({ kind: 'set-range', uuid, cheat: cheat.type, id: range.id, edge: 'end', timeMs: currentTime() }); }}
          >
            {range.endMs === null ? t('replayViewer.labels.end') : formatClock(range.endMs)}
          </Button>
          <IconAction icon={X} label={t('replayViewer.labels.removeRange')} className="h-8 w-8" onClick={() => { dispatch({ kind: 'remove-range', uuid, cheat: cheat.type, id: range.id }); }} />
        </div>
      ))}
    </div>
  );
}

function LabelDetails({ uuid, draft, dispatch }: { readonly uuid: string; readonly draft: PlayerDraft; readonly dispatch: Dispatch<LabelAction> }) {
  const { t } = useTranslation();
  const confidenceId = `replay-label-confidence-${uuid}`;
  const notesId = `replay-label-notes-${uuid}`;
  const confidenceText = t('replayViewer.labels.confidence', { value: draft.confidence });
  return (
    <div className="space-y-3">
      <div className="space-y-2">
        <Label id={confidenceId} className="text-xs">{confidenceText}</Label>
        <Slider
          labelledBy={confidenceId}
          valueText={confidenceText}
          min={0}
          max={100}
          step={5}
          value={[draft.confidence]}
          onValueChange={(values) => { dispatch({ kind: 'confidence', uuid, value: values[0] ?? draft.confidence }); }}
        />
      </div>
      <div className="space-y-1.5">
        <Label htmlFor={notesId} className="text-xs">{t('replayViewer.labels.notes')}</Label>
        <Textarea
          id={notesId}
          rows={2}
          maxLength={NOTES_MAX_LENGTH}
          value={draft.notes}
          onChange={(event) => { dispatch({ kind: 'notes', uuid, value: event.target.value }); }}
        />
      </div>
    </div>
  );
}
