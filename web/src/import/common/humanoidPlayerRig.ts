import { Euler, Matrix4, Quaternion, Vector3 } from "three";
import type { ImportedSkinPart } from "../../domain/conversionSeed";

export type HumanoidPart = ImportedSkinPart["part"];
export type HumanoidSliceMotion = "upper" | "lower";

export interface HumanoidSliceSpec {
  order: number;
  startY: number;
  endY: number;
  motion: HumanoidSliceMotion;
}

export type HumanoidJointSide = "upper" | "lower";

export interface HumanoidRenderPieceSpec extends HumanoidSliceSpec {
  kind: "slice" | "joint_fill";
  jointSide?: HumanoidJointSide;
}

const JOINT_FILL_WIDTH_FACTOR = 0.996;
const UPPER_JOINT_FILL_OFFSET = { y: -0.04875, z: 0.0475 };
const LOWER_JOINT_FILL_OFFSET = { x: 1 / 320, y: 0.015, z: 0.04625 };
const UPPER_JOINT_FILL_SCALE = new Vector3(JOINT_FILL_WIDTH_FACTOR, 1.05, 0.89);
const LOWER_JOINT_FILL_SCALE = new Vector3(JOINT_FILL_WIDTH_FACTOR, 1.075, 0.875);

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

export function humanoidRenderPieces(part: HumanoidPart, jointed: boolean): readonly HumanoidRenderPieceSpec[] {
  const slices = humanoidSkinSlices(part, jointed).map((slice) => ({ ...slice, kind: "slice" as const }));
  if (!jointed || part === "head" || part === "body") return slices;
  return [
    slices[0],
    slices[1],
    { ...slices[1], kind: "joint_fill", jointSide: "upper" },
    { ...slices[2], kind: "joint_fill", jointSide: "lower" },
    slices[2],
    slices[3],
  ];
}

export function humanoidJointFillMatrix(base: Matrix4, part: HumanoidPart, side: HumanoidJointSide): Matrix4 {
  if (part === "head" || part === "body") throw new Error(`${part} does not support limb joint fillers.`);
  const position = new Vector3();
  const rotation = new Quaternion();
  const scale = new Vector3();
  base.decompose(position, rotation, scale);

  const bendDirection = part.endsWith("arm") ? 1 : -1;
  const handedDirection = part.startsWith("right") ? -1 : 1;
  const offset = side === "upper"
    ? new Vector3(0, UPPER_JOINT_FILL_OFFSET.y, bendDirection * UPPER_JOINT_FILL_OFFSET.z)
    : new Vector3(
      handedDirection * LOWER_JOINT_FILL_OFFSET.x,
      LOWER_JOINT_FILL_OFFSET.y,
      -bendDirection * LOWER_JOINT_FILL_OFFSET.z,
    );
  const fillRotation = side === "upper" ? bendDirection * Math.PI / 4 : -bendDirection * Math.PI / 4;
  const scaleFactor = side === "upper" ? UPPER_JOINT_FILL_SCALE : LOWER_JOINT_FILL_SCALE;

  position.add(offset.applyQuaternion(rotation));
  rotation.multiply(new Quaternion().setFromEuler(new Euler(fillRotation, 0, 0, "ZYX")));
  scale.multiply(scaleFactor);
  return new Matrix4().compose(position, rotation, scale);
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
