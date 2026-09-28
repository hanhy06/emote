import type { RuntimeScalar, RuntimeVectorKeyframe } from "../../domain/minecraftData";

export type MolangVector = [RuntimeScalar, RuntimeScalar, RuntimeScalar];

export function molangScalar(value: string | number): RuntimeScalar {
  if (typeof value === "number") return value;
  const numeric = Number(value.trim());
  return Number.isFinite(numeric) ? numeric : value.trim();
}

export function affineMolang(value: RuntimeScalar, factor: number, offset: number): RuntimeScalar {
  if (typeof value === "number") return value * factor + offset;
  if (factor === 1 && offset === 0) return value;
  const scaled = factor === 1 ? `(${value})` : `((${value}) * ${factor})`;
  return offset === 0 ? scaled : `(${scaled} + ${offset})`;
}

export function negateMolang(value: RuntimeScalar): RuntimeScalar {
  return typeof value === "number" ? -value : `-(${value})`;
}

export function isolateMolangAxis(
  frames: RuntimeVectorKeyframe[],
  axis: number,
  transform: (value: RuntimeScalar) => RuntimeScalar = (value) => value,
): RuntimeVectorKeyframe[] {
  const isolate = (values: readonly RuntimeScalar[]): MolangVector => values.map((value, index) => index === axis ? transform(value) : 0) as MolangVector;
  return frames.map((frame) => ({ ...frame, ...(frame.value ? { value: isolate(frame.value) } : {}), ...(frame.pre ? { pre: isolate(frame.pre) } : {}), ...(frame.post ? { post: isolate(frame.post) } : {}) }));
}
