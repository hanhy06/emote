import type { Matrix4 } from "three";
import type { Matrix16 } from "./emoteAnimation";

export const IDENTITY_MATRIX: Matrix16 = [
  1, 0, 0, 0,
  0, 1, 0, 0,
  0, 0, 1, 0,
  0, 0, 0, 1,
];

export function asMatrix16(values: readonly number[], label: string): Matrix16 {
  if (values.length !== 16 || values.some((value) => !Number.isFinite(value))) {
    throw new Error(`${label} must contain 16 finite numbers.`);
  }
  return values as Matrix16;
}

export function matrix4ToRowMajor(matrix: Matrix4, label: string): Matrix16 {
  const values = matrix.elements;
  return asMatrix16([
    values[0], values[4], values[8], values[12],
    values[1], values[5], values[9], values[13],
    values[2], values[6], values[10], values[14],
    values[3], values[7], values[11], values[15],
  ], label);
}
