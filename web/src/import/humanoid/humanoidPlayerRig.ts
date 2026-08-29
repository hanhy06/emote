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

export function humanoidSkinPartHeight(part: HumanoidPart): number {
  return part === "head" ? 8 : 12;
}

export function inferHumanoidPart(name: string): HumanoidPart | undefined {
  const normalized = name.toLowerCase().replaceAll(/[^a-z0-9]/g, "");
  if (normalized.includes("left") && (normalized.includes("arm") || normalized.includes("hand") || normalized.includes("wing"))) return "left_arm";
  if (normalized.includes("right") && (normalized.includes("arm") || normalized.includes("hand") || normalized.includes("wing"))) return "right_arm";
  if (normalized.includes("left") && (normalized.includes("leg") || normalized.includes("foot"))) return "left_leg";
  if (normalized.includes("right") && (normalized.includes("leg") || normalized.includes("foot"))) return "right_leg";
  if (normalized.includes("head") || normalized.includes("face") || normalized.includes("skull")) return "head";
  if (normalized.includes("body") || normalized.includes("torso") || normalized.includes("chest") || normalized.includes("waist")) return "body";
  return undefined;
}

export function isStandardHumanoidPartSize(part: HumanoidPart, size: readonly number[]): boolean {
  const closeTo = (value: number, expected: number) => Math.abs(value - expected) <= 1e-3;
  if (part === "head") return closeTo(size[0], 8) && closeTo(size[1], 8) && closeTo(size[2], 8);
  if (part === "body") return closeTo(size[0], 8) && closeTo(size[1], 12) && closeTo(size[2], 4);
  return (closeTo(size[0], 3) || closeTo(size[0], 4)) && closeTo(size[1], 12) && closeTo(size[2], 4);
}

export function sliceVerticalUv(
  uv: readonly number[],
  rotation: number,
  startRatio: number,
  endRatio: number,
  quarterTurnTop: "low" | "high",
): number[] {
  const [minU, minV, maxU, maxV] = uv;
  const normalizedRotation = ((rotation % 360) + 360) % 360;
  const quarterTurn = normalizedRotation === 90 || normalizedRotation === 270;
  const reverse = normalizedRotation === 180 || (quarterTurn && (normalizedRotation === 90) === (quarterTurnTop === "high"));
  if (quarterTurn) {
    return reverse
      ? [maxU - (maxU - minU) * endRatio, minV, maxU - (maxU - minU) * startRatio, maxV]
      : [minU + (maxU - minU) * startRatio, minV, minU + (maxU - minU) * endRatio, maxV];
  }
  return reverse
    ? [minU, maxV - (maxV - minV) * endRatio, maxU, maxV - (maxV - minV) * startRatio]
    : [minU, minV + (maxV - minV) * startRatio, maxU, minV + (maxV - minV) * endRatio];
}
