import type { EmoteVectorKeyframe, MolangScalar } from "../format/emoteAnimation";

export type MolangVector = [MolangScalar, MolangScalar, MolangScalar];

export function molangScalar(value: string | number): MolangScalar {
  if (typeof value === "number") return value;
  const numeric = Number(value.trim());
  return Number.isFinite(numeric) ? numeric : value.trim();
}

export function affineMolang(value: MolangScalar, factor: number, offset: number): MolangScalar {
  if (typeof value === "number") return value * factor + offset;
  const scaled = factor === 1 ? `(${value})` : `((${value}) * ${factor})`;
  return offset === 0 ? scaled : `(${scaled} + ${offset})`;
}

export function negateMolang(value: MolangScalar): MolangScalar {
  return typeof value === "number" ? -value : `-(${value})`;
}

export function isolateMolangAxis(
  frames: EmoteVectorKeyframe[],
  axis: number,
  transform: (value: MolangScalar) => MolangScalar = (value) => value,
): EmoteVectorKeyframe[] {
  const isolate = (values: readonly MolangScalar[]): MolangVector => values.map((value, index) => index === axis ? transform(value) : 0) as MolangVector;
  return frames.map((frame) => ({ ...frame, ...(frame.value ? { value: isolate(frame.value) } : {}), ...(frame.pre ? { pre: isolate(frame.pre) } : {}), ...(frame.post ? { post: isolate(frame.post) } : {}) }));
}
