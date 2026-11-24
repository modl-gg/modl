export type CheatType =
  | 'aim'
  | 'trigger'
  | 'strafe_wtap'
  | 'connection'
  | 'scaffold'
  | 'automation'
  | 'xray_esp'
  | 'movement'
  | 'other';

export type Verdict = 'legit' | 'cheating' | 'unsure';

export interface TimeRange {
  startMs: number;
  endMs: number;
}

export interface DraftTimeRange extends TimeRange {
  readonly id: number;
}

export interface DraftCheatDetail {
  type: CheatType;
  timeRanges: DraftTimeRange[];
}

export interface CheatDetail {
  type: CheatType;
  timeRanges: TimeRange[];
}

export interface PlayerLabel {
  uuid: string;
  playerName: string;
  verdict: Verdict;
  confidence: number;
  cheats: CheatDetail[];
  notes: string;
}
