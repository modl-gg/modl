export const LIGHTMAP_GLSL = `
uniform float uSkyFactor;
uniform float uAmbient;
uniform float uFullBright;
uniform float uGamma;

float levelBrightness(float level) {
  float f = level / 15.0;
  float curve = f / (4.0 - 3.0 * f);
  return curve + uAmbient * (1.0 - curve);
}

vec3 notGamma(vec3 color) {
  vec3 inverse = 1.0 - color;
  return 1.0 - inverse * inverse * inverse * inverse;
}

vec3 lightmap(float sky, float block) {
  if (uFullBright > 0.5) return vec3(1.0);
  vec3 skyColor = mix(vec3(uSkyFactor, uSkyFactor, 1.0), vec3(1.0), 0.35);
  float skyBrightness = levelBrightness(sky) * (uSkyFactor * 0.95 + 0.05);
  float blockBrightness = levelBrightness(block) * 1.5;
  vec3 color = vec3(
    blockBrightness,
    blockBrightness * ((blockBrightness * 0.6 + 0.4) * 0.6 + 0.4),
    blockBrightness * (blockBrightness * blockBrightness * 0.6 + 0.4)
  );
  color += skyColor * skyBrightness;
  color = mix(color, vec3(0.75), 0.04);
  color = clamp(color, 0.0, 1.0);
  color = mix(color, notGamma(color), uGamma);
  color = mix(color, vec3(0.75), 0.04);
  return clamp(color, 0.0, 1.0);
}
`;

export interface DayState {
  readonly timeOfDay: number;
  readonly rain: number;
  readonly thunder: number;
}

export function celestialAngle(dayTime: number): number {
  const fraction = dayTime / 24000 - 0.25;
  const d0 = fraction - Math.floor(fraction);
  const d1 = 0.5 - Math.cos(d0 * Math.PI) / 2;
  return (d0 * 2 + d1) / 3;
}

export function skyDarken(state: DayState): number {
  const angle = celestialAngle(state.timeOfDay);
  let value = 1 - (Math.cos(angle * Math.PI * 2) * 2 + 0.2);
  value = Math.min(1, Math.max(0, value));
  value = 1 - value;
  value *= 1 - state.rain * 5 / 16;
  value *= 1 - state.thunder * 5 / 16;
  return value * 0.8 + 0.2;
}

export function skyBrightness(state: DayState): number {
  const angle = celestialAngle(state.timeOfDay);
  const value = Math.cos(angle * Math.PI * 2) * 2 + 0.5;
  return Math.min(1, Math.max(0, value));
}

export function scaleColor(color: number, factor: number): number {
  const red = Math.round(((color >> 16) & 255) * factor);
  const green = Math.round(((color >> 8) & 255) * factor);
  const blue = Math.round((color & 255) * factor);
  return (red << 16) | (green << 8) | blue;
}
