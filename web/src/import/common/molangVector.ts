export type MolangScalar = number | string;
export type MolangVector = [MolangScalar, MolangScalar, MolangScalar];

export function molangScalar(value: string | number): MolangScalar {
  if (typeof value !== "string") return value;
  const numeric = Number(value.trim());
  return Number.isFinite(numeric) ? numeric : value.trim();
}
