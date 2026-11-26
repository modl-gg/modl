import type { ReplayLabelInput } from '@/lib/replay-api';

export const VERDICTS = ['legit', 'cheating', 'unsure'] as const;
export type Verdict = (typeof VERDICTS)[number];

export const CHEAT_TYPES = ['aim', 'trigger', 'strafe_wtap', 'connection', 'scaffold', 'automation', 'xray_esp', 'movement', 'other'] as const;
export type CheatType = (typeof CHEAT_TYPES)[number];

export const DEFAULT_CONFIDENCE = 75;

export interface RangeDraft {
  readonly id: number;
  readonly startMs: number | null;
  readonly endMs: number | null;
}

export interface CheatDraft {
  readonly type: CheatType;
  readonly ranges: readonly RangeDraft[];
}

export interface PlayerDraft {
  readonly verdict: Verdict | null;
  readonly cheats: readonly CheatDraft[];
  readonly confidence: number;
  readonly notes: string;
}

export interface LabelDrafts {
  readonly players: Readonly<Record<string, PlayerDraft>>;
  readonly nextRangeId: number;
}

export type RangeEdge = 'start' | 'end';

export type LabelAction =
  | { readonly kind: 'verdict'; readonly uuid: string; readonly verdict: Verdict }
  | { readonly kind: 'toggle-cheat'; readonly uuid: string; readonly cheat: CheatType }
  | { readonly kind: 'add-range'; readonly uuid: string; readonly cheat: CheatType }
  | { readonly kind: 'set-range'; readonly uuid: string; readonly cheat: CheatType; readonly id: number; readonly edge: RangeEdge; readonly timeMs: number }
  | { readonly kind: 'remove-range'; readonly uuid: string; readonly cheat: CheatType; readonly id: number }
  | { readonly kind: 'confidence'; readonly uuid: string; readonly value: number }
  | { readonly kind: 'notes'; readonly uuid: string; readonly value: string }
  | { readonly kind: 'reset' };

export const EMPTY_DRAFTS: LabelDrafts = { players: {}, nextRangeId: 1 };

const EMPTY_PLAYER: PlayerDraft = { verdict: null, cheats: [], confidence: DEFAULT_CONFIDENCE, notes: '' };

export function playerDraft(drafts: LabelDrafts, uuid: string): PlayerDraft {
  return drafts.players[uuid] ?? EMPTY_PLAYER;
}

function withPlayer(drafts: LabelDrafts, uuid: string, update: (draft: PlayerDraft) => PlayerDraft, consumedIds = 0): LabelDrafts {
  return {
    players: { ...drafts.players, [uuid]: update(playerDraft(drafts, uuid)) },
    nextRangeId: drafts.nextRangeId + consumedIds,
  };
}

function withCheat(draft: PlayerDraft, cheat: CheatType, update: (ranges: readonly RangeDraft[]) => readonly RangeDraft[]): PlayerDraft {
  return { ...draft, cheats: draft.cheats.map((entry) => (entry.type === cheat ? { ...entry, ranges: update(entry.ranges) } : entry)) };
}

function emptyRange(id: number): RangeDraft {
  return { id, startMs: null, endMs: null };
}

export function labelReducer(drafts: LabelDrafts, action: LabelAction): LabelDrafts {
  switch (action.kind) {
    case 'verdict':
      return withPlayer(drafts, action.uuid, (draft) => ({ ...draft, verdict: draft.verdict === action.verdict ? null : action.verdict }));
    case 'toggle-cheat':
      return withPlayer(drafts, action.uuid, (draft) => (draft.cheats.some((entry) => entry.type === action.cheat)
        ? { ...draft, cheats: draft.cheats.filter((entry) => entry.type !== action.cheat) }
        : { ...draft, cheats: [...draft.cheats, { type: action.cheat, ranges: [emptyRange(drafts.nextRangeId)] }] }), 1);
    case 'add-range':
      return withPlayer(drafts, action.uuid, (draft) => withCheat(draft, action.cheat, (ranges) => [...ranges, emptyRange(drafts.nextRangeId)]), 1);
    case 'set-range':
      return withPlayer(drafts, action.uuid, (draft) => withCheat(draft, action.cheat, (ranges) => ranges.map((range) => {
        if (range.id !== action.id) return range;
        return action.edge === 'start' ? { ...range, startMs: action.timeMs } : { ...range, endMs: action.timeMs };
      })));
    case 'remove-range':
      return withPlayer(drafts, action.uuid, (draft) => withCheat(draft, action.cheat, (ranges) => ranges.filter((range) => range.id !== action.id)));
    case 'confidence':
      return withPlayer(drafts, action.uuid, (draft) => ({ ...draft, confidence: action.value }));
    case 'notes':
      return withPlayer(drafts, action.uuid, (draft) => ({ ...draft, notes: action.value }));
    case 'reset':
      return EMPTY_DRAFTS;
  }
}

export function hasUnsubmittedLabels(drafts: LabelDrafts): boolean {
  return Object.values(drafts.players).some((draft) => draft.verdict !== null);
}

export function rangeComplete(range: RangeDraft): boolean {
  return range.startMs !== null && range.endMs !== null && range.endMs >= range.startMs;
}

function cheatsComplete(draft: PlayerDraft): boolean {
  return draft.cheats.length > 0 && draft.cheats.every((cheat) => cheat.ranges.length > 0 && cheat.ranges.every(rangeComplete));
}

export interface LabelSubject {
  readonly uuid: string;
  readonly name: string;
}

export function labelsToSubmit(drafts: LabelDrafts, subjects: readonly LabelSubject[]): ReplayLabelInput[] | null {
  const labels: ReplayLabelInput[] = [];
  for (const subject of subjects) {
    const draft = playerDraft(drafts, subject.uuid);
    if (draft.verdict === null) continue;
    if (draft.verdict === 'cheating' && !cheatsComplete(draft)) return null;
    labels.push({
      uuid: subject.uuid,
      playerName: subject.name,
      verdict: draft.verdict,
      confidence: draft.confidence,
      notes: draft.notes.trim(),
      cheats: draft.verdict === 'cheating'
        ? draft.cheats.map((cheat) => ({ type: cheat.type, timeRanges: cheat.ranges.map((range) => ({ startMs: range.startMs ?? 0, endMs: range.endMs ?? 0 })) }))
        : [],
    });
  }
  return labels.length === 0 ? null : labels;
}

export interface LabelDraftStore {
  readonly subscribe: (onChange: () => void) => () => void;
  readonly snapshot: () => LabelDrafts;
  readonly dispatch: (action: LabelAction) => void;
}

export function createLabelDraftStore(): LabelDraftStore {
  let current = EMPTY_DRAFTS;
  const listeners = new Set<() => void>();
  return {
    subscribe: (onChange) => {
      listeners.add(onChange);
      return () => { listeners.delete(onChange); };
    },
    snapshot: () => current,
    dispatch: (action) => {
      current = labelReducer(current, action);
      for (const listener of listeners) listener();
    },
  };
}
