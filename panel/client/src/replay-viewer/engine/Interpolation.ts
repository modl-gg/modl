/**
 * Linearly interpolate between two values.
 */
export function lerp(a: number, b: number, t: number): number {
  return a + (b - a) * t;
}

/**
 * Decode a raw int16 yaw/pitch value to degrees.
 * Encoding: value = (short)(angle / 360.0 * 32767.0)
 * Decoding: angle = value * 360.0 / 32767.0
 */
export function decodeAngle(raw: number): number {
  return (raw * 360.0) / 32767.0;
}

/**
 * Interpolate an angle (in degrees) with wrap-around handling.
 * Ensures shortest-path interpolation (e.g., 350 to 10 goes through 0, not 180).
 */
export function lerpAngle(a: number, b: number, t: number): number {
  let diff = b - a;
  // Normalize to [-180, 180]
  while (diff > 180) diff -= 360;
  while (diff < -180) diff += 360;
  return a + diff * t;
}

/**
 * Given two timestamped positions, compute the interpolated position at time T.
 */
export function interpolatePosition(
  before: { x: number; y: number; z: number; timestampDeltaMs: number },
  after: { x: number; y: number; z: number; timestampDeltaMs: number },
  timeMs: number,
): { x: number; y: number; z: number } {
  const range = after.timestampDeltaMs - before.timestampDeltaMs;
  if (range <= 0) return { x: before.x, y: before.y, z: before.z };

  const t = Math.max(0, Math.min(1, (timeMs - before.timestampDeltaMs) / range));
  return {
    x: lerp(before.x, after.x, t),
    y: lerp(before.y, after.y, t),
    z: lerp(before.z, after.z, t),
  };
}

/**
 * Binary search for the last event index where timestampDeltaMs <= targetTime.
 * Returns -1 if no event is at or before targetTime.
 */
export function binarySearchTime(
  events: readonly { timestampDeltaMs: number }[],
  targetTime: number,
): number {
  let lo = 0;
  let hi = events.length - 1;
  let result = -1;

  while (lo <= hi) {
    const mid = (lo + hi) >>> 1;
    const midTime = events[mid]?.timestampDeltaMs ?? Number.POSITIVE_INFINITY;
    if (midTime <= targetTime) {
      result = mid;
      lo = mid + 1;
    } else {
      hi = mid - 1;
    }
  }

  return result;
}
