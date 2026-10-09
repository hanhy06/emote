import { mapMolangResult } from "../../format/molang/sourceTransformer";

export type MolangScalar = number | string;
export type MolangVector = [MolangScalar, MolangScalar, MolangScalar];

export function molangScalar(value: string | number): MolangScalar {
  if (typeof value === "number") return value;
  const numeric = Number(value.trim());
  return Number.isFinite(numeric) ? numeric : value.trim();
}

export function affineMolang(value: MolangScalar, factor: MolangScalar, offset: MolangScalar): MolangScalar {
  if (typeof value === "number" && typeof factor === "number" && typeof offset === "number") return value * factor + offset;
  if (factor === 1 && offset === 0) return value;
  return mapMolangResult(String(value), (result) => {
    const scaled = factor === 1 ? `(${result})` : `((${result}) * ${typeof factor === "number" ? factor : `(${factor})`})`;
    return offset === 0 ? scaled : `(${scaled} + ${typeof offset === "number" ? offset : `(${offset})`})`;
  });
}

export function negateMolang(value: MolangScalar): MolangScalar {
  return typeof value === "number" ? -value : mapMolangResult(value, (result) => `-(${result})`);
}
