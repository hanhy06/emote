import { Matrix4, Vector3 } from "three";
import type { ImportedNode, ImportedSkinPart } from "../../domain/conversionSeed";
import { matrix4ToRowMajor } from "../../format/matrix";
import { humanoidJointFillMatrix, humanoidRenderPieces, humanoidSkinPartHeight, type HumanoidJointSide } from "../common/humanoidPlayerRig";

export const EMOTECRAFT_RENDER_SCALE = 0.9375;

export interface EmotecraftPlayerPart {
  bone: string;
  part: ImportedSkinPart["part"];
  pivot: readonly [number, number, number];
  bounds: { from: readonly [number, number, number]; to: readonly [number, number, number] };
}

export interface EmotecraftSlice {
  id: string;
  source: EmotecraftPlayerPart;
  order: number;
  lower: boolean;
  jointSide?: HumanoidJointSide;
  bounds: EmotecraftPlayerPart["bounds"];
}

export const EMOTECRAFT_PLAYER_PARTS: readonly EmotecraftPlayerPart[] = [
  { bone: "head", part: "head", pivot: [0, 24, 0], bounds: { from: [-4, 24, -4], to: [4, 32, 4] } },
  { bone: "torso", part: "body", pivot: [0, 24, 0], bounds: { from: [-4, 12, -2], to: [4, 24, 2] } },
  { bone: "right_arm", part: "right_arm", pivot: [5, 22, 0], bounds: { from: [4, 12, -2], to: [8, 24, 2] } },
  { bone: "left_arm", part: "left_arm", pivot: [-5, 22, 0], bounds: { from: [-8, 12, -2], to: [-4, 24, 2] } },
  { bone: "left_leg", part: "left_leg", pivot: [-2, 12, 0], bounds: { from: [-4, 0, -2], to: [0, 12, 2] } },
  { bone: "right_leg", part: "right_leg", pivot: [2, 12, 0], bounds: { from: [0, 0, -2], to: [4, 12, 2] } },
];

export const EMOTECRAFT_PIVOTS: Readonly<Record<string, readonly [number, number, number]>> = {
  body: [0, 12, 0],
  ...Object.fromEntries(EMOTECRAFT_PLAYER_PARTS.map((part) => [part.bone, part.pivot])),
  right_item: [6, 12, -2], left_item: [-6, 12, -2], cape: [0, 24, 2], elytra: [0, 24, 2],
};

export function createEmotecraftSlices(bentBones: ReadonlySet<string>): EmotecraftSlice[] {
  const slices: EmotecraftSlice[] = [];
  for (const source of EMOTECRAFT_PLAYER_PARTS) {
    const { from, to } = source.bounds;
    const height = to[1] - from[1];
    const skinHeight = humanoidSkinPartHeight(source.part);
    for (const piece of humanoidRenderPieces(source.part, bentBones.has(source.bone))) {
      const sliceFromY = to[1] - height * piece.endY / skinHeight;
      const sliceToY = to[1] - height * piece.startY / skinHeight;
      const id = source.part === "head"
        ? source.part
        : piece.kind === "joint_fill" ? `${source.part}_joint_${piece.jointSide}` : `${source.part}_${piece.order}`;
      slices.push({
        id,
        source,
        order: piece.order,
        lower: piece.motion === "lower",
        ...(piece.jointSide ? { jointSide: piece.jointSide } : {}),
        bounds: { from: [from[0], sliceFromY, from[2]], to: [to[0], sliceToY, to[2]] },
      });
    }
  }
  return slices;
}

export function createEmotecraftNodes(slices: readonly EmotecraftSlice[], matrices: ReadonlyMap<string, Matrix4>): Record<string, ImportedNode> {
  return Object.fromEntries(slices.map((slice) => {
    const matrix = matrices.get(slice.id);
    if (!matrix) throw new Error(`Missing Emotecraft bind matrix for ${slice.id}.`);
    const group = `${slice.source.part}_${slice.order}`;
    return [slice.id, {
      id: slice.id,
      type: "item_display",
      defaultMatrix: matrix4ToRowMajor(matrix, `Emotecraft ${slice.id} bind matrix`),
      visible: true,
      itemDisplay: "none",
      itemStack: { id: "minecraft:player_head", count: 1 },
      playerHeadConversion: { matrix: slicePlayerHeadConversion(slice) },
      skinAssignmentGroup: group,
      suggestedSkin: { part: slice.source.part, order: slice.order },
      space: "initiator",
    } satisfies ImportedNode];
  }));
}

function slicePlayerHeadConversion(slice: EmotecraftSlice) {
  const geometryPivot = slice.lower
    ? [slice.source.pivot[0], slice.source.pivot[1] - 6, slice.source.pivot[2]]
    : slice.source.pivot;
  const sourceFrom = slice.bounds.from.map((value, axis) => value - geometryPivot[axis]);
  const sourceTo = slice.bounds.to.map((value, axis) => value - geometryPivot[axis]);
  const from = sourceFrom.map((value) => value / 16);
  const to = sourceTo.map((value) => value / 16);
  const size = to.map((value, axis) => value - from[axis]);
  const center = from.map((value, axis) => (value + to[axis]) / 2);
  const fit = new Matrix4().makeTranslation(center[0], center[1], center[2])
    .scale(new Vector3(size[0] * 2, size[1] * 2, size[2] * 2))
    .multiply(new Matrix4().makeTranslation(0, 0.25, 0));
  const conversion = slice.jointSide ? humanoidJointFillMatrix(fit, slice.source.part, slice.jointSide) : fit;
  return matrix4ToRowMajor(conversion, `Emotecraft ${slice.id} player head conversion`);
}
