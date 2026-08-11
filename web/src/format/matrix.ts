import { Matrix4 } from "three";
import type { Matrix16 } from "./emoteAnimation";

const SHEAR_EPSILON = 1e-6;
const SINGULAR_EPSILON = 1e-12;
const POLAR_CONVERGENCE_EPSILON = 1e-10;
const MAX_POLAR_ITERATIONS = 16;

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

export function multiplyMatrix16(left: Matrix16, right: Matrix16, label: string): Matrix16 {
  return matrix4ToRowMajor(new Matrix4().set(...left).multiply(new Matrix4().set(...right)), label);
}

export function stabilizeDisplayMatrix(values: readonly number[], label: string): Matrix16 {
  const matrix = asMatrix16(values, label);
  const linear = [
    matrix[0], matrix[1], matrix[2],
    matrix[4], matrix[5], matrix[6],
    matrix[8], matrix[9], matrix[10],
  ];
  if (maximumColumnShear(linear) <= SHEAR_EPSILON) return [...matrix] as Matrix16;

  const rotation = polarRotation(linear);
  if (!rotation) return [...matrix] as Matrix16;

  const scales = [
    rotation[0] * linear[0] + rotation[3] * linear[3] + rotation[6] * linear[6],
    rotation[1] * linear[1] + rotation[4] * linear[4] + rotation[7] * linear[7],
    rotation[2] * linear[2] + rotation[5] * linear[5] + rotation[8] * linear[8],
  ];
  if (determinant3(rotation) < 0) {
    const axis = smallestAbsoluteIndex(scales);
    for (let row = 0; row < 3; row++) rotation[row * 3 + axis] *= -1;
    scales[axis] *= -1;
  }

  return asMatrix16([
    rotation[0] * scales[0], rotation[1] * scales[1], rotation[2] * scales[2], matrix[3],
    rotation[3] * scales[0], rotation[4] * scales[1], rotation[5] * scales[2], matrix[7],
    rotation[6] * scales[0], rotation[7] * scales[1], rotation[8] * scales[2], matrix[11],
    matrix[12], matrix[13], matrix[14], matrix[15],
  ], label);
}

function polarRotation(linear: number[]): number[] | null {
  let current = [...linear];
  for (let iteration = 0; iteration < MAX_POLAR_ITERATIONS; iteration++) {
    const inverseTranspose = inverseTranspose3(current);
    if (!inverseTranspose) return null;
    const next = current.map((value, index) => (value + inverseTranspose[index]) * 0.5);
    const difference = Math.max(...next.map((value, index) => Math.abs(value - current[index])));
    current = next;
    if (difference <= POLAR_CONVERGENCE_EPSILON) break;
  }
  return current;
}

function inverseTranspose3(matrix: number[]): number[] | null {
  const [a, b, c, d, e, f, g, h, i] = matrix;
  const determinant = determinant3(matrix);
  if (Math.abs(determinant) <= SINGULAR_EPSILON) return null;
  return [
    (e * i - f * h) / determinant,
    (f * g - d * i) / determinant,
    (d * h - e * g) / determinant,
    (c * h - b * i) / determinant,
    (a * i - c * g) / determinant,
    (b * g - a * h) / determinant,
    (b * f - c * e) / determinant,
    (c * d - a * f) / determinant,
    (a * e - b * d) / determinant,
  ];
}

function determinant3(matrix: number[]): number {
  const [a, b, c, d, e, f, g, h, i] = matrix;
  return a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g);
}

function maximumColumnShear(matrix: number[]): number {
  const columns = [
    [matrix[0], matrix[3], matrix[6]],
    [matrix[1], matrix[4], matrix[7]],
    [matrix[2], matrix[5], matrix[8]],
  ];
  const lengths = columns.map(([x, y, z]) => Math.hypot(x, y, z));
  if (lengths.some((length) => length <= SINGULAR_EPSILON)) return 0;

  let maximum = 0;
  for (let first = 0; first < 3; first++) {
    for (let second = first + 1; second < 3; second++) {
      const dot = columns[first][0] * columns[second][0]
        + columns[first][1] * columns[second][1]
        + columns[first][2] * columns[second][2];
      maximum = Math.max(maximum, Math.abs(dot / (lengths[first] * lengths[second])));
    }
  }
  return maximum;
}

function smallestAbsoluteIndex(values: number[]): number {
  let result = 0;
  for (let index = 1; index < values.length; index++) {
    if (Math.abs(values[index]) < Math.abs(values[result])) result = index;
  }
  return result;
}
