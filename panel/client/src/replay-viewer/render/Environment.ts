import type { Biome } from './world/Biomes.ts';
import { type DayState, scaleColor, skyBrightness } from './Lighting.ts';

export interface EnvironmentInput {
  readonly dimension: string;
  readonly biome: Biome;
  readonly radius: number;
  readonly day: DayState;
  readonly legacy: boolean;
  readonly fullBright: boolean;
}

export interface Environment {
  readonly fogColor: number;
  readonly fogNear: number;
  readonly fogFar: number;
  readonly skyTop: number;
  readonly skyHorizon: number;
  readonly depthFog: boolean;
  readonly squareEdge: number | null;
}

const OVERWORLD_FOG = 0xc0d8ff;
const DEFAULT_SKY = 0x78a7ff;
const NETHER_FOG = 0x330808;
const END_FOG = 0x0f0c14;
const LEGACY_TOP_BLEND = 0.12;
const LEGACY_HORIZON_BLEND = 0.25;
const LEGACY_FOG_START = 1.5;
const LEGACY_FOG_END = 2;

function mix(from: number, to: number, amount: number): number {
  const channel = (shift: number): number => {
    const start = (from >> shift) & 255;
    const end = (to >> shift) & 255;
    return Math.round(start + (end - start) * amount);
  };
  return (channel(16) << 16) | (channel(8) << 8) | channel(0);
}

export function environment(input: EnvironmentInput): Environment {
  if (input.dimension.endsWith('the_nether')) {
    const fog = input.biome.fogColor ?? NETHER_FOG;
    return { fogColor: fog, fogNear: input.radius * 0.05, fogFar: Math.min(input.radius, 192) * 0.5, skyTop: fog, skyHorizon: fog, depthFog: false, squareEdge: null };
  }
  if (input.dimension.endsWith('the_end')) {
    return { fogColor: END_FOG, fogNear: input.radius * 0.75, fogFar: input.radius, skyTop: END_FOG, skyHorizon: END_FOG, depthFog: false, squareEdge: null };
  }
  const chunks = input.radius / 16;
  const blend = 1 - Math.pow(0.25 + 0.75 * chunks / 32, 0.25);
  const base = mix(input.biome.fogColor ?? OVERWORLD_FOG, input.biome.skyColor ?? DEFAULT_SKY, blend);
  const brightness = input.fullBright ? 1 : Math.max(0.05, skyBrightness(input.day));
  const fogColor = scaleColor(base, brightness);
  if (input.legacy) {
    return {
      fogColor,
      fogNear: input.radius * LEGACY_FOG_START,
      fogFar: input.radius * LEGACY_FOG_END,
      skyTop: scaleColor(mix(base, input.biome.skyColor ?? DEFAULT_SKY, LEGACY_TOP_BLEND), brightness),
      skyHorizon: scaleColor(mix(base, OVERWORLD_FOG, LEGACY_HORIZON_BLEND), brightness),
      depthFog: true,
      squareEdge: Math.max(2, Math.floor(input.radius / 16)),
    };
  }
  return {
    fogColor,
    fogNear: input.radius - Math.min(64, Math.max(4, input.radius / 10)),
    fogFar: input.radius,
    skyTop: fogColor,
    skyHorizon: fogColor,
    depthFog: false,
    squareEdge: null,
  };
}
