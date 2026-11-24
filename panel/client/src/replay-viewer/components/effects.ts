import { elapsedTicks } from './format';

export interface ActiveEffect {
  readonly effectTypeId: number;
  readonly amplifier: number;
  readonly durationTicks: number;
  readonly appliedAtMs: number;
}

type EffectKind = 'positive' | 'negative' | 'neutral';

interface EffectDefinition {
  readonly labelKey: string;
  readonly kind: EffectKind;
}

const EFFECTS: Readonly<Record<number, EffectDefinition>> = {
  1: { labelKey: 'replay.effects.speed', kind: 'positive' },
  2: { labelKey: 'replay.effects.slowness', kind: 'negative' },
  3: { labelKey: 'replay.effects.haste', kind: 'positive' },
  4: { labelKey: 'replay.effects.miningFatigue', kind: 'negative' },
  5: { labelKey: 'replay.effects.strength', kind: 'positive' },
  6: { labelKey: 'replay.effects.instantHealth', kind: 'positive' },
  7: { labelKey: 'replay.effects.instantDamage', kind: 'negative' },
  8: { labelKey: 'replay.effects.jumpBoost', kind: 'positive' },
  9: { labelKey: 'replay.effects.nausea', kind: 'negative' },
  10: { labelKey: 'replay.effects.regeneration', kind: 'positive' },
  11: { labelKey: 'replay.effects.resistance', kind: 'positive' },
  12: { labelKey: 'replay.effects.fireResistance', kind: 'positive' },
  13: { labelKey: 'replay.effects.waterBreathing', kind: 'positive' },
  14: { labelKey: 'replay.effects.invisibility', kind: 'neutral' },
  15: { labelKey: 'replay.effects.blindness', kind: 'negative' },
  16: { labelKey: 'replay.effects.nightVision', kind: 'positive' },
  17: { labelKey: 'replay.effects.hunger', kind: 'negative' },
  18: { labelKey: 'replay.effects.weakness', kind: 'negative' },
  19: { labelKey: 'replay.effects.poison', kind: 'negative' },
  20: { labelKey: 'replay.effects.wither', kind: 'negative' },
  21: { labelKey: 'replay.effects.healthBoost', kind: 'positive' },
  22: { labelKey: 'replay.effects.absorption', kind: 'positive' },
  23: { labelKey: 'replay.effects.saturation', kind: 'positive' },
  24: { labelKey: 'replay.effects.glowing', kind: 'neutral' },
  25: { labelKey: 'replay.effects.levitation', kind: 'negative' },
  26: { labelKey: 'replay.effects.luck', kind: 'positive' },
  27: { labelKey: 'replay.effects.unluck', kind: 'negative' },
  28: { labelKey: 'replay.effects.slowFalling', kind: 'positive' },
  29: { labelKey: 'replay.effects.conduitPower', kind: 'positive' },
  30: { labelKey: 'replay.effects.dolphinsGrace', kind: 'neutral' },
  31: { labelKey: 'replay.effects.badOmen', kind: 'negative' },
  32: { labelKey: 'replay.effects.heroOfTheVillage', kind: 'positive' },
  33: { labelKey: 'replay.effects.darkness', kind: 'negative' },
};

const EFFECT_COLORS: Readonly<Record<EffectKind, string>> = {
  positive: 'rgba(80, 200, 120, 0.7)',
  negative: 'rgba(220, 80, 80, 0.7)',
  neutral: 'rgba(100, 160, 220, 0.7)',
};

const ROMAN_NUMERALS = ['I', 'II', 'III', 'IV', 'V', 'VI', 'VII', 'VIII', 'IX', 'X'];

export function effectLabelKey(effectTypeId: number): string | undefined {
  return EFFECTS[effectTypeId]?.labelKey;
}

export function effectColor(effectTypeId: number): string {
  return EFFECT_COLORS[EFFECTS[effectTypeId]?.kind ?? 'neutral'];
}

export function effectLevel(amplifier: number): string {
  return ROMAN_NUMERALS[amplifier] ?? String(amplifier + 1);
}

export function remainingEffectTicks(effect: ActiveEffect, currentTimeMs: number): number {
  return effect.durationTicks - elapsedTicks(effect.appliedAtMs, currentTimeMs);
}

export function activeEffectsAt(effects: readonly ActiveEffect[], currentTimeMs: number): ActiveEffect[] {
  return effects.filter((effect) => remainingEffectTicks(effect, currentTimeMs) > 0);
}
