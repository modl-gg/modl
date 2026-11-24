import type { CheatType, Verdict } from '../../types/labeling';

export const CHEAT_TYPES: readonly CheatType[] = [
  'aim', 'trigger', 'strafe_wtap', 'connection', 'scaffold', 'automation', 'xray_esp', 'movement', 'other',
];

export const CHEAT_LABELS: Readonly<Record<CheatType, { labelKey: string }>> = {
  aim: { labelKey: 'replay.labeling.cheats.aim' },
  trigger: { labelKey: 'replay.labeling.cheats.trigger' },
  strafe_wtap: { labelKey: 'replay.labeling.cheats.strafeWtap' },
  connection: { labelKey: 'replay.labeling.cheats.connection' },
  scaffold: { labelKey: 'replay.labeling.cheats.scaffold' },
  automation: { labelKey: 'replay.labeling.cheats.automation' },
  xray_esp: { labelKey: 'replay.labeling.cheats.xrayEsp' },
  movement: { labelKey: 'replay.labeling.cheats.movement' },
  other: { labelKey: 'common.other' },
};

export interface VerdictStyle {
  readonly labelKey: string;
  readonly color: string;
  readonly background: string;
  readonly border: string;
}

export const VERDICTS: readonly Verdict[] = ['legit', 'cheating', 'unsure'];

export const VERDICT_STYLES: Readonly<Record<Verdict, VerdictStyle>> = {
  legit: {
    labelKey: 'replay.labeling.verdicts.legit',
    color: '#22c55e',
    background: 'rgba(34, 197, 94, 0.2)',
    border: '1px solid rgba(34, 197, 94, 0.4)',
  },
  cheating: {
    labelKey: 'replay.labeling.verdicts.cheating',
    color: '#ef4444',
    background: 'rgba(239, 68, 68, 0.2)',
    border: '1px solid rgba(239, 68, 68, 0.4)',
  },
  unsure: {
    labelKey: 'replay.labeling.verdicts.unsure',
    color: '#eab308',
    background: 'rgba(234, 179, 8, 0.2)',
    border: '1px solid rgba(234, 179, 8, 0.4)',
  },
};
