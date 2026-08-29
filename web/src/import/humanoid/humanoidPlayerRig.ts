import type { ImportedSkinPart } from "../../domain/conversionSeed";

export type HumanoidPart = ImportedSkinPart["part"];
export type HumanoidSliceMotion = "upper" | "lower";

export interface HumanoidSliceSpec {
  order: number;
  startY: number;
  endY: number;
  motion: HumanoidSliceMotion;
}

const HEAD: readonly HumanoidSliceSpec[] = [
  { order: 0, startY: 0, endY: 8, motion: "upper" },
];

const RIGID: readonly HumanoidSliceSpec[] = [
  { order: 0, startY: 0, endY: 4, motion: "upper" },
  { order: 1, startY: 4, endY: 12, motion: "upper" },
];

const BENT_BODY: readonly HumanoidSliceSpec[] = [
  { order: 0, startY: 0, endY: 4, motion: "upper" },
  { order: 1, startY: 4, endY: 12, motion: "lower" },
];

const BENT_LIMB: readonly HumanoidSliceSpec[] = [
  { order: 0, startY: 0, endY: 4, motion: "upper" },
  { order: 1, startY: 4, endY: 6, motion: "upper" },
  { order: 2, startY: 6, endY: 8, motion: "lower" },
  { order: 3, startY: 8, endY: 12, motion: "lower" },
];

export function humanoidSkinSlices(part: HumanoidPart, jointed: boolean): readonly HumanoidSliceSpec[] {
  if (part === "head") return HEAD;
  if (!jointed) return RIGID;
  return part === "body" ? BENT_BODY : BENT_LIMB;
}

