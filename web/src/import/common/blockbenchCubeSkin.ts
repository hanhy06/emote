import { Matrix4, Vector3 } from "three";
import type { Matrix16 } from "../../domain/matrix";
import { matrix4ToRowMajor } from "../../format/matrix";
import type { ImportedSkinPart } from "../../domain/conversionSeed";
import type { PlayerSkinPart } from "../../domain/player";
import type { BbCube } from "./blockbenchCubeSchema";
import type { BoneEntry } from "./blockbenchCubeModel";
import type { ConversionIssue } from "../../foundation/diagnostics";
import {
  humanoidJointFillMatrix,
  humanoidRenderPieces,
  humanoidSkinPartHeight,
  inferHumanoidPart,
  isStandardHumanoidPartSize,
  sliceVerticalUv,
} from "./humanoidPlayerRig";

const HIDDEN_ACCESSORY_BONES = new Set(["leftitem", "rightitem", "cape"]);
const SPLIT_SKIN_CUBE_PATTERN = /_skin_(upper|lower|(\d+)|joint_(upper|lower)_(\d+))$/;

interface SplitSkinCube {
  order: number;
  jointSide?: "upper" | "lower";
}

interface SkinCubeSource {
  bone: BoneEntry;
  cube: BbCube;
  part: PlayerSkinPart;
}

export interface PreparedCubeModels {
  playableCubesByBone: ReadonlyMap<string, BbCube[]>;
  skinAssignments: ReadonlyMap<BbCube, ImportedSkinPart>;
  sourceIds: ReadonlyMap<BbCube, string>;
  diagnostics: ConversionIssue[];
}

export function prepareCubeModels(bones: BoneEntry[]): PreparedCubeModels {
  const sourceIds = new Map(bones.flatMap((bone) => bone.cubes.map((cube) => [cube, cube.uuid] as const)));
  const diagnostics: ConversionIssue[] = [];
  const playableCubesByBone = splitPlayerSkinCubesByPoseBone(bones, sourceIds, diagnostics);
  return { playableCubesByBone, skinAssignments: inferSkinAssignments(bones, playableCubesByBone, sourceIds), sourceIds, diagnostics };
}

export function normalizeBlockbenchName(name: string | undefined): string | undefined {
  const normalized = name?.toLowerCase().replaceAll(/[^a-z0-9]/g, "");
  return normalized || undefined;
}

export function isHiddenAccessoryName(name: string | undefined): boolean {
  const normalized = normalizeBlockbenchName(name);
  return normalized !== undefined && HIDDEN_ACCESSORY_BONES.has(normalized);
}

export function isHiddenAccessoryBone(bone: BoneEntry): boolean {
  for (let current: BoneEntry | undefined = bone; current; current = current.parent) {
    if (isHiddenAccessoryName(current.group.name)) return true;
  }
  return false;
}

export function cubePlayerHeadMatrix(cube: BbCube, bone: BoneEntry, canonical: { from: readonly number[]; to: readonly number[] }): Matrix16 | undefined {
  const from = canonical.from.map((value) => value / 16);
  const to = canonical.to.map((value) => value / 16);
  const size = to.map((value, axis) => value - from[axis]);
  if (size.some((value) => value < 0)) return undefined;

  const center = from.map((value, axis) => (value + to[axis]) / 2);
  const fit = new Matrix4()
    .makeTranslation(center[0], center[1], center[2])
    .scale(new Vector3(size[0] * 2, size[1] * 2, size[2] * 2))
    .multiply(new Matrix4().makeTranslation(0, 0.25, 0));
  const jointSide = parseSplitSkinCube(cube.uuid)?.jointSide;
  const part = jointSide ? inferSkinPart(bone) : undefined;
  const conversion = jointSide && part ? humanoidJointFillMatrix(fit, part, jointSide) : fit;
  return matrix4ToRowMajor(conversion, `Blockbench cube ${cube.name ?? cube.uuid} player head conversion`);
}

function removeDuplicateSkinLayers(bone: BoneEntry): BbCube[] {
  const part = inferSkinPart(bone);
  if (!part) return bone.cubes;
  const offsets = part === "head" ? [[32, 0]] : part === "left_arm" ? [[0, 16], [16, 0]] : part === "left_leg" ? [[0, 16], [-16, 0]] : [[0, 16]];
  return bone.cubes.filter((layer) => !isSkinLayerCube(layer) || !bone.cubes.some((base) => {
    if (base === layer || isSkinLayerCube(base)) return false;
    const size = base.to.map((value, axis) => value - base.from[axis]);
    if (size[1] <= 0 || size[1] > humanoidSkinPartHeight(part)
      || !isStandardHumanoidPartSize(part, [size[0], humanoidSkinPartHeight(part), size[2]])) return false;
    if (!sameCubeBounds(layer, base) && !nearbySkinLayerBounds(layer, base)) return false;
    if (![0, 1, 2].every((axis) => Math.abs((layer.origin ?? bone.group.origin)[axis] - (base.origin ?? bone.group.origin)[axis]) <= 1e-7
      && Math.abs((layer.rotation ?? [0, 0, 0])[axis] - (base.rotation ?? [0, 0, 0])[axis]) <= 1e-7)) return false;
    const faces = Object.entries(layer.faces).filter(([, face]) => face.enabled !== false && face.texture !== null && face.uv?.length === 4);
    return faces.length > 0 && offsets.some(([u, v]) => faces.every(([direction, face]) => {
      const other = base.faces[direction];
      return other?.enabled !== false && other?.uv?.length === 4 && face.texture === other.texture
        && (face.rotation ?? 0) === (other.rotation ?? 0)
        && face.uv!.every((value, index) => Math.abs(value - other.uv![index] - (index % 2 === 0 ? u : v)) <= 1e-3);
    }));
  }));
}

function splitPlayerSkinCubesByPoseBone(bones: BoneEntry[], sourceIds: Map<BbCube, string>, diagnostics: ConversionIssue[]): Map<string, BbCube[]> {
  const result = new Map(bones.map((bone) => [bone.uuid, [] as BbCube[]]));
  const cubesByBone = new Map(bones.map((bone) => [bone.uuid, removeDuplicateSkinLayers(bone)]));
  const consumed = splitPresegmentedSkinCubes(bones, cubesByBone, result, sourceIds, diagnostics);
  for (const bone of bones) {
    const part = inferSkinPart(bone);
    for (const cube of cubesByBone.get(bone.uuid) ?? []) {
      if (consumed.has(cube.uuid)) continue;
      if (!part || isSkinLayerCube(cube) || !isStandardPlayerSkinCube(cube, part)) {
        result.get(bone.uuid)!.push(cube);
        continue;
      }
      const lowerBone = findLowerJointBone(bone, cube, part, bones);
      const skinHeight = humanoidSkinPartHeight(part);
      const pieces = humanoidRenderPieces(part, lowerBone !== undefined);
      for (const piece of pieces) {
        const height = cube.to[1] - cube.from[1];
        const suffix = pieces.length === 2
          ? (piece.order === 0 ? "upper" : "lower")
          : piece.kind === "joint_fill" ? `joint_${piece.jointSide}_${piece.order}` : `${piece.order}`;
        const sliced: BbCube = {
          ...cube,
          uuid: `${cube.uuid}_skin_${suffix}`,
          name: `${cube.name ?? "Cube"} ${suffix === "upper" ? "Upper" : suffix === "lower" ? "Lower" : `Skin ${suffix}`}`,
          from: [cube.from[0], cube.to[1] - height * piece.endY / skinHeight, cube.from[2]],
          to: [cube.to[0], cube.to[1] - height * piece.startY / skinHeight, cube.to[2]],
          faces: sliceVerticalFaceUvs(cube.faces, piece.startY / skinHeight, piece.endY / skinHeight),
        };
        sourceIds.set(sliced, cube.uuid);
        result.get(piece.motion === "lower" && lowerBone ? lowerBone.uuid : bone.uuid)!.push(sliced);
      }
    }
  }
  return result;
}

function splitPresegmentedSkinCubes(
  bones: BoneEntry[],
  cubesByBone: ReadonlyMap<string, BbCube[]>,
  result: Map<string, BbCube[]>,
  sourceIds: Map<BbCube, string>,
  diagnostics: ConversionIssue[],
): Set<string> {
  const consumed = new Set<string>();
  const sources: SkinCubeSource[] = [];
  for (const bone of bones) {
    const part = inferSkinPart(bone);
    if (!part || part === "head") continue;
    for (const cube of cubesByBone.get(bone.uuid) ?? []) {
      const height = cube.to[1] - cube.from[1];
      if (!isSkinLayerCube(cube) && height > 0 && height < humanoidSkinPartHeight(part)) sources.push({ bone, cube, part });
    }
  }

  const pending = new Set(sources);
  while (pending.size > 0) {
    const first = pending.values().next().value as SkinCubeSource;
    const component: SkinCubeSource[] = [];
    const queue = [first];
    pending.delete(first);
    while (queue.length > 0) {
      const source = queue.pop()!;
      component.push(source);
      for (const candidate of pending) {
        if (!samePresegmentedColumn(source, candidate)) continue;
        pending.delete(candidate);
        queue.push(candidate);
      }
    }

    if (!isCompleteSkinColumn(component)) continue;
    const part = component[0].part;
    const top = Math.max(...component.map((source) => source.cube.to[1]));
    const pieces = humanoidRenderPieces(part, component.some((source) => source.bone !== component[0].bone));
    const pieceSources = pieces.map((piece) => component.find((candidate) => candidate.cube.from[1] <= top - piece.endY + 1e-3 && candidate.cube.to[1] >= top - piece.startY - 1e-3));
    if (pieceSources.some((source) => !source)) {
      diagnostics.push({ severity: "warning", code: "blockbench_skin_split_preserved", message: `${component[0].bone.group.name}: skin segments do not match the source cubes; original geometry is preserved without automatic skin assignment.`, sourcePath: component[0].cube.uuid });
      continue;
    }
    for (const [index, piece] of pieces.entries()) {
      const pieceTop = top - piece.startY;
      const pieceBottom = top - piece.endY;
      const source = pieceSources[index]!;
      const height = source.cube.to[1] - source.cube.from[1];
      const startRatio = (source.cube.to[1] - pieceTop) / height;
      const endRatio = (source.cube.to[1] - pieceBottom) / height;
      const suffix = piece.kind === "joint_fill" ? `joint_${piece.jointSide}_${piece.order}` : `${piece.order}`;
      const sliced: BbCube = {
        ...source.cube,
        uuid: `${source.cube.uuid}_skin_${suffix}`,
        name: `${source.cube.name ?? "Cube"} Skin ${suffix}`,
        from: [source.cube.from[0], pieceBottom, source.cube.from[2]],
        to: [source.cube.to[0], pieceTop, source.cube.to[2]],
        faces: sliceVerticalFaceUvs(source.cube.faces, startRatio, endRatio),
      };
      sourceIds.set(sliced, source.cube.uuid);
      result.get(source.bone.uuid)!.push(sliced);
    }
    component.forEach((source) => consumed.add(source.cube.uuid));
  }
  return consumed;
}

function samePresegmentedColumn(first: SkinCubeSource, second: SkinCubeSource): boolean {
  if (first.part !== second.part || (!isRelatedBone(first.bone, second.bone) && first.bone !== second.bone)) return false;
  if (![0, 2].every((axis) => Math.abs(first.cube.from[axis] - second.cube.from[axis]) <= 1e-3
    && Math.abs(first.cube.to[axis] - second.cube.to[axis]) <= 1e-3)) return false;
  return Math.abs(first.cube.to[1] - second.cube.from[1]) <= 1e-3 || Math.abs(second.cube.to[1] - first.cube.from[1]) <= 1e-3;
}

function isCompleteSkinColumn(component: SkinCubeSource[]): boolean {
  if (component.length < 2 || component.some((source) => source.part !== component[0].part)) return false;
  const fromY = Math.min(...component.map((source) => source.cube.from[1]));
  const toY = Math.max(...component.map((source) => source.cube.to[1]));
  const first = component[0].cube;
  const width = first.to[0] - first.from[0];
  const depth = first.to[2] - first.from[2];
  const coveredHeight = component.reduce((sum, source) => sum + source.cube.to[1] - source.cube.from[1], 0);
  return Math.abs(coveredHeight - (toY - fromY)) <= 1e-3
    && isStandardHumanoidPartSize(component[0].part, [width, toY - fromY, depth]);
}

function sliceVerticalFaceUvs(faces: BbCube["faces"], startRatio: number, endRatio: number): BbCube["faces"] {
  const slicedFaces: BbCube["faces"] = {};
  for (const [direction, face] of Object.entries(faces)) {
    if (!["north", "south", "east", "west"].includes(direction) || face.uv?.length !== 4) {
      slicedFaces[direction] = face;
      continue;
    }

    slicedFaces[direction] = { ...face, uv: sliceVerticalUv(face.uv, face.rotation ?? 0, startRatio, endRatio, "low") };
  }
  return slicedFaces;
}

function findLowerJointBone(bone: BoneEntry, cube: BbCube, part: PlayerSkinPart, bones: BoneEntry[]): BoneEntry | undefined {
  if (part === "head") return undefined;
  const names = part === "body"
    ? ["lowerbody", "abdomen"]
    : part.endsWith("arm") ? ["forearm", "lowerarm", "elbow"] : ["lowerleg", "shin", "knee"];
  const expectedY = (cube.from[1] + cube.to[1]) / 2;
  const candidates = bones.filter((candidate) => candidate !== bone
    && isDescendantOf(candidate, bone)
    && names.some((name) => normalizeBlockbenchName(candidate.group.name)?.includes(name))
    && Math.abs(candidate.group.origin[1] - expectedY) <= 2);
  return candidates.length === 1 ? candidates[0] : undefined;
}

function isDescendantOf(candidate: BoneEntry, ancestor: BoneEntry): boolean {
  for (let current = candidate.parent; current; current = current.parent) {
    if (current === ancestor) return true;
  }
  return false;
}

function isRelatedBone(first: BoneEntry, second: BoneEntry): boolean {
  return isDescendantOf(first, second)
    || isDescendantOf(second, first)
    || (first.parent !== undefined && first.parent === second.parent);
}

function sameCubeBounds(first: BbCube, second: BbCube): boolean {
  return first.from.every((value, axis) => Math.abs(value - second.from[axis]) <= 1e-7)
    && first.to.every((value, axis) => Math.abs(value - second.to[axis]) <= 1e-7);
}

function isSkinLayerCube(cube: BbCube): boolean {
  const name = normalizeBlockbenchName(cube.name) ?? "";
  return (cube.inflate ?? 0) > 0 || ["layer", "overlay", "sleeve", "pants", "jacket", "headwear", "hat"].some((marker) => name.includes(marker));
}

function nearbySkinLayerBounds(layer: BbCube, base: BbCube): boolean {
  return [0, 1, 2].every((axis) => {
    const layerCenter = (layer.from[axis] + layer.to[axis]) / 2;
    const baseCenter = (base.from[axis] + base.to[axis]) / 2;
    const layerSize = layer.to[axis] - layer.from[axis] + (layer.inflate ?? 0) * 2;
    const baseSize = base.to[axis] - base.from[axis] + (base.inflate ?? 0) * 2;
    return Math.abs(layerCenter - baseCenter) <= 0.25 && Math.abs(layerSize - baseSize) <= 0.5;
  });
}

function inferSkinAssignments(
  bones: BoneEntry[],
  playableCubesByBone: ReadonlyMap<string, BbCube[]>,
  sourceIds: ReadonlyMap<BbCube, string>,
): Map<BbCube, ImportedSkinPart> {
  const candidates: { cube: BbCube; part: ImportedSkinPart["part"]; centerY: number; sourceOrder: number; explicitOrder?: number }[] = [];
  let sourceOrder = 0;
  for (const bone of bones) {
    const part = inferSkinPart(bone);
    for (const cube of playableCubesByBone.get(bone.uuid) ?? []) {
      const split = sourceIds.get(cube) !== cube.uuid ? parseSplitSkinCube(cube.uuid) : null;
      if (!part || isSkinLayerCube(cube) || (split === null && !isStandardPlayerSkinCube(cube, part))) continue;
      candidates.push({
        cube,
        part,
        centerY: (cube.from[1] + cube.to[1]) / 2,
        sourceOrder: sourceOrder++,
        explicitOrder: split?.order,
      });
    }
  }

  const result = new Map<BbCube, ImportedSkinPart>();
  for (const part of ["head", "body", "left_arm", "right_arm", "left_leg", "right_leg"] as const) {
    candidates
      .filter((candidate) => candidate.part === part)
      .sort((first, second) => second.centerY - first.centerY || first.sourceOrder - second.sourceOrder)
      .forEach((candidate, order) => result.set(candidate.cube, { part, order: candidate.explicitOrder ?? order }));
  }
  return result;
}

function parseSplitSkinCube(uuid: string): SplitSkinCube | null {
  const match = uuid.match(SPLIT_SKIN_CUBE_PATTERN);
  if (!match) return null;
  if (match[1] === "upper") return { order: 0 };
  if (match[1] === "lower") return { order: 1 };
  if (match[2] !== undefined) return { order: Number(match[2]) };
  return { order: Number(match[4]), jointSide: match[3] as "upper" | "lower" };
}

function isStandardPlayerSkinCube(cube: BbCube, part: ImportedSkinPart["part"]): boolean {
  const size = cube.to.map((value, axis) => Math.abs(value - cube.from[axis]));
  return isStandardHumanoidPartSize(part, size);
}

function inferSkinPart(bone: BoneEntry): ImportedSkinPart["part"] | undefined {
  for (let current: BoneEntry | undefined = bone; current; current = current.parent) {
    const part = inferHumanoidPart(current.group.name);
    if (part) return part;
  }
  return undefined;
}
