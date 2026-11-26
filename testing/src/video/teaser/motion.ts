import type { Pose } from "./edit";

export function clamp(value: number, low = 0, high = 1): number {
  return Math.min(high, Math.max(low, value));
}

export function lerp(from: number, to: number, progress: number): number {
  return from + (to - from) * progress;
}

export function easeInOut(progress: number): number {
  const p = clamp(progress);
  return p < 0.5 ? 4 * p * p * p : 1 - (-2 * p + 2) ** 3 / 2;
}

export function easeOut(progress: number): number {
  return 1 - (1 - clamp(progress)) ** 3;
}

export function easeOutBack(progress: number): number {
  const p = clamp(progress);
  const overshoot = 1.4;
  return 1 + (overshoot + 1) * (p - 1) ** 3 + overshoot * (p - 1) ** 2;
}

export function window01(time: number, start: number, seconds: number): number {
  if (seconds <= 0) return time >= start ? 1 : 0;
  return clamp((time - start) / seconds);
}

export function posePoint(from: Pose, to: Pose, progress: number): Pose {
  const p = easeInOut(progress);
  return { rotateX: lerp(from.rotateX, to.rotateX, p), rotateY: lerp(from.rotateY, to.rotateY, p), scale: lerp(from.scale, to.scale, p) };
}

export function poseTransform(pose: Pose): string {
  return `rotateX(${pose.rotateX.toFixed(3)}deg) rotateY(${pose.rotateY.toFixed(3)}deg) scale(${pose.scale.toFixed(4)})`;
}

export function px(value: number): string {
  return `${value.toFixed(2)}px`;
}

export function fixed(value: number, digits = 3): string {
  return value.toFixed(digits);
}
