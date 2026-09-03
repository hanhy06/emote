import { Matrix4, Vector3 } from "three";
import { matrix4ToRowMajor } from "../../format/matrix";
import type { ImportedNode, ImportedSkinPart } from "../../domain/conversionSeed";
import { bedrockBoundsToCanonical } from "../coordinateSpace";
import { humanoidSkinPartHeight, humanoidSkinSlices } from "../humanoid/humanoidPlayerRig";

export const BEDROCK_PLAYER_RENDER_SCALE = 0.9375;

export interface BedrockPlayerBone {
  id: string;
  sourceName: string;
  pivot: readonly [number, number, number];
  parent?: string;
  cube?: {
    from: readonly [number, number, number];
    to: readonly [number, number, number];
    skin: ImportedSkinPart["part"];
  };
}

export const BEDROCK_PLAYER_BONES: readonly BedrockPlayerBone[] = [
  { id: "root", sourceName: "root", pivot: [0, 0, 0] },
  { id: "waist", sourceName: "waist", pivot: [0, 12, 0], parent: "root" },
  { id: "body", sourceName: "body", pivot: [0, 24, 0], parent: "waist", cube: { from: [-4, 12, -2], to: [4, 24, 2], skin: "body" } },
  { id: "head", sourceName: "head", pivot: [0, 24, 0], parent: "body", cube: { from: [-4, 24, -4], to: [4, 32, 4], skin: "head" } },
  { id: "left_arm", sourceName: "leftArm", pivot: [5, 22, 0], parent: "body", cube: { from: [4, 12, -2], to: [8, 24, 2], skin: "left_arm" } },
  { id: "right_arm", sourceName: "rightArm", pivot: [-5, 22, 0], parent: "body", cube: { from: [-8, 12, -2], to: [-4, 24, 2], skin: "right_arm" } },
  { id: "left_leg", sourceName: "leftLeg", pivot: [1.9, 12, 0], parent: "root", cube: { from: [-0.1, 0, -2], to: [3.9, 12, 2], skin: "left_leg" } },
  { id: "right_leg", sourceName: "rightLeg", pivot: [-1.9, 12, 0], parent: "root", cube: { from: [-3.9, 0, -2], to: [0.1, 12, 2], skin: "right_leg" } },
];

const BONES_BY_ID = new Map(BEDROCK_PLAYER_BONES.map((bone) => [bone.id, bone]));
const BONES_BY_NORMALIZED_NAME = new Map(BEDROCK_PLAYER_BONES.map((bone) => [normalizeBedrockBoneName(bone.sourceName), bone]));
const HIDDEN_ACCESSORY_BONES = new Set(["leftitem", "rightitem", "cape"]);

export interface BedrockPlayerSlice {
  id: string;
  bone: BedrockPlayerBone & { cube: NonNullable<BedrockPlayerBone["cube"]> };
  order: number;
  from: readonly [number, number, number];
  to: readonly [number, number, number];
}

export const BEDROCK_PLAYER_SLICES: readonly BedrockPlayerSlice[] = BEDROCK_PLAYER_BONES.flatMap((bone) => {
  if (!bone.cube) return [];
  const height = bone.cube.to[1] - bone.cube.from[1];
  const skinHeight = humanoidSkinPartHeight(bone.cube.skin);
  return humanoidSkinSlices(bone.cube.skin, false).map((slice) => ({
    id: bone.cube!.skin === "head" ? bone.id : `${bone.id}_${slice.order}`,
    bone: bone as BedrockPlayerSlice["bone"],
    order: slice.order,
    from: [bone.cube!.from[0], bone.cube!.to[1] - height * slice.endY / skinHeight, bone.cube!.from[2]] as const,
    to: [bone.cube!.to[0], bone.cube!.to[1] - height * slice.startY / skinHeight, bone.cube!.to[2]] as const,
  }));
});

export function bedrockPlayerBoneById(id: string): BedrockPlayerBone {
  const bone = BONES_BY_ID.get(id);
  if (!bone) throw new Error(`Unknown Bedrock player bone ${id}.`);
  return bone;
}

export function resolveBedrockPlayerBone(name: string): BedrockPlayerBone | undefined {
  return BONES_BY_NORMALIZED_NAME.get(normalizeBedrockBoneName(name));
}

export function isHiddenBedrockAccessoryBone(name: string): boolean {
  return HIDDEN_ACCESSORY_BONES.has(normalizeBedrockBoneName(name));
}

export function createBedrockPlayerNodes(worldMatrices: ReadonlyMap<string, Matrix4>): Record<string, ImportedNode> {
  const nodes: Record<string, ImportedNode> = {};
  for (const slice of BEDROCK_PLAYER_SLICES) {
    const world = worldMatrices.get(slice.bone.id);
    if (!world) throw new Error(`Missing bind matrix for Bedrock player bone ${slice.bone.id}.`);
    nodes[slice.id] = {
      id: slice.id,
      type: "item_display",
      defaultMatrix: matrix4ToRowMajor(world, `Bedrock player slice ${slice.id}`),
      visible: true,
      itemDisplay: "none",
      itemStack: { id: "minecraft:player_head", count: 1 },
      playerHeadConversion: { matrix: bedrockPlayerHeadConversionMatrix(slice.bone, slice.from, slice.to) },
      suggestedSkin: { part: slice.bone.cube.skin, order: slice.order },
    };
  }
  return nodes;
}

export function bedrockPlayerHeadConversionMatrix(
  bone: BedrockPlayerBone,
  boundsFrom: readonly number[] = bone.cube?.from ?? [],
  boundsTo: readonly number[] = bone.cube?.to ?? [],
) {
  if (!bone.cube) throw new Error(`Bedrock player bone ${bone.id} does not have geometry.`);
  const sourceFrom = boundsFrom.map((value, axis) => value - bone.pivot[axis]);
  const sourceTo = boundsTo.map((value, axis) => value - bone.pivot[axis]);
  const canonical = bedrockBoundsToCanonical(sourceFrom, sourceTo);
  const from = canonical.from.map((value) => value / 16);
  const to = canonical.to.map((value) => value / 16);
  const size = to.map((value, axis) => value - from[axis]);
  const center = from.map((value, axis) => (value + to[axis]) / 2);
  const fit = new Matrix4()
    .makeTranslation(center[0], center[1], center[2])
    .scale(new Vector3(size[0] * 2, size[1] * 2, size[2] * 2))
    .multiply(new Matrix4().makeTranslation(0, 0.25, 0));
  return matrix4ToRowMajor(fit, `Bedrock player bone ${bone.id} player head conversion`);
}

function normalizeBedrockBoneName(name: string): string {
  return name.toLowerCase().replaceAll(/[_\-\s]/g, "");
}
