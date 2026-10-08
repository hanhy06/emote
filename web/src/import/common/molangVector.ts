import type { RuntimeScalar, RuntimeVectorKeyframe } from "../../domain/minecraftData";
import { mapMolangResult } from "../../format/molang/sourceTransformer";

export type MolangVector = [RuntimeScalar, RuntimeScalar, RuntimeScalar];

export function molangScalar(value: string | number): RuntimeScalar {
  if (typeof value === "number") return value;
  const numeric = Number(value.trim());
  return Number.isFinite(numeric) ? numeric : value.trim();
}

export function affineMolang(value: RuntimeScalar, factor: RuntimeScalar, offset: RuntimeScalar): RuntimeScalar {
  if (typeof value === "number" && typeof factor === "number" && typeof offset === "number") return value * factor + offset;
  if (factor === 1 && offset === 0) return value;
  return mapMolangResult(String(value), (result) => {
    const scaled = factor === 1 ? `(${result})` : `((${result}) * ${typeof factor === "number" ? factor : `(${factor})`})`;
    return offset === 0 ? scaled : `(${scaled} + ${typeof offset === "number" ? offset : `(${offset})`})`;
  });
}

export function negateMolang(value: RuntimeScalar): RuntimeScalar {
  return typeof value === "number" ? -value : mapMolangResult(value, (result) => `-(${result})`);
}

export function isolateMolangAxis(
  frames: RuntimeVectorKeyframe[],
  axis: number,
  transform: (value: RuntimeScalar) => RuntimeScalar = (value) => value,
): RuntimeVectorKeyframe[] {
  const isolate = (values: readonly RuntimeScalar[]): MolangVector => values.map((value, index) => index === axis ? transform(value) : 0) as MolangVector;
  return frames.map((frame) => ({ ...frame, ...(frame.value ? { value: isolate(frame.value) } : {}), ...(frame.pre ? { pre: isolate(frame.pre) } : {}), ...(frame.post ? { post: isolate(frame.post) } : {}) }));
}
