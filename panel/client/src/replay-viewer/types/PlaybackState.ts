export type PlaybackRate = 0.25 | 0.5 | 1 | 2 | 4 | 8;

export interface PlaybackState {
  /** Whether the replay is currently playing */
  playing: boolean;
  /** Current playback time in milliseconds from start */
  currentTimeMs: number;
  /** Total duration of the replay in milliseconds */
  totalDurationMs: number;
  /** Current playback speed multiplier */
  rate: PlaybackRate;
  /** UUID of the player being followed, or null for free camera */
  followingPlayer: string | null;
}
