import React, { useReducer } from 'react';
import { useTranslation } from 'react-i18next';
import type { PlayerLabel, CheatType, CheatDetail, DraftCheatDetail, DraftTimeRange, TimeRange, Verdict } from '../types/labeling';
import { PlayerVerdictStep } from './labeling/PlayerVerdictStep';
import { CheatTypeStep } from './labeling/CheatTypeStep';
import { TimeRangeStep } from './labeling/TimeRangeStep';
import { ConfidenceStep } from './labeling/ConfidenceStep';
import { ReviewStep, type LabelSubmitHandler } from './labeling/ReviewStep';

type Step = 'verdict' | 'cheat_types' | 'time_ranges' | 'confidence' | 'review';

interface WizardState {
  step: Step;
  playerIndex: number;
  labels: PlayerLabel[];
  // Current player working state
  currentVerdict: Verdict | null;
  selectedCheats: CheatType[];
  cheatDetails: DraftCheatDetail[];
  currentCheatIndex: number;
  nextRangeId: number;
  confidence: number;
  notes: string;
}

type WizardAction =
  | { type: 'SET_VERDICT'; verdict: Verdict }
  | { type: 'TOGGLE_CHEAT'; cheatType: CheatType }
  | { type: 'CHEATS_NEXT' }
  | { type: 'ADD_RANGE' }
  | { type: 'SET_RANGE_START'; index: number; timeMs: number }
  | { type: 'SET_RANGE_END'; index: number; timeMs: number }
  | { type: 'REMOVE_RANGE'; index: number }
  | { type: 'TIME_RANGES_NEXT' }
  | { type: 'SET_CONFIDENCE'; value: number }
  | { type: 'SET_NOTES'; value: string }
  | { type: 'CONFIDENCE_NEXT'; players: { uuid: string; playerName: string }[] };

function initState(): WizardState {
  return {
    step: 'verdict',
    playerIndex: 0,
    labels: [],
    currentVerdict: null,
    selectedCheats: [],
    cheatDetails: [],
    currentCheatIndex: 0,
    nextRangeId: 0,
    confidence: 75,
    notes: '',
  };
}

function resetCurrentPlayer(state: WizardState): WizardState {
  return {
    ...state,
    currentVerdict: null,
    selectedCheats: [],
    cheatDetails: [],
    currentCheatIndex: 0,
    confidence: 75,
    notes: '',
  };
}

function updateCurrentCheatRanges(
  state: WizardState,
  transform: (ranges: DraftTimeRange[]) => DraftTimeRange[],
): WizardState {
  const current = state.cheatDetails[state.currentCheatIndex];
  if (!current) return state;
  const updated = [...state.cheatDetails];
  updated[state.currentCheatIndex] = { ...current, timeRanges: transform(current.timeRanges) };
  return { ...state, cheatDetails: updated };
}

function patchRangeAt(ranges: DraftTimeRange[], index: number, patch: Partial<TimeRange>): DraftTimeRange[] {
  return ranges.map((range, i) => (i === index ? { ...range, ...patch } : range));
}

function emptyRange(id: number): DraftTimeRange {
  return { id, startMs: 0, endMs: 0 };
}

function withRangeIdsConsumed(state: WizardState, count: number): WizardState {
  return { ...state, nextRangeId: state.nextRangeId + count };
}

function toCheatDetail(draft: DraftCheatDetail): CheatDetail {
  return {
    type: draft.type,
    timeRanges: draft.timeRanges.map(({ startMs, endMs }) => ({ startMs, endMs })),
  };
}

function reducer(state: WizardState, action: WizardAction): WizardState {
  switch (action.type) {
    case 'SET_VERDICT':
      if (action.verdict === 'legit' || action.verdict === 'unsure') {
        return { ...state, currentVerdict: action.verdict, step: 'confidence' };
      }
      return { ...state, currentVerdict: 'cheating', step: 'cheat_types' };

    case 'TOGGLE_CHEAT': {
      const cheats = state.selectedCheats.includes(action.cheatType)
        ? state.selectedCheats.filter((c) => c !== action.cheatType)
        : [...state.selectedCheats, action.cheatType];
      return { ...state, selectedCheats: cheats };
    }

    case 'CHEATS_NEXT': {
      const details: DraftCheatDetail[] = state.selectedCheats.map((type, i) => ({
        type,
        timeRanges: [emptyRange(state.nextRangeId + i)],
      }));
      return {
        ...withRangeIdsConsumed(state, details.length),
        cheatDetails: details,
        currentCheatIndex: 0,
        step: 'time_ranges',
      };
    }

    case 'ADD_RANGE':
      return updateCurrentCheatRanges(
        withRangeIdsConsumed(state, 1),
        (ranges) => [...ranges, emptyRange(state.nextRangeId)],
      );

    case 'SET_RANGE_START':
      return updateCurrentCheatRanges(state, (ranges) => patchRangeAt(ranges, action.index, { startMs: action.timeMs }));

    case 'SET_RANGE_END':
      return updateCurrentCheatRanges(state, (ranges) => patchRangeAt(ranges, action.index, { endMs: action.timeMs }));

    case 'REMOVE_RANGE':
      return updateCurrentCheatRanges(withRangeIdsConsumed(state, 1), (ranges) => {
        const remaining = ranges.filter((_, i) => i !== action.index);
        return remaining.length > 0 ? remaining : [emptyRange(state.nextRangeId)];
      });

    case 'TIME_RANGES_NEXT': {
      if (state.currentCheatIndex < state.cheatDetails.length - 1) {
        return { ...state, currentCheatIndex: state.currentCheatIndex + 1 };
      }
      return { ...state, step: 'confidence' };
    }

    case 'SET_CONFIDENCE':
      return { ...state, confidence: action.value };

    case 'SET_NOTES':
      return { ...state, notes: action.value };

    case 'CONFIDENCE_NEXT': {
      const player = action.players[state.playerIndex];
      if (!player || state.currentVerdict === null) return state;
      const label: PlayerLabel = {
        uuid: player.uuid,
        playerName: player.playerName,
        verdict: state.currentVerdict,
        confidence: state.confidence,
        cheats: state.currentVerdict === 'cheating' ? state.cheatDetails.map(toCheatDetail) : [],
        notes: state.notes,
      };
      const newLabels = [...state.labels, label];

      if (state.playerIndex < action.players.length - 1) {
        return {
          ...resetCurrentPlayer(state),
          labels: newLabels,
          playerIndex: state.playerIndex + 1,
          step: 'verdict',
        };
      }
      return { ...state, labels: newLabels, step: 'review' };
    }
  }
}

interface LabelingWizardProps {
  players: { uuid: string; playerName: string }[];
  currentTimeMs: number;
  totalDurationMs: number;
  onClose: () => void;
  onSubmit?: LabelSubmitHandler | undefined;
}

export const LabelingWizard: React.FC<LabelingWizardProps> = ({
  players,
  currentTimeMs,
  totalDurationMs,
  onClose,
  onSubmit,
}) => {
  const { t } = useTranslation();
  const [state, dispatch] = useReducer(reducer, undefined, initState);

  const currentPlayer = players[state.playerIndex];
  const currentCheat = state.cheatDetails[state.currentCheatIndex];

  return (
    <div style={{ padding: '14px 16px', color: 'white', fontFamily: 'system-ui, sans-serif' }}>
      {/* Back button */}
      <button
        onClick={onClose}
        style={{
          background: 'none',
          border: 'none',
          color: 'rgba(255, 255, 255, 0.5)',
          fontSize: 12,
          cursor: 'pointer',
          padding: '0 0 8px',
          display: 'flex',
          alignItems: 'center',
          gap: 4,
        }}
      >
        &larr; {t('common.back')}
      </button>

      {/* Progress indicator */}
      {state.step !== 'review' && (
        <div style={{ fontSize: 11, color: 'rgba(255,255,255,0.4)', marginBottom: 12 }}>
          {t('replay.labeling.progress', { current: state.playerIndex + 1, total: players.length })}
        </div>
      )}

      {/* Step content */}
      {state.step === 'verdict' && currentPlayer && (
        <PlayerVerdictStep
          playerName={currentPlayer.playerName}
          onVerdict={(verdict) => dispatch({ type: 'SET_VERDICT', verdict })}
        />
      )}

      {state.step === 'cheat_types' && (
        <CheatTypeStep
          selected={state.selectedCheats}
          onToggle={(type) => dispatch({ type: 'TOGGLE_CHEAT', cheatType: type })}
          onNext={() => dispatch({ type: 'CHEATS_NEXT' })}
        />
      )}

      {state.step === 'time_ranges' && currentCheat && (
        <TimeRangeStep
          cheatType={currentCheat.type}
          ranges={currentCheat.timeRanges}
          currentTimeMs={currentTimeMs}
          totalDurationMs={totalDurationMs}
          onAddRange={() => dispatch({ type: 'ADD_RANGE' })}
          onSetStart={(i, timeMs) => dispatch({ type: 'SET_RANGE_START', index: i, timeMs: timeMs ?? currentTimeMs })}
          onSetEnd={(i, timeMs) => dispatch({ type: 'SET_RANGE_END', index: i, timeMs: timeMs ?? currentTimeMs })}
          onRemoveRange={(i) => dispatch({ type: 'REMOVE_RANGE', index: i })}
          onNext={() => dispatch({ type: 'TIME_RANGES_NEXT' })}
        />
      )}

      {state.step === 'confidence' && (
        <ConfidenceStep
          confidence={state.confidence}
          notes={state.notes}
          onConfidenceChange={(v) => dispatch({ type: 'SET_CONFIDENCE', value: v })}
          onNotesChange={(v) => dispatch({ type: 'SET_NOTES', value: v })}
          onNext={() => dispatch({ type: 'CONFIDENCE_NEXT', players })}
        />
      )}

      {state.step === 'review' && (
        <ReviewStep
          labels={state.labels}
          onSubmit={onSubmit}
          onClose={onClose}
        />
      )}
    </div>
  );
};
