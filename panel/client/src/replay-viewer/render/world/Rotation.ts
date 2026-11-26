import type { Vec3 } from './Direction.ts';

export type Mat3 = readonly [number, number, number, number, number, number, number, number, number];

export const IDENTITY: Mat3 = [1, 0, 0, 0, 1, 0, 0, 0, 1];

const DEG = Math.PI / 180;

function snap(value: number): number {
  const rounded = Math.round(value);
  return Math.abs(value - rounded) < 1e-9 ? rounded : value;
}

export function rotationX(degrees: number): Mat3 {
  const c = snap(Math.cos(degrees * DEG));
  const s = snap(Math.sin(degrees * DEG));
  return [1, 0, 0, 0, c, -s, 0, s, c];
}

export function rotationY(degrees: number): Mat3 {
  const c = snap(Math.cos(degrees * DEG));
  const s = snap(Math.sin(degrees * DEG));
  return [c, 0, s, 0, 1, 0, -s, 0, c];
}

export function rotationZ(degrees: number): Mat3 {
  const c = snap(Math.cos(degrees * DEG));
  const s = snap(Math.sin(degrees * DEG));
  return [c, -s, 0, s, c, 0, 0, 0, 1];
}

export function multiply(a: Mat3, b: Mat3): Mat3 {
  return [
    a[0] * b[0] + a[1] * b[3] + a[2] * b[6],
    a[0] * b[1] + a[1] * b[4] + a[2] * b[7],
    a[0] * b[2] + a[1] * b[5] + a[2] * b[8],
    a[3] * b[0] + a[4] * b[3] + a[5] * b[6],
    a[3] * b[1] + a[4] * b[4] + a[5] * b[7],
    a[3] * b[2] + a[4] * b[5] + a[5] * b[8],
    a[6] * b[0] + a[7] * b[3] + a[8] * b[6],
    a[6] * b[1] + a[7] * b[4] + a[8] * b[7],
    a[6] * b[2] + a[7] * b[5] + a[8] * b[8],
  ];
}

export function apply(matrix: Mat3, vector: Vec3): [number, number, number] {
  return [
    matrix[0] * vector[0] + matrix[1] * vector[1] + matrix[2] * vector[2],
    matrix[3] * vector[0] + matrix[4] * vector[1] + matrix[5] * vector[2],
    matrix[6] * vector[0] + matrix[7] * vector[1] + matrix[8] * vector[2],
  ];
}

export function variantRotation(x: number, y: number): Mat3 {
  return multiply(rotationY(-y), rotationX(-x));
}
