import type { AssetLibrary } from '../../assets/AssetLibrary';
import type { EffectView } from '../../engine/ReplayPlayer';
import type { HeartKind } from './hud-layout';

const NAMESPACE = 'minecraft';
const ROMAN = ['', 'II', 'III', 'IV', 'V', 'VI', 'VII', 'VIII', 'IX', 'X'];

const BENEFICIAL_EFFECTS: ReadonlySet<string> = new Set([
  'speed', 'haste', 'strength', 'instant_health', 'jump_boost', 'regeneration', 'resistance', 'fire_resistance',
  'water_breathing', 'invisibility', 'night_vision', 'health_boost', 'absorption', 'saturation', 'luck',
  'slow_falling', 'conduit_power', 'dolphins_grace', 'hero_of_the_village',
]);

export interface NamespacedKey {
  readonly namespace: string;
  readonly path: string;
}

export function namespacedKey(key: string): NamespacedKey {
  const separator = key.indexOf(':');
  return separator < 0 ? { namespace: NAMESPACE, path: key } : { namespace: key.slice(0, separator), path: key.slice(separator + 1) };
}

export function titleCase(path: string): string {
  return path.split(/[_/]/).filter((part) => part !== '').map((part) => part.charAt(0).toUpperCase() + part.slice(1)).join(' ');
}

export function itemDisplayName(assets: AssetLibrary, itemKey: string): string {
  const { namespace, path } = namespacedKey(itemKey);
  return assets.lang(`item.${namespace}.${path}`) ?? assets.lang(`block.${namespace}.${path}`) ?? titleCase(path);
}

export function effectDisplayName(assets: AssetLibrary, effect: EffectView): string {
  const { namespace, path } = namespacedKey(effect.effect);
  const name = assets.lang(`effect.${namespace}.${path}`) ?? titleCase(path);
  const level = ROMAN[effect.amplifier] ?? String(effect.amplifier + 1);
  return level === '' ? name : `${name} ${level}`;
}

export function isBeneficialEffect(effect: EffectView): boolean {
  return BENEFICIAL_EFFECTS.has(namespacedKey(effect.effect).path);
}

export function effectIconSprite(effect: EffectView): string {
  return `mob_effect/${namespacedKey(effect.effect).path}`;
}

function hasEffect(effects: readonly EffectView[], path: string): boolean {
  return effects.some((effect) => namespacedKey(effect.effect).path === path);
}

export function heartKindFor(effects: readonly EffectView[]): HeartKind {
  if (hasEffect(effects, 'poison')) return 'poisoned';
  if (hasEffect(effects, 'wither')) return 'withered';
  return 'normal';
}

export function isHungry(effects: readonly EffectView[]): boolean {
  return hasEffect(effects, 'hunger');
}

export function formatClock(ms: number): string {
  const totalSeconds = Math.max(0, Math.floor(ms / 1000));
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return `${String(minutes)}:${String(seconds).padStart(2, '0')}`;
}
